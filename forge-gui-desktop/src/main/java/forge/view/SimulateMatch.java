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

import forge.LobbyPlayer;
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
            // "sim" is in the 0th slot
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
            // Number of games should only be a single string
            nGames = Integer.parseInt(params.get("n").get(0));
        }

        int matchSize = 0;
        if (params.containsKey("m")) {
            // Match size ("best of X games")
            matchSize = Integer.parseInt(params.get("m").get(0));
        }

        // Quiet mode: suppress game logs if -q flag OR -j flag is passed
        // Full game logging only in sequential mode without -q
        boolean quietMode = params.containsKey("q") || params.containsKey("j");
        boolean outputGamelog = !quietMode;

        GameType type = GameType.Constructed;
        if (params.containsKey("f")) {
            type = GameType.valueOf(WordUtil.capitalize(params.get("f").get(0)));
        }

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));

        // Parse per-player AI profiles (-P1 Profile1 -P2 Profile2 etc.)
        Map<Integer, String> aiProfiles = new HashMap<>();
        for (int p = 1; p <= 8; p++) {
            if (params.containsKey("P" + p)) {
                aiProfiles.put(p - 1, params.get("P" + p).get(0).trim());
            }
        }

        if (matchSize != 0) {
            rules.setGamesPerMatch(matchSize);
        }

        if (params.containsKey("t")) {
            boolean useSnapshot = params.containsKey("s");
            simulateTournament(params, rules, outputGamelog, aiProfiles, useSnapshot);
            System.out.flush();
            return;
        }

        List<RegisteredPlayer> pp = new ArrayList<>();
        List<PlayerConfig> playerConfigs = new ArrayList<>();
        StringBuilder sb = new StringBuilder();

        int i = 1;

        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, type);
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

                // Store config for parallel execution (creates fresh players per game)
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
        }

        if (params.containsKey("c")) {
            rules.setSimTimeout(Integer.parseInt(params.get("c").get(0)));
        }

        // Enable experimental snapshot restore for faster simulation (2-3x speedup)
        boolean useSnapshot = params.containsKey("s");

        // Number of parallel threads for batch simulation
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

        // Suppress output early for quiet mode to catch any debug prints during game setup
        if (quietMode) {
            System.setOut(NULL_PRINT_STREAM);
            System.setErr(NULL_PRINT_STREAM);
        }

        try {
            if (matchSize != 0) {
                // Match mode - must be sequential (games depend on each other)
                Match mc = new Match(rules, pp, "Test");
                int iGame = 0;
                while (!mc.isMatchOver()) {
                    // play games until the match ends
                    simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                    iGame++;
                }
            } else if (numThreads > 1 && nGames > 1) {
                // Parallel batch mode - run independent games in parallel
                // Note: simulateParallel handles its own suppression
                if (quietMode) {
                    // Restore before parallel (it will re-suppress)
                    System.setOut(ORIGINAL_OUT);
                    System.setErr(ORIGINAL_ERR);
                }
                simulateParallel(rules, playerConfigs, nGames, numThreads, outputGamelog, useSnapshot);
            } else {
                // Sequential batch mode
                Match mc = new Match(rules, pp, "Test");
                for (int iGame = 0; iGame < nGames; iGame++) {
                    simulateSingleMatch(mc, iGame, outputGamelog, useSnapshot);
                }
            }
        } finally {
            // Always restore stdout/stderr
            System.setOut(ORIGINAL_OUT);
            System.setErr(ORIGINAL_ERR);
        }

        System.out.flush();
    }

    /**
     * Runs multiple independent games in parallel using a thread pool.
     * Each game is completely independent, so this provides near-linear speedup.
     */
    private static void simulateParallel(GameRules rules, List<PlayerConfig> playerConfigs,
                                         int nGames, int numThreads, boolean outputGamelog, boolean useSnapshot) {
        final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        final AtomicInteger wins1 = new AtomicInteger(0);
        final AtomicInteger wins2 = new AtomicInteger(0);
        final AtomicInteger draws = new AtomicInteger(0);
        final AtomicInteger completed = new AtomicInteger(0);
        final long startTime = System.currentTimeMillis();

        // Suppress both stdout and stderr for all threads to avoid ALL debug print pollution
        // Use ORIGINAL_OUT directly for intentional output (progress, summary)
        System.setOut(NULL_PRINT_STREAM);
        System.setErr(NULL_PRINT_STREAM);

        List<Future<?>> futures = new ArrayList<>();

        for (int iGame = 0; iGame < nGames; iGame++) {
            final int gameNum = iGame;
            futures.add(executor.submit(() -> {
                try {
                    // Create fresh RegisteredPlayer objects for each game to ensure thread safety
                    List<RegisteredPlayer> freshPlayers = new ArrayList<>();
                    for (PlayerConfig config : playerConfigs) {
                        freshPlayers.add(config.createRegisteredPlayer());
                    }
                    Match mc = new Match(rules, freshPlayers, "Test-" + gameNum);
                    GameResult result = simulateSingleMatchQuietNoSuppress(mc, gameNum, useSnapshot);

                    synchronized (ORIGINAL_OUT) {
                        if (result.isDraw) {
                            draws.incrementAndGet();
                        } else if (result.winnerIndex == 0) {
                            wins1.incrementAndGet();
                        } else {
                            wins2.incrementAndGet();
                        }
                        int done = completed.incrementAndGet();
                        // Only show progress every 10 games or at completion
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

        // Wait for all games to complete
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (Exception e) {
                // Already handled in the task
            }
        }

        executor.shutdown();

        // Restore stdout and stderr before printing summary
        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);

        long totalTime = System.currentTimeMillis() - startTime;

        // Print summary
        System.out.println();
        System.out.println("=== Simulation Summary ===");
        System.out.printf("Total games: %d%n", nGames);
        System.out.printf("Player 1 wins: %d (%.1f%%)%n", wins1.get(), 100.0 * wins1.get() / nGames);
        System.out.printf("Player 2 wins: %d (%.1f%%)%n", wins2.get(), 100.0 * wins2.get() / nGames);
        System.out.printf("Draws: %d (%.1f%%)%n", draws.get(), 100.0 * draws.get() / nGames);
        System.out.printf("Total time: %d ms (%.1f ms/game avg, %.1f games/sec)%n",
                totalTime, (double) totalTime / nGames, 1000.0 * nGames / totalTime);
    }

    /**
     * Simple result holder for parallel game execution.
     */
    private static class GameResult {
        boolean isDraw;
        int winnerIndex;
        String winnerName;
        long timeMs;
    }

    /**
     * Simulates a single game without outputting the game log (for parallel execution).
     * Suppresses all stdout during game execution to avoid debug print pollution.
     */
    private static GameResult simulateSingleMatchQuiet(final Match mc, int iGame, boolean useSnapshot) {
        final GameResult result = new GameResult();
        final long startTime = System.currentTimeMillis();

        final Game g1 = mc.createGame();
        g1.EXPERIMENTAL_RESTORE_SNAPSHOT = useSnapshot;

        // Suppress stdout during game execution to avoid debug prints
        System.setOut(NULL_PRINT_STREAM);
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> {
                mc.startGame(g1);
            }, mc.getRules().getSimTimeout(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Timeout - treat as draw
        } catch (Exception | StackOverflowError e) {
            // Error - treat as draw
        } finally {
            // Always restore stdout
            System.setOut(ORIGINAL_OUT);
            if (!g1.isGameOver()) {
                g1.setGameOver(GameEndReason.Draw);
            }
        }

        result.timeMs = System.currentTimeMillis() - startTime;
        result.isDraw = g1.getOutcome().isDraw();

        if (!result.isDraw) {
            result.winnerName = g1.getOutcome().getWinningLobbyPlayer().getName();
            // Determine winner index (0 or 1)
            result.winnerIndex = g1.getOutcome().getWinningLobbyPlayer().getName().contains("(1)") ? 0 : 1;
        }

        return result;
    }

    /**
     * Simulates a single game without outputting the game log (for parallel execution).
     * Does NOT suppress stdout - caller is responsible for suppression.
     * Used when parent method already handles stdout suppression for all threads.
     */
    private static GameResult simulateSingleMatchQuietNoSuppress(final Match mc, int iGame, boolean useSnapshot) {
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

        if (!result.isDraw) {
            result.winnerName = g1.getOutcome().getWinningLobbyPlayer().getName();
            // Determine winner index (0 or 1)
            result.winnerIndex = g1.getOutcome().getWinningLobbyPlayer().getName().contains("(1)") ? 0 : 1;
        }

        return result;
    }

    private static void argumentHelp() {
        System.out.println("Syntax: forge.exe sim -d <deck1[.dck]> ... <deckX[.dck]> -D [D] -n [N] -m [M] -t [T] -p [P] -f [F] -q -s -j [J]");
        System.out.println("\tsim - stands for simulation mode");
        System.out.println("\tdeck1 (or deck2,...,X) - constructed deck name or filename (has to be quoted when contains multiple words)");
        System.out.println("\tdeck is treated as file if it ends with a dot followed by three numbers or letters");
        System.out.println("\tD - absolute directory to load decks from");
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
        // Enable experimental snapshot restore for faster AI simulation
        g1.EXPERIMENTAL_RESTORE_SNAPSHOT = useSnapshot;

        // will run match in the same thread
        // Note: stdout/stderr suppression is handled by caller in quiet mode
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
            // Verbose mode: show all game log entries (only when NOT in quiet mode)
            List<GameLogEntry> log = g1.getGameLog().getLogEntries(null);
            Collections.reverse(log);
            for (GameLogEntry l : log) {
                ORIGINAL_OUT.println(l);
            }
        }

        // Always show game result (brief summary) - use ORIGINAL_OUT to bypass any suppression
        if (g1.getOutcome().isDraw()) {
            ORIGINAL_OUT.printf("Game %d: Draw (%d ms)%n", 1 + iGame, sw.getTime());
        } else {
            ORIGINAL_OUT.printf("Game %d: %s wins (%d ms)%n", 1 + iGame, g1.getOutcome().getWinningLobbyPlayer().getName(), sw.getTime());
        }
    }

    private static void simulateTournament(Map<String, List<String>> params, GameRules rules, boolean outputGamelog, Map<Integer, String> aiProfiles, boolean useSnapshot) {
        String tournament = params.get("t").get(0);
        AbstractTournament tourney = null;
        int matchPlayers = params.containsKey("p") ? Integer.parseInt(params.get("p").get(0)) : 2;

        DeckGroup deckGroup = new DeckGroup("SimulatedTournament");
        List<TournamentPlayer> players = new ArrayList<>();
        int numPlayers = 0;
        if (params.containsKey("d")) {
            for (String deck : params.get("d")) {
                Deck d = deckFromCommandLineParameter(deck, rules.getGameType());
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
            // Direc
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
                    // play games until the match ends
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

    private static Deck deckFromCommandLineParameter(String deckname, GameType type) {
        int dotpos = deckname.lastIndexOf('.');
        if (dotpos > 0 && dotpos == deckname.length() - 4) {
            String baseDir = type.equals(GameType.Commander) ?
                    ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR;

            File f = new File(baseDir + deckname);
            if (!f.exists()) {
                System.out.println("No deck found in " + baseDir);
            }

            return DeckSerializer.fromFile(f);
        }

        IStorage<Deck> deckStore = null;

        // Add other game types here...
        if (type.equals(GameType.Commander)) {
            deckStore = FModel.getDecks().getCommander();
        } else {
            deckStore = FModel.getDecks().getConstructed();
        }

        return deckStore.get(deckname);
    }

}