package forge.view;

import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.time.StopWatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import forge.LobbyPlayer;
import forge.ai.nn.EpsilonGreedyBridge;
import forge.ai.nn.NNBridge;
import forge.ai.nn.NNFullController;
import forge.ai.nn.NNHybridController;
import forge.ai.nn.OnnxBridge;
import forge.ai.nn.RandomBridge;
import forge.cli.ExitCode;
import forge.cli.ProgressBar;
import forge.cli.SimCommand;
import forge.cli.json.SimulationResult;
import forge.cli.stats.WilsonInterval;
import forge.deck.Deck;
import forge.deck.DeckGroup;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameLogEntry;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.tournament.system.AbstractTournament;
import forge.gamemodes.tournament.system.TournamentBracket;
import forge.gamemodes.tournament.system.TournamentPairing;
import forge.gamemodes.tournament.system.TournamentPlayer;
import forge.gamemodes.tournament.system.TournamentRoundRobin;
import forge.gamemodes.tournament.system.TournamentSwiss;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.BuildInfo;
import forge.util.Lang;
import forge.util.TextUtil;
import forge.util.WordUtil;
import forge.util.storage.IStorage;

public class SimulateMatch {
    // Null output stream to suppress debug prints during quiet mode
    private static final PrintStream NULL_PRINT_STREAM = new PrintStream(new OutputStream() {
        @Override public void write(int b) { }
        @Override public void write(byte[] b) { }
        @Override public void write(byte[] b, int off, int len) { }
    });

    // Store original stdout/stderr for restoration
    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;

    /**
     * Record game outcome and close training data writers for NN controllers.
     */
    private static void finishNNControllers(Game game) {
        if (game == null || game.getOutcome() == null) return;
        for (forge.game.player.Player p : game.getRegisteredPlayers()) {
            forge.game.player.PlayerController ctrl = p.getController();
            if (ctrl instanceof NNFullController) {
                boolean won = !game.getOutcome().isDraw() && game.getOutcome().isWinner(p.getLobbyPlayer());
                ((NNFullController) ctrl).finishGame(won, game.getPhaseHandler().getTurn(),
                        game.getOutcome().getWinCondition().toString());
            } else if (ctrl instanceof NNHybridController) {
                boolean won = !game.getOutcome().isDraw() && game.getOutcome().isWinner(p.getLobbyPlayer());
                ((NNHybridController) ctrl).finishGame(won, game.getPhaseHandler().getTurn(),
                        game.getOutcome().getWinCondition().toString());
            }
        }
    }

    /**
     * Configuration for a player in parallel simulation.
     * Stores the original deck and player info so fresh RegisteredPlayer objects
     * can be created for each parallel game (avoiding thread-safety issues).
     */
    private static class PlayerConfig {
        final Deck deck;
        final String name;
        final String aiProfile;
        final GameType gameType;
        // NN mode fields (null for regular AI players)
        NNBridge nnBridge;
        String nnExportDir;
        boolean nnFullMode;

        PlayerConfig(Deck deck, String name, String aiProfile, GameType gameType) {
            this.deck = deck;
            this.name = name;
            this.aiProfile = aiProfile;
            this.gameType = gameType;
        }

        RegisteredPlayer createRegisteredPlayer() {
            RegisteredPlayer rp;
            if (gameType.equals(GameType.Commander)) {
                rp = RegisteredPlayer.forCommander((Deck) deck.copyTo(deck.getName()));
            } else {
                rp = new RegisteredPlayer((Deck) deck.copyTo(deck.getName()));
            }
            if (nnBridge != null) {
                rp.setPlayer(GamePlayerUtil.createNNPlayer(name, nnBridge, nnExportDir, nnFullMode));
            } else {
                rp.setPlayer(GamePlayerUtil.createAiPlayer(name, aiProfile));
            }
            return rp;
        }
    }

    /**
     * Simple result holder for parallel game execution.
     */
    private static class GameResult {
        boolean isDraw;
        int winnerIndex;
        String winnerName;
        long timeMs;
        int turns;
        Game game; // For JSON output with full logs
    }

    /**
     * New entry point for simulation using picocli SimCommand.
     * All diagnostic output goes to stderr, only results to stdout.
     *
     * @param cmd The parsed SimCommand with all options
     * @return Exit code (0 = success, non-zero = error)
     */
    public static int listProfiles() {
        File aiDir = new File(ForgeConstants.AI_PROFILE_DIR);
        ORIGINAL_OUT.println("Available AI profiles:");
        ORIGINAL_OUT.println();
        if (aiDir.isDirectory()) {
            File[] profiles = aiDir.listFiles((dir, name) -> name.endsWith(".ai"));
            if (profiles != null) {
                Arrays.sort(profiles);
                for (File f : profiles) {
                    String name = f.getName().replace(".ai", "");
                    ORIGINAL_OUT.printf("  %-16s  (%s)%n", name, f.getAbsolutePath());
                }
            }
        } else {
            ORIGINAL_ERR.println("Warning: AI profile directory not found at " + aiDir.getAbsolutePath());
        }
        ORIGINAL_OUT.println();
        ORIGINAL_OUT.println("Usage: -P 1:Simulation -P 2:Default");
        ORIGINAL_OUT.println("  or:  -P1 Simulation -P2 Default  (legacy syntax)");
        return ExitCode.SUCCESS;
    }

    public static int simulate(SimCommand cmd) {
        // Suppress noisy initialization messages (e.g. "was not assigned to any set")
        // in quiet mode or when producing structured output
        boolean suppressInit = cmd.isQuiet() || cmd.getNumJobs() != null
                || cmd.isJsonOutput() || cmd.isCsvOutput();

        // Redirect initialization output to stderr (or null if suppressed)
        System.setOut(suppressInit ? NULL_PRINT_STREAM : ORIGINAL_ERR);
        if (suppressInit) {
            System.setErr(NULL_PRINT_STREAM);
        }

        try {
            FModel.initialize(null, null);
        } finally {
            System.setOut(ORIGINAL_OUT);
            System.setErr(ORIGINAL_ERR);
        }

        ORIGINAL_ERR.println("Simulation mode");

        // Validate that we have decks
        if (cmd.getDecks().isEmpty() && cmd.getDeckDirectory() == null) {
            ORIGINAL_ERR.println("Error: No decks specified. Use -d/--deck or -D/--deck-directory");
            return ExitCode.ARGS_ERROR;
        }

        int nGames = cmd.getNumGames();
        Integer matchSize = cmd.getMatchSize();

        // Quiet mode: suppress game logs if -q flag OR -j flag is passed
        boolean quietMode = cmd.isQuiet() || cmd.getNumJobs() != null;
        boolean structuredOutput = cmd.isJsonOutput() || cmd.isCsvOutput();
        boolean outputGamelog = !quietMode && !structuredOutput;

        GameType type;
        try {
            type = GameType.valueOf(WordUtil.capitalize(cmd.getFormat()));
        } catch (IllegalArgumentException e) {
            ORIGINAL_ERR.println("Error: Invalid format '" + cmd.getFormat() + "'");
            return ExitCode.ARGS_ERROR;
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        // Parse per-player AI profiles
        Map<Integer, String> aiProfiles = new HashMap<>();
        for (int p = 0; p < 8; p++) {
            String profile = cmd.getPlayerProfile(p);
            if (profile != null) {
                aiProfiles.put(p, profile.trim());
            }
        }

        if (matchSize != null && matchSize > 0) {
            rules.setGamesPerMatch(matchSize);
        }

        rules.setSimTimeout(cmd.getTimeout());

        // Custom base directory for relative deck paths
        String customDeckBaseDir = null;
        if (cmd.getBaseDir() != null) {
            customDeckBaseDir = cmd.getBaseDir().getAbsolutePath();
            if (!cmd.getBaseDir().isDirectory()) {
                ORIGINAL_ERR.println("Warning: Base deck directory not found - " + customDeckBaseDir);
            }
        }

        // NN mode setup
        NNBridge nnBridge = null;
        String nnExportDir = null;
        boolean nnFullMode = false;

        if (cmd.isNnMode()) {
            // Validate NN flags
            if (cmd.isNnHybrid() && cmd.isNnFull()) {
                ORIGINAL_ERR.println("Error: Cannot use both --nn-hybrid and --nn-full");
                return ExitCode.ARGS_ERROR;
            }
            if (!cmd.isNnRandom() && cmd.getNnModel() == null) {
                ORIGINAL_ERR.println("Error: NN mode requires --nn-random or --nn-model FILE");
                return ExitCode.ARGS_ERROR;
            }

            nnFullMode = cmd.isNnFull();

            if (cmd.isNnRandom()) {
                nnBridge = new RandomBridge();
                ORIGINAL_ERR.println("NN mode: " + (nnFullMode ? "full" : "hybrid") + " with random bridge");
            } else {
                File modelFile = cmd.getNnModel();
                if (!modelFile.exists()) {
                    ORIGINAL_ERR.println("Error: ONNX model file not found - " + modelFile.getAbsolutePath());
                    return ExitCode.ARGS_ERROR;
                }
                try {
                    nnBridge = new OnnxBridge(modelFile.getAbsolutePath());
                    ORIGINAL_ERR.println("NN mode: " + (nnFullMode ? "full" : "hybrid") + " with ONNX model " + modelFile.getName());
                } catch (Exception e) {
                    ORIGINAL_ERR.println("Error: Failed to load ONNX model - " + e.getMessage());
                    return ExitCode.ARGS_ERROR;
                }
            }

            if (cmd.getNnExportDir() != null) {
                nnExportDir = cmd.getNnExportDir().getAbsolutePath();
                File exportDir = cmd.getNnExportDir();
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                ORIGINAL_ERR.println("NN training data export: " + nnExportDir);
            }

            // Wrap with epsilon-greedy if requested
            if (cmd.getNnEpsilon() > 0 && nnBridge != null) {
                nnBridge = new EpsilonGreedyBridge(nnBridge, cmd.getNnEpsilon());
                ORIGINAL_ERR.println("NN exploration: epsilon=" + cmd.getNnEpsilon());
            }
        }

        // Tournament mode
        if (cmd.getTournamentType() != null) {
            boolean useSnapshot = cmd.isUseSnapshot();
            simulateTournamentFromCmd(cmd, rules, outputGamelog, aiProfiles, useSnapshot, customDeckBaseDir);
            System.out.flush();
            return ExitCode.SUCCESS;
        }

        // Build player configurations
        List<RegisteredPlayer> pp = new ArrayList<>();
        List<PlayerConfig> playerConfigs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        List<String> deckNames = new ArrayList<>();

        int i = 1;
        for (String deck : cmd.getDecks()) {
            Deck d = deckFromCommandLineParameter(deck, type, customDeckBaseDir);
            if (d == null) {
                ORIGINAL_ERR.println("Error: Could not load deck - " + deck);
                return ExitCode.DECK_ERROR;
            }
            if (i > 1) {
                sb.append(" vs ");
            }
            String name = TextUtil.concatNoSpace("Ai(", String.valueOf(i), ")-", d.getName());
            sb.append(name);
            deckNames.add(d.getName());

            String profile = aiProfiles.getOrDefault(i - 1, "");

            // Store config for parallel execution
            PlayerConfig config = new PlayerConfig(d, name, profile, type);
            if (nnBridge != null) {
                String nnPlayerSetting = cmd.getNnPlayer();
                boolean thisPlayerGetsNN = "all".equals(nnPlayerSetting)
                    || String.valueOf(i).equals(nnPlayerSetting);
                if (thisPlayerGetsNN) {
                    config.nnBridge = nnBridge;
                    config.nnExportDir = nnExportDir;
                    config.nnFullMode = nnFullMode;
                }
            }
            playerConfigs.add(config);

            // Also create RegisteredPlayer for sequential execution
            RegisteredPlayer rp;
            if (type.equals(GameType.Commander)) {
                rp = RegisteredPlayer.forCommander(d);
            } else {
                rp = new RegisteredPlayer(d);
            }
            if (nnBridge != null) {
                String nnPlayerSetting = cmd.getNnPlayer();
                boolean thisPlayerGetsNN = "all".equals(nnPlayerSetting)
                    || String.valueOf(i).equals(nnPlayerSetting);
                if (thisPlayerGetsNN) {
                    rp.setPlayer(GamePlayerUtil.createNNPlayer(name, nnBridge, nnExportDir, nnFullMode));
                } else {
                    rp.setPlayer(GamePlayerUtil.createAiPlayer(name, profile));
                }
            } else {
                rp.setPlayer(GamePlayerUtil.createAiPlayer(name, profile));
            }
            pp.add(rp);
            i++;
        }

        if (pp.size() < 2) {
            ORIGINAL_ERR.println("Error: Need at least 2 decks for simulation");
            return ExitCode.ARGS_ERROR;
        }

        boolean useSnapshot = cmd.isUseSnapshot();

        // Number of parallel threads
        int numThreads = 1;
        if (cmd.getNumJobs() != null) {
            numThreads = cmd.getNumJobs();
            if (numThreads < 1) numThreads = 1;
            if (numThreads > Runtime.getRuntime().availableProcessors() * 2) {
                numThreads = Runtime.getRuntime().availableProcessors() * 2;
            }
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        if (!useSnapshot) {
            sb.append(" (snapshot restore DISABLED)");
        }
        if (numThreads > 1) {
            sb.append(" (").append(numThreads).append(" parallel threads)");
        }

        ORIGINAL_ERR.println(sb);

        // Prepare JSON result if needed
        SimulationResult jsonResult = null;
        if (cmd.isJsonOutput()) {
            jsonResult = new SimulationResult();
            jsonResult.version = BuildInfo.getVersionString();
            jsonResult.config = new SimulationResult.SimulationConfig();
            jsonResult.config.decks = deckNames;
            jsonResult.config.format = type.name();
            jsonResult.config.gamesRequested = nGames;
            jsonResult.config.matchSize = matchSize;
            jsonResult.config.timeoutSeconds = cmd.getTimeout();
            jsonResult.config.snapshotEnabled = useSnapshot;
            jsonResult.config.parallelJobs = cmd.getNumJobs();
            jsonResult.config.aiProfiles = new ArrayList<>();
            for (int p = 0; p < pp.size(); p++) {
                jsonResult.config.aiProfiles.add(aiProfiles.getOrDefault(p, "Default"));
            }
        }

        // Suppress stdout during game execution:
        // - quiet mode: suppress all output
        // - json/csv mode: suppress to prevent debug prints from corrupting structured output
        if (quietMode || structuredOutput) {
            System.setOut(NULL_PRINT_STREAM);
            System.setErr(NULL_PRINT_STREAM);
        }

        List<GameResult> allResults = new ArrayList<>();
        long totalStartTime = System.currentTimeMillis();

        try {
            if (matchSize != null && matchSize > 0) {
                // Match mode - must be sequential
                Match mc = new Match(rules, pp, "Test");
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    GameResult result = simulateSingleMatchWithResult(mc, iGame, outputGamelog, useSnapshot, structuredOutput, structuredOutput);
                    allResults.add(result);
                    iGame++;
                }
            } else if (numThreads > 1 && nGames > 1) {
                // Parallel batch mode — restore streams since simulateParallelWithResults
                // manages its own suppression
                if (quietMode || structuredOutput) {
                    System.setOut(ORIGINAL_OUT);
                    System.setErr(ORIGINAL_ERR);
                }
                allResults = simulateParallelWithResults(rules, playerConfigs, nGames, numThreads, outputGamelog, useSnapshot, structuredOutput);
            } else {
                // Sequential batch mode
                Match mc = new Match(rules, pp, "Test");
                for (int iGame = 0; iGame < nGames; iGame++) {
                    GameResult result = simulateSingleMatchWithResult(mc, iGame, outputGamelog, useSnapshot, structuredOutput, structuredOutput);
                    allResults.add(result);
                }
            }
        } finally {
            System.setOut(ORIGINAL_OUT);
            System.setErr(ORIGINAL_ERR);
        }

        long totalTime = System.currentTimeMillis() - totalStartTime;

        // Output results
        if (cmd.isJsonOutput()) {
            outputJsonResult(jsonResult, allResults, playerConfigs, totalTime);
        } else if (cmd.isCsvOutput()) {
            outputCsvResult(allResults, playerConfigs, totalTime);
        } else if (numThreads > 1) {
            // Summary already printed by parallel method
        }

        System.out.flush();
        return ExitCode.SUCCESS;
    }

    /**
     * Determines the winner index by comparing against the match's registered players.
     * Returns -1 if no match found.
     */
    private static int determineWinnerIndex(Game game, Match mc) {
        LobbyPlayer winningLobby = game.getOutcome().getWinningLobbyPlayer();
        List<RegisteredPlayer> players = mc.getPlayers();
        for (int idx = 0; idx < players.size(); idx++) {
            if (players.get(idx).getPlayer().equals(winningLobby)) {
                return idx;
            }
        }
        // Fallback: match by name
        String winnerName = winningLobby.getName();
        for (int idx = 0; idx < players.size(); idx++) {
            if (players.get(idx).getPlayer().getName().equals(winnerName)) {
                return idx;
            }
        }
        return -1;
    }

    /**
     * Simulates a single game and returns a GameResult with full data.
     * @param jsonMode when true, per-game status goes to stderr to keep stdout clean for JSON
     */
    private static GameResult simulateSingleMatchWithResult(final Match mc, int iGame, boolean outputGamelog, boolean useSnapshot, boolean collectLog, boolean jsonMode) {
        final GameResult result = new GameResult();
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        g1.EXPERIMENTAL_RESTORE_SNAPSHOT = useSnapshot;

        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ORIGINAL_ERR.println("Stopping slow match as draw");
        } catch (Exception | StackOverflowError e) {
            ORIGINAL_ERR.println("Game error: " + e.getMessage());
        } finally {
            if (sw.isStarted()) {
                sw.stop();
            }
            if (!g1.isGameOver()) {
                g1.setGameOver(GameEndReason.Draw);
            }
        }

        result.timeMs = sw.getTime();
        result.isDraw = g1.getOutcome().isDraw();
        result.turns = g1.getPhaseHandler().getTurn();

        // Finalize NN training data writers
        finishNNControllers(g1);

        if (!result.isDraw) {
            result.winnerName = g1.getOutcome().getWinningLobbyPlayer().getName();
            result.winnerIndex = determineWinnerIndex(g1, mc);
        }

        // Store game reference for JSON log extraction
        if (collectLog) {
            result.game = g1;
        }

        if (outputGamelog) {
            List<GameLogEntry> log = g1.getGameLog().getLogEntries(null);
            Collections.reverse(log);
            for (GameLogEntry l : log) {
                ORIGINAL_OUT.println(l);
            }
        }

        // Show game result — to stderr in JSON mode to keep stdout clean for JSON
        PrintStream resultStream = jsonMode ? ORIGINAL_ERR : ORIGINAL_OUT;
        if (result.isDraw) {
            resultStream.printf("Game %d: Draw (%d ms)%n", 1 + iGame, result.timeMs);
        } else {
            resultStream.printf("Game %d: %s wins (%d ms)%n", 1 + iGame, result.winnerName, result.timeMs);
        }

        return result;
    }

    /**
     * Runs multiple independent games in parallel and returns all results.
     */
    private static List<GameResult> simulateParallelWithResults(GameRules rules, List<PlayerConfig> playerConfigs,
                                         int nGames, int numThreads, boolean outputGamelog, boolean useSnapshot, boolean collectLog) {
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final AtomicInteger wins1 = new AtomicInteger(0);
        final AtomicInteger wins2 = new AtomicInteger(0);
        final AtomicInteger draws = new AtomicInteger(0);
        final AtomicInteger completed = new AtomicInteger(0);
        final long startTime = System.currentTimeMillis();
        final List<GameResult> allResults = Collections.synchronizedList(new ArrayList<>());
        final ProgressBar progress = new ProgressBar(ORIGINAL_ERR, nGames);

        System.setOut(NULL_PRINT_STREAM);
        System.setErr(NULL_PRINT_STREAM);

        List<Future<?>> futures = new ArrayList<>();

        for (int iGame = 0; iGame < nGames; iGame++) {
            final int gameNum = iGame;
            futures.add(executor.submit(() -> {
                try {
                    List<RegisteredPlayer> freshPlayers = new ArrayList<>();
                    for (PlayerConfig config : playerConfigs) {
                        freshPlayers.add(config.createRegisteredPlayer());
                    }
                    Match mc = new Match(rules, freshPlayers, "Test-" + gameNum);
                    GameResult result = simulateSingleMatchQuietNoSuppress(mc, gameNum, useSnapshot, collectLog);
                    allResults.add(result);

                    if (result.isDraw) {
                        draws.incrementAndGet();
                    } else if (result.winnerIndex == 0) {
                        wins1.incrementAndGet();
                    } else {
                        wins2.incrementAndGet();
                    }
                    completed.incrementAndGet();
                    progress.update(completed.get());
                } catch (Exception e) {
                    completed.incrementAndGet();
                    progress.update(completed.get());
                }
            }));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                // Already handled in the task
            }
        }

        executor.shutdown();
        progress.finish();

        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);

        long totalTime = System.currentTimeMillis() - startTime;

        // Print summary — to stderr in JSON mode to keep stdout clean
        PrintStream summaryStream = collectLog ? ORIGINAL_ERR : System.out;
        summaryStream.println("=== Simulation Summary ===");
        summaryStream.printf("Total games: %d%n", nGames);
        double[] ci1 = WilsonInterval.calculate95(wins1.get(), nGames);
        double[] ci2 = WilsonInterval.calculate95(wins2.get(), nGames);
        summaryStream.printf("Player 1 wins: %d (%s)%n", wins1.get(),
                WilsonInterval.format(100.0 * wins1.get() / nGames, ci1));
        summaryStream.printf("Player 2 wins: %d (%s)%n", wins2.get(),
                WilsonInterval.format(100.0 * wins2.get() / nGames, ci2));
        summaryStream.printf("Draws: %d (%.1f%%)%n", draws.get(), 100.0 * draws.get() / nGames);
        summaryStream.printf("Total time: %d ms (%.1f ms/game avg, %.1f games/sec)%n",
                totalTime, (double) totalTime / nGames, 1000.0 * nGames / totalTime);

        return allResults;
    }

    /**
     * Simulates a single game without outputting the game log (for parallel execution).
     * Does NOT suppress stdout - caller is responsible for suppression.
     */
    private static GameResult simulateSingleMatchQuietNoSuppress(final Match mc, int iGame, boolean useSnapshot, boolean collectLog) {
        final GameResult result = new GameResult();
        final long startTime = System.currentTimeMillis();

        final Game g1 = mc.createGame();
        g1.EXPERIMENTAL_RESTORE_SNAPSHOT = useSnapshot;

        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Timeout - treat as draw
        } catch (Exception | StackOverflowError e) {
            // Error - treat as draw
        } finally {
            if (!g1.isGameOver()) {
                g1.setGameOver(GameEndReason.Draw);
            }
        }

        result.timeMs = System.currentTimeMillis() - startTime;
        result.isDraw = g1.getOutcome().isDraw();
        result.turns = g1.getPhaseHandler().getTurn();

        // Finalize NN training data writers
        finishNNControllers(g1);

        if (!result.isDraw) {
            result.winnerName = g1.getOutcome().getWinningLobbyPlayer().getName();
            result.winnerIndex = determineWinnerIndex(g1, mc);
        }

        if (collectLog) {
            result.game = g1;
        }

        return result;
    }

    /**
     * Outputs simulation results in JSON format.
     */
    private static void outputJsonResult(SimulationResult jsonResult, List<GameResult> results,
                                         List<PlayerConfig> playerConfigs, long totalTime) {
        // Build summary
        jsonResult.summary = new SimulationResult.SimulationSummary();
        jsonResult.summary.totalGames = results.size();
        jsonResult.summary.completedGames = results.size();
        jsonResult.summary.totalTimeMs = totalTime;
        jsonResult.summary.averageGameTimeMs = results.isEmpty() ? 0 : (double) totalTime / results.size();
        jsonResult.summary.gamesPerSecond = results.isEmpty() ? 0 : 1000.0 * results.size() / totalTime;

        // Count wins per player
        int[] wins = new int[playerConfigs.size()];
        int drawCount = 0;
        for (GameResult r : results) {
            if (r.isDraw) {
                drawCount++;
            } else if (r.winnerIndex >= 0 && r.winnerIndex < wins.length) {
                wins[r.winnerIndex]++;
            }
        }
        jsonResult.summary.draws = drawCount;

        // Build player summaries
        for (int p = 0; p < playerConfigs.size(); p++) {
            SimulationResult.PlayerSummary ps = new SimulationResult.PlayerSummary();
            ps.playerIndex = p;
            ps.name = playerConfigs.get(p).name;
            ps.deck = playerConfigs.get(p).deck.getName();
            ps.aiProfile = playerConfigs.get(p).aiProfile.isEmpty() ? "Default" : playerConfigs.get(p).aiProfile;
            ps.wins = wins[p];
            ps.losses = results.size() - wins[p] - drawCount;
            ps.winRate = results.isEmpty() ? 0 : 100.0 * wins[p] / results.size();
            double[] ci = WilsonInterval.calculate95(wins[p], results.size());
            ps.winRateCiLower = ci[0];
            ps.winRateCiUpper = ci[1];
            jsonResult.summary.players.add(ps);
        }

        // Build individual game results
        int gameNum = 1;
        for (GameResult r : results) {
            SimulationResult.GameResult gr = new SimulationResult.GameResult();
            gr.gameNumber = gameNum++;
            gr.isDraw = r.isDraw;
            gr.winner = r.winnerName;
            gr.winnerIndex = r.isDraw ? null : r.winnerIndex;
            gr.durationMs = r.timeMs;
            gr.turns = r.turns;
            gr.endReason = r.game != null && r.game.getOutcome() != null ?
                          r.game.getOutcome().getWinCondition().toString() : "Unknown";

            // Add full game log if game reference is available
            if (r.game != null) {
                gr.log = new ArrayList<>();
                List<GameLogEntry> logEntries = r.game.getGameLog().getLogEntries(null);
                for (GameLogEntry entry : logEntries) {
                    SimulationResult.GameLogEntry jsonEntry = new SimulationResult.GameLogEntry();
                    jsonEntry.type = entry.type.name();
                    jsonEntry.message = entry.message;
                    gr.log.add(jsonEntry);
                }
            }

            jsonResult.games.add(gr);
        }

        // Output JSON to stdout
        Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();
        ORIGINAL_OUT.println(gson.toJson(jsonResult));
    }

    /**
     * Outputs simulation results in CSV format.
     * Header row + one row per game, plus a summary section.
     */
    private static void outputCsvResult(List<GameResult> results, List<PlayerConfig> playerConfigs, long totalTime) {
        // Per-game rows
        ORIGINAL_OUT.println("game,winner,winner_index,is_draw,turns,duration_ms");
        int gameNum = 1;
        for (GameResult r : results) {
            ORIGINAL_OUT.printf("%d,%s,%s,%s,%d,%d%n",
                    gameNum++,
                    r.isDraw ? "" : csvEscape(r.winnerName),
                    r.isDraw ? "" : String.valueOf(r.winnerIndex),
                    r.isDraw,
                    r.turns,
                    r.timeMs);
        }

        // Summary section (separated by blank line)
        ORIGINAL_OUT.println();
        ORIGINAL_OUT.println("player_index,deck,ai_profile,wins,losses,win_rate,ci_lower_95,ci_upper_95");
        int[] wins = new int[playerConfigs.size()];
        int drawCount = 0;
        for (GameResult r : results) {
            if (r.isDraw) {
                drawCount++;
            } else if (r.winnerIndex >= 0 && r.winnerIndex < wins.length) {
                wins[r.winnerIndex]++;
            }
        }
        for (int p = 0; p < playerConfigs.size(); p++) {
            double winRate = results.isEmpty() ? 0 : 100.0 * wins[p] / results.size();
            double[] ci = WilsonInterval.calculate95(wins[p], results.size());
            ORIGINAL_OUT.printf("%d,%s,%s,%d,%d,%.1f,%.1f,%.1f%n",
                    p,
                    csvEscape(playerConfigs.get(p).deck.getName()),
                    csvEscape(playerConfigs.get(p).aiProfile.isEmpty() ? "Default" : playerConfigs.get(p).aiProfile),
                    wins[p],
                    results.size() - wins[p] - drawCount,
                    winRate,
                    ci[0], ci[1]);
        }
    }

    /**
     * Escapes a value for CSV output (wraps in quotes if it contains commas or quotes).
     */
    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Tournament simulation from SimCommand.
     */
    private static void simulateTournamentFromCmd(SimCommand cmd, GameRules rules, boolean outputGamelog,
                                                   Map<Integer, String> aiProfiles, boolean useSnapshot,
                                                   String customDeckBaseDir) {
        String tournament = cmd.getTournamentType();
        AbstractTournament tourney = null;
        int matchPlayers = cmd.getPlayersPerMatch();

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;

        for (String deck : cmd.getDecks()) {
            Deck d = deckFromCommandLineParameter(deck, rules.getGameType(), customDeckBaseDir);
            if (d == null) {
                ORIGINAL_ERR.println("Error: Could not load deck - " + deck);
                return;
            }

            deckGroup.addAiDeck(d);
            String profile = aiProfiles.getOrDefault(numPlayers, "");
            players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), profile), numPlayers));
            numPlayers++;
        }

        if (cmd.getDeckDirectory() != null) {
            File folder = cmd.getDeckDirectory();
            if (!folder.isDirectory()) {
                ORIGINAL_ERR.println("Error: Directory not found - " + folder.getAbsolutePath());
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        ORIGINAL_ERR.println("Error: Could not load deck - " + deck.getName());
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    String profile = aiProfiles.getOrDefault(numPlayers, "");
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), profile), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            ORIGINAL_ERR.println("Error: No decks/players found.");
            return;
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }

        if (tourney == null) {
            ORIGINAL_ERR.println("Error: Invalid tournament type - " + tournament);
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        ORIGINAL_OUT.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));

        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    ORIGINAL_OUT.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                ORIGINAL_OUT.println();
                ORIGINAL_OUT.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));

                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    ORIGINAL_OUT.println(pairing.outputHeader());
                }
                ORIGINAL_OUT.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            ORIGINAL_OUT.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");

                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        ORIGINAL_ERR.println(e.toString());
                        if (exceptions > 5) {
                            ORIGINAL_ERR.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            ORIGINAL_ERR.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }
                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        ORIGINAL_OUT.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        ORIGINAL_OUT.println();
                        break;
                    }
                }
            }

            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();

        // Output matchup matrix for round-robin tournaments
        if ("roundrobin".equalsIgnoreCase(tournament)) {
            outputMatchupMatrix(tourney, players);
        }
    }

    /**
     * Outputs a pairwise matchup matrix showing win rates between all deck pairs.
     */
    private static void outputMatchupMatrix(AbstractTournament tourney, List<TournamentPlayer> players) {
        int n = players.size();
        // wins[i][j] = number of times player i beat player j
        int[][] wins = new int[n][n];
        int[][] games = new int[n][n];

        for (TournamentPairing pairing : tourney.getCompletedPairings()) {
            if (pairing.isBye() || pairing.getWinner() == null) continue;

            List<TournamentPlayer> paired = pairing.getPairedPlayers();
            if (paired.size() != 2) continue;

            int idx0 = paired.get(0).getIndex();
            int idx1 = paired.get(1).getIndex();
            int winnerIdx = pairing.getWinner().getIndex();

            games[idx0][idx1]++;
            games[idx1][idx0]++;
            if (winnerIdx == idx0) {
                wins[idx0][idx1]++;
            } else {
                wins[idx1][idx0]++;
            }
        }

        // Determine column width based on longest deck name
        int nameWidth = 4;
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            names[i] = players.get(i).getPlayer().getName();
            nameWidth = Math.max(nameWidth, names[i].length());
        }
        nameWidth = Math.min(nameWidth, 20);

        ORIGINAL_OUT.println();
        ORIGINAL_OUT.println("=== Matchup Matrix (row win % vs column) ===");

        // Header row
        StringBuilder header = new StringBuilder();
        header.append(String.format("%-" + nameWidth + "s", ""));
        for (int j = 0; j < n; j++) {
            String shortName = names[j].length() > 8 ? names[j].substring(0, 8) : names[j];
            header.append(String.format("  %8s", shortName));
        }
        ORIGINAL_OUT.println(header);

        // Data rows
        for (int i = 0; i < n; i++) {
            StringBuilder row = new StringBuilder();
            String rowName = names[i].length() > nameWidth ? names[i].substring(0, nameWidth) : names[i];
            row.append(String.format("%-" + nameWidth + "s", rowName));
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    row.append(String.format("  %8s", "---"));
                } else if (games[i][j] == 0) {
                    row.append(String.format("  %8s", "n/a"));
                } else {
                    double rate = 100.0 * wins[i][j] / games[i][j];
                    row.append(String.format("  %7.1f%%", rate));
                }
            }
            ORIGINAL_OUT.println(row);
        }
    }

    // =====================================================================
    // LEGACY METHODS - Kept for backward compatibility
    // =====================================================================

    /**
     * @deprecated Use {@link #simulate(SimCommand)} instead.
     * This method is kept for backward compatibility with existing code.
     */
    @Deprecated
    public static void simulate(String[] args) {
        FModel.initialize(null, null);

        System.out.println("Simulation mode");
        if (args.length < 4) {
            argumentHelp();
            return;
        }

        final Map<String, List<String>> params = new HashMap<>();
        List<String> options = null;

        for (int i = 1; i < args.length; i++) {
            final String a = args[i];

            if (a.charAt(0) == '-') {
                if (a.length() < 2) {
                    System.err.println("Error at argument " + a);
                    argumentHelp();
                    return;
                }

                options = new ArrayList<>();
                params.put(a.substring(1), options);
            } else if (options != null) {
                options.add(a);
            } else {
                System.err.println("Illegal parameter usage");
                return;
            }
        }

        int nGames = 1;
        if (params.containsKey("n")) {
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        boolean quietMode = params.containsKey("q") || params.containsKey("j");
        boolean outputGamelog = !quietMode;

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        Map<Integer, String> aiProfiles = new HashMap<>();
        for (int p = 1; p <= 8; p++) {
            if (params.containsKey("P" + p)) {
                aiProfiles.put(p - 1, params.get("P" + p).get(0).trim());
            }
        }

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }

        String customDeckBaseDir = null;
        if (params.containsKey("B")) {
            customDeckBaseDir = params.get("B").get(0);
            File dir = new File(customDeckBaseDir);
            if (!dir.isDirectory()) {
                System.out.println("Warning: Base deck directory not found - " + customDeckBaseDir);
            }
        }

        if (params.containsKey("t")) {
            boolean useSnapshot = params.containsKey("s");
            simulateTournament(params, rules, outputGamelog, aiProfiles, useSnapshot, customDeckBaseDir);
            System.out.flush();
            return;
        }

        List<RegisteredPlayer> pp = new ArrayList<>();
        List<PlayerConfig> playerConfigs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        int i = 1;

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type, customDeckBaseDir);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }
                if (i > 1) {
                    sb.append(" vs ");
                }
                String name = TextUtil.concatNoSpace("Ai(", String.valueOf(i), ")-", d.getName());
                sb.append(name);

                String profile = aiProfiles.getOrDefault(i - 1, "");

                playerConfigs.add(new PlayerConfig(d, name, profile, type));

                RegisteredPlayer rp;
                if (type.equals(GameType.Commander)) {
                    rp = RegisteredPlayer.forCommander(d);
                } else {
                    rp = new RegisteredPlayer(d);
                }
                rp.setPlayer(GamePlayerUtil.createAiPlayer(name, profile));
                pp.add(rp);
                i++;
            }
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }

        boolean useSnapshot = params.containsKey("s");

        int numThreads = 1;
        if (params.containsKey("j")) {
            numThreads = Integer.parseInt(params.get("j").get(0));
            if (numThreads < 1) numThreads = 1;
            if (numThreads > Runtime.getRuntime().availableProcessors() * 2) {
                numThreads = Runtime.getRuntime().availableProcessors() * 2;
            }
        }

        sb.append(" - ").append(Lang.nounWithNumeral(nGames, "game")).append(" of ").append(type);
        if (useSnapshot) {
            sb.append(" (snapshot restore enabled)");
        }
        if (numThreads > 1) {
            sb.append(" (").append(numThreads).append(" parallel threads)");
        }

        System.out.println(sb.toString());

        if (quietMode) {
            System.setOut(NULL_PRINT_STREAM);
            System.setErr(NULL_PRINT_STREAM);
        }

        try {
            if (matchSize != 0) {
                Match mc = new Match(rules, pp, "Test");
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                    iGame++;
                }
            } else if (numThreads > 1 && nGames > 1) {
                if (quietMode) {
                    System.setOut(ORIGINAL_OUT);
                    System.setErr(ORIGINAL_ERR);
                }
                simulateParallel(rules, playerConfigs, nGames, numThreads, outputGamelog, useSnapshot);
            } else {
                Match mc = new Match(rules, pp, "Test");
                for (int iGame = 0; iGame < nGames; iGame++) {
                    simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                }
            }
        } finally {
            System.setOut(ORIGINAL_OUT);
            System.setErr(ORIGINAL_ERR);
        }

        System.out.flush();
    }

    private static void simulateParallel(GameRules rules, List<PlayerConfig> playerConfigs,
                                         int nGames, int numThreads, boolean outputGamelog, boolean useSnapshot) {
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final AtomicInteger wins1 = new AtomicInteger(0);
        final AtomicInteger wins2 = new AtomicInteger(0);
        final AtomicInteger draws = new AtomicInteger(0);
        final AtomicInteger completed = new AtomicInteger(0);
        final long startTime = System.currentTimeMillis();
        final ProgressBar progress = new ProgressBar(ORIGINAL_ERR, nGames);

        System.setOut(NULL_PRINT_STREAM);
        System.setErr(NULL_PRINT_STREAM);

        List<Future<?>> futures = new ArrayList<>();

        for (int iGame = 0; iGame < nGames; iGame++) {
            final int gameNum = iGame;
            futures.add(executor.submit(() -> {
                try {
                    List<RegisteredPlayer> freshPlayers = new ArrayList<>();
                    for (PlayerConfig config : playerConfigs) {
                        freshPlayers.add(config.createRegisteredPlayer());
                    }
                    Match mc = new Match(rules, freshPlayers, "Test-" + gameNum);
                    GameResult result = simulateSingleMatchQuietNoSuppress(mc, gameNum, useSnapshot, false);

                    if (result.isDraw) {
                        draws.incrementAndGet();
                    } else if (result.winnerIndex == 0) {
                        wins1.incrementAndGet();
                    } else {
                        wins2.incrementAndGet();
                    }
                    completed.incrementAndGet();
                    progress.update(completed.get());
                } catch (Exception e) {
                    completed.incrementAndGet();
                    progress.update(completed.get());
                }
            }));
        }

        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                // Already handled
            }
        }

        executor.shutdown();
        progress.finish();

        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);

        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println("=== Simulation Summary ===");
        System.out.printf("Total games: %d%n", nGames);
        double[] ci1 = WilsonInterval.calculate95(wins1.get(), nGames);
        double[] ci2 = WilsonInterval.calculate95(wins2.get(), nGames);
        System.out.printf("Player 1 wins: %d (%s)%n", wins1.get(),
                WilsonInterval.format(100.0 * wins1.get() / nGames, ci1));
        System.out.printf("Player 2 wins: %d (%s)%n", wins2.get(),
                WilsonInterval.format(100.0 * wins2.get() / nGames, ci2));
        System.out.printf("Draws: %d (%.1f%%)%n", draws.get(), 100.0 * draws.get() / nGames);
        System.out.printf("Total time: %d ms (%.1f ms/game avg, %.1f games/sec)%n",
                totalTime, (double) totalTime / nGames, 1000.0 * nGames / totalTime);
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -B [B] -n [N] -m [M] -t [T] -p [P] -f [F] -q -s -j [J]");
        System.out.println("\tsim - stands for simulation mode");
        System.out.println("\tdeck1 (or deck2,...,X) - constructed deck name or filename (has to be quoted when contains multiple words)");
        System.out.println("\tdeck is treated as file if it ends with a dot followed by three numbers or letters");
        System.out.println("\tAbsolute paths (e.g., /path/to/deck.dck) are used directly without prepending base directory");
        System.out.println("\tD - absolute directory to load ALL decks from (batch mode for tournaments)");
        System.out.println("\tB - Base directory for relative deck paths (overrides default constructed/commander dirs)");
        System.out.println("\tN - number of games, defaults to 1 (Ignores match setting)");
        System.out.println("\tM - Play full match of X games, typically 1,3,5 games. (Optional, overrides N)");
        System.out.println("\tT - Type of tournament to run with all provided decks (Bracket, RoundRobin, Swiss)");
        System.out.println("\tP - Amount of players per match (used only with Tournaments, defaults to 2)");
        System.out.println("\tF - format of games, defaults to constructed");
        System.out.println("\tc - Clock flag. Set the maximum time in seconds before calling the match a draw, defaults to 120.");
        System.out.println("\tq - Quiet flag. Suppress all game logs, only show game results.");
        System.out.println("\ts - Snapshot flag. Enable experimental snapshot restore for faster AI simulation (2-3x speedup).");
        System.out.println("\tj - Jobs/threads flag. Number of parallel threads for batch simulation. Implies quiet mode (no game logs).");
        System.out.println("\tP1, P2, ... - AI profile for player 1, 2, etc. (Default, Cautious, Reckless, Experimental, Enhanced, Ascended, AlwaysPass)");
    }

    public static void simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog) {
        simulateSingleMatch(mc, iGame, outputGamelog, false);
    }

    public static void simulateSingleMatch(final Match mc, int iGame, boolean outputGamelog, boolean useSnapshot) {
        final StopWatch sw = new StopWatch();
        sw.start();

        final Game g1 = mc.createGame();
        g1.EXPERIMENTAL_RESTORE_SNAPSHOT = useSnapshot;

        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
                sw.stop();
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            ORIGINAL_OUT.println("Stopping slow match as draw");
        } catch (Exception | StackOverflowError e) {
            ORIGINAL_ERR.println("Game error: " + e.getMessage());
        } finally {
            if (sw.isStarted()) {
                sw.stop();
            }
            if (!g1.isGameOver()) {
                g1.setGameOver(GameEndReason.Draw);
            }
        }

        if (outputGamelog) {
            List<GameLogEntry> log = g1.getGameLog().getLogEntries(null);
            Collections.reverse(log);
            for (GameLogEntry l : log) {
                ORIGINAL_OUT.println(l);
            }
        }

        if (g1.getOutcome().isDraw()) {
            ORIGINAL_OUT.printf("Game %d: Draw (%d ms)%n", 1 + iGame, sw.getTime());
        } else {
            ORIGINAL_OUT.printf("Game %d: %s wins (%d ms)%n", 1 + iGame, g1.getOutcome().getWinningLobbyPlayer().getName(), sw.getTime());
        }
    }

    private static void simulateTournament(Map<String, List<String>> params, GameRules rules, boolean outputGamelog, Map<Integer, String> aiProfiles, boolean useSnapshot, String customDeckBaseDir) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType(), customDeckBaseDir);
                if (d == null) {
                    System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck, ", match cannot start"));
                    return;
                }

                deckGroup.addAiDeck(d);
                String profile = aiProfiles.getOrDefault(numPlayers, "");
                players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), profile), numPlayers));
                numPlayers++;
            }
        }

        if (params.containsKey("D")) {
            String foldName = params.get("D").get(0);
            File folder = new File(foldName);
            if (!folder.isDirectory()) {
                System.out.println("Directory not found - " + foldName);
            } else {
                for (File deck : folder.listFiles((dir, name) -> name.endsWith(".dck"))) {
                    Deck d = DeckSerializer.fromFile(deck);
                    if (d == null) {
                        System.out.println(TextUtil.concatNoSpace("Could not load deck - ", deck.getName(), ", match cannot start"));
                        return;
                    }
                    deckGroup.addAiDeck(d);
                    String profile = aiProfiles.getOrDefault(numPlayers, "");
                    players.add(new TournamentPlayer(GamePlayerUtil.createAiPlayer(d.getName(), profile), numPlayers));
                    numPlayers++;
                }
            }
        }

        if (numPlayers == 0) {
            System.out.println("No decks/Players found. Please try again.");
        }

        if ("bracket".equalsIgnoreCase(tournament)) {
            tourney = new TournamentBracket(players, matchPlayers);
        } else if ("roundrobin".equalsIgnoreCase(tournament)) {
            tourney = new TournamentRoundRobin(players, matchPlayers);
        } else if ("swiss".equalsIgnoreCase(tournament)) {
            tourney = new TournamentSwiss(players, matchPlayers);
        }
        if (tourney == null) {
            System.out.println("Failed to initialize tournament, bailing out");
            return;
        }

        tourney.initializeTournament();

        String lastWinner = "";
        int curRound = 0;
        System.out.println(TextUtil.concatNoSpace("Starting a ", tournament, " tournament with ",
                String.valueOf(numPlayers), " players over ",
                String.valueOf(tourney.getTotalRounds()), " rounds"));
        while (!tourney.isTournamentOver()) {
            if (tourney.getActiveRound() != curRound) {
                if (curRound != 0) {
                    System.out.println(TextUtil.concatNoSpace("End Round - ", String.valueOf(curRound)));
                }
                curRound = tourney.getActiveRound();
                System.out.println();
                System.out.println(TextUtil.concatNoSpace("Round ", String.valueOf(curRound), " Pairings:"));

                for (TournamentPairing pairing : tourney.getActivePairings()) {
                    System.out.println(pairing.outputHeader());
                }
                System.out.println();
            }

            TournamentPairing pairing = tourney.getNextPairing();
            List<RegisteredPlayer> regPlayers = AbstractTournament.registerTournamentPlayers(pairing, deckGroup);

            StringBuilder sb = new StringBuilder();
            sb.append("Round ").append(tourney.getActiveRound()).append(" - ");
            sb.append(pairing.outputHeader());
            System.out.println(sb.toString());

            if (!pairing.isBye()) {
                Match mc = new Match(rules, regPlayers, "TourneyMatch");

                int exceptions = 0;
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    try {
                        simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                        iGame++;
                    } catch (Exception e) {
                        exceptions++;
                        System.out.println(e.toString());
                        if (exceptions > 5) {
                            System.out.println("Exceeded number of exceptions thrown. Abandoning match...");
                            break;
                        } else {
                            System.out.println("Game threw exception. Abandoning game and continuing...");
                        }
                    }

                }
                LobbyPlayer winner = mc.getWinner().getPlayer();
                for (TournamentPlayer tp : pairing.getPairedPlayers()) {
                    if (winner.equals(tp.getPlayer())) {
                        pairing.setWinner(tp);
                        lastWinner = winner.getName();
                        System.out.println(TextUtil.concatNoSpace("Match Winner - ", lastWinner, "!"));
                        System.out.println();
                        break;
                    }
                }
            }

            tourney.reportMatchCompletion(pairing);
        }
        tourney.outputTournamentResults();
    }

    public static Match simulateOffthreadGame(List<Deck> decks, GameType format, int games) {
        return null;
    }

    private static Deck deckFromCommandLineParameter(String deckname, GameType type, String customBaseDir) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            // Looks like a file path
            List<String> pathsTried = new ArrayList<>();
            File f = new File(deckname);

            if (f.isAbsolute()) {
                pathsTried.add(f.getAbsolutePath());
            } else {
                String baseDir;
                if (customBaseDir != null && !customBaseDir.isEmpty()) {
                    baseDir = customBaseDir.endsWith(File.separator) ? customBaseDir : customBaseDir + File.separator;
                } else {
                    baseDir = type.equals(GameType.Commander) ?
                            ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR;
                }
                f = new File(baseDir + deckname);
                pathsTried.add(f.getAbsolutePath());

                // Also try current working directory
                if (!f.exists()) {
                    File cwdFile = new File(System.getProperty("user.dir"), deckname);
                    pathsTried.add(cwdFile.getAbsolutePath());
                    if (cwdFile.exists()) {
                        return DeckSerializer.fromFile(cwdFile);
                    }
                }
            }

            if (!f.exists()) {
                ORIGINAL_ERR.println("Error: Deck file not found - " + deckname);
                ORIGINAL_ERR.println("  Paths tried:");
                for (String path : pathsTried) {
                    ORIGINAL_ERR.println("    - " + path);
                }
                // Suggest .dck files in the directory
                File parentDir = f.getParentFile();
                if (parentDir != null && parentDir.isDirectory()) {
                    File[] dckFiles = parentDir.listFiles((dir, name) -> name.endsWith(".dck"));
                    if (dckFiles != null && dckFiles.length > 0) {
                        List<String> suggestions = findSimilarNames(deckname, dckFiles);
                        if (!suggestions.isEmpty()) {
                            ORIGINAL_ERR.println("  Did you mean:");
                            for (String s : suggestions) {
                                ORIGINAL_ERR.println("    - " + s);
                            }
                        }
                    }
                }
                return null;
            }

            return DeckSerializer.fromFile(f);
        }

        // Name-based lookup
        IStorage<Deck> deckStore;
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        Deck d = deckStore.get(deckname);
        if (d == null) {
            ORIGINAL_ERR.println("Error: Deck not found by name - " + deckname);
            // Suggest similar deck names
            Collection<String> allNames = deckStore.getItemNames();
            List<String> suggestions = findSimilarDeckNames(deckname, allNames);
            if (!suggestions.isEmpty()) {
                ORIGINAL_ERR.println("  Did you mean:");
                for (String s : suggestions) {
                    ORIGINAL_ERR.println("    - " + s);
                }
            }
            ORIGINAL_ERR.println("  Tip: Use a .dck file path for decks not in the store, or -B to set a base directory");
        }
        return d;
    }

    /**
     * Find similar file names using case-insensitive substring matching.
     */
    private static List<String> findSimilarNames(String target, File[] files) {
        String baseName = new File(target).getName().toLowerCase();
        // Strip extension for comparison
        String nameNoExt = baseName.contains(".") ? baseName.substring(0, baseName.lastIndexOf('.')) : baseName;

        List<String> matches = new ArrayList<>();
        for (File f : files) {
            String fn = f.getName().toLowerCase();
            String fnNoExt = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;
            if (fnNoExt.contains(nameNoExt) || nameNoExt.contains(fnNoExt) || editDistance(nameNoExt, fnNoExt) <= 3) {
                matches.add(f.getName());
                if (matches.size() >= 5) break;
            }
        }
        return matches;
    }

    /**
     * Find similar deck names from the deck store.
     */
    private static List<String> findSimilarDeckNames(String target, Collection<String> allNames) {
        String lowerTarget = target.toLowerCase();
        List<String> matches = new ArrayList<>();
        for (String name : allNames) {
            String lowerName = name.toLowerCase();
            if (lowerName.contains(lowerTarget) || lowerTarget.contains(lowerName) || editDistance(lowerTarget, lowerName) <= 3) {
                matches.add(name);
                if (matches.size() >= 5) break;
            }
        }
        return matches;
    }

    /**
     * Simple Levenshtein edit distance for fuzzy matching.
     */
    private static int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

}
