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
import forge.cli.ExitCode;
import forge.cli.SimCommand;
import forge.cli.json.SimulationResult;
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
     * Configuration for a player in parallel simulation.
     * Stores the original deck and player info so fresh RegisteredPlayer objects
     * can be created for each parallel game (avoiding thread-safety issues).
     */
    private static class PlayerConfig {
        final Deck deck;
        final String name;
        final String aiProfile;
        final GameType gameType;

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
            rp.setPlayer(GamePlayerUtil.createAiPlayer(name, aiProfile));
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
    public static int simulate(SimCommand cmd) {
        // Redirect initialization output to stderr
        System.setOut(ORIGINAL_ERR);

        try {
            FModel.initialize(null, null);
        } finally {
            System.setOut(ORIGINAL_OUT);
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
        boolean outputGamelog = !quietMode && !cmd.isJsonOutput();

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
            playerConfigs.add(new PlayerConfig(d, name, profile, type));

            // Also create RegisteredPlayer for sequential execution
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
        // - json mode: suppress to prevent debug prints from corrupting JSON on stdout
        if (quietMode || cmd.isJsonOutput()) {
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
                    GameResult result = simulateSingleMatchWithResult(mc, iGame, outputGamelog, useSnapshot, cmd.isJsonOutput(), cmd.isJsonOutput());
                    allResults.add(result);
                    iGame++;
                }
            } else if (numThreads > 1 && nGames > 1) {
                // Parallel batch mode — restore streams since simulateParallelWithResults
                // manages its own suppression
                if (quietMode || cmd.isJsonOutput()) {
                    System.setOut(ORIGINAL_OUT);
                    System.setErr(ORIGINAL_ERR);
                }
                allResults = simulateParallelWithResults(rules, playerConfigs, nGames, numThreads, outputGamelog, useSnapshot, cmd.isJsonOutput());
            } else {
                // Sequential batch mode
                Match mc = new Match(rules, pp, "Test");
                for (int iGame = 0; iGame < nGames; iGame++) {
                    GameResult result = simulateSingleMatchWithResult(mc, iGame, outputGamelog, useSnapshot, cmd.isJsonOutput(), cmd.isJsonOutput());
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

                    synchronized (ORIGINAL_OUT) {
                        if (result.isDraw) {
                            draws.incrementAndGet();
                        } else if (result.winnerIndex == 0) {
                            wins1.incrementAndGet();
                        } else {
                            wins2.incrementAndGet();
                        }
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == nGames) {
                            ORIGINAL_ERR.printf("Progress: %d/%d games completed%n", done, nGames);
                        }
                    }
                } catch (Exception e) {
                    synchronized (ORIGINAL_OUT) {
                        ORIGINAL_ERR.printf("Game %d: Error - %s%n", gameNum + 1, e.getMessage());
                        completed.incrementAndGet();
                    }
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

        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);

        long totalTime = System.currentTimeMillis() - startTime;

        // Print summary — to stderr in JSON mode to keep stdout clean
        PrintStream summaryStream = collectLog ? ORIGINAL_ERR : System.out;
        summaryStream.println();
        summaryStream.println("=== Simulation Summary ===");
        summaryStream.printf("Total games: %d%n", nGames);
        summaryStream.printf("Player 1 wins: %d (%.1f%%)%n", wins1.get(), 100.0 * wins1.get() / nGames);
        summaryStream.printf("Player 2 wins: %d (%.1f%%)%n", wins2.get(), 100.0 * wins2.get() / nGames);
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

                    synchronized (ORIGINAL_OUT) {
                        if (result.isDraw) {
                            draws.incrementAndGet();
                        } else if (result.winnerIndex == 0) {
                            wins1.incrementAndGet();
                        } else {
                            wins2.incrementAndGet();
                        }
                        int done = completed.incrementAndGet();
                        if (done % 10 == 0 || done == nGames) {
                            ORIGINAL_OUT.printf("Progress: %d/%d games completed%n", done, nGames);
                        }
                    }
                } catch (Exception e) {
                    synchronized (ORIGINAL_OUT) {
                        ORIGINAL_OUT.printf("Game %d: Error - %s%n", gameNum + 1, e.getMessage());
                        completed.incrementAndGet();
                    }
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

        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);

        long totalTime = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("=== Simulation Summary ===");
        System.out.printf("Total games: %d%n", nGames);
        System.out.printf("Player 1 wins: %d (%.1f%%)%n", wins1.get(), 100.0 * wins1.get() / nGames);
        System.out.printf("Player 2 wins: %d (%.1f%%)%n", wins2.get(), 100.0 * wins2.get() / nGames);
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
            File f = new File(deckname);

            if (!f.isAbsolute()) {
                String baseDir;
                if (customBaseDir != null && !customBaseDir.isEmpty()) {
                    baseDir = customBaseDir.endsWith(File.separator) ? customBaseDir : customBaseDir + File.separator;
                } else {
                    baseDir = type.equals(GameType.Commander) ?
                            ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR;
                }
                f = new File(baseDir + deckname);
            }

            if (!f.exists()) {
                System.out.println("No deck found at " + f.getAbsolutePath());
                return null;
            }

            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore = null;

        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        return deckStore.get(deckname);
    }

}
