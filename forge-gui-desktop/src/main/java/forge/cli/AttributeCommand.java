package forge.cli;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.time.StopWatch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import forge.LobbyPlayer;
import forge.StaticData;
import forge.ai.ForcedActionController;
import forge.ai.ForcedLobbyPlayer;
import forge.ai.GameState;
import forge.ai.StateDumpChecksum;
import forge.cli.json.AttributionResult;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.IPaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.BuildInfo;
import forge.util.MyRandom;
import forge.util.ThreadUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Replays one dumped board position to completion, many times, and writes down what happened.
 *
 * <p>The question this answers is not "what does the AI do here" — the decision trace already
 * says that — but "does what the AI does here decide the game". So a position the AI refused a
 * card in is replayed twice over: once with the AI playing as it always does, and once with
 * that one refusal overruled. The difference between the two arms' win rates is what the
 * refusal is worth. This command produces one position's raw rows; pooling positions and
 * putting an interval around the difference is the caller's job.
 *
 * <p>Every rollout is a <b>fresh game</b> built from the dumped text, never a copy of a live
 * game: puzzle rules, two empty decks with no opening hand, and the board applied inside the
 * start-game hook. Three details of that recipe are not optional, and each one silently
 * produces plausible nonsense when it is got wrong:
 *
 * <ul>
 *   <li>The board must be applied <b>inside</b> the start-game hook. Applying it after
 *       {@code startGame} returns throws "Turns already started"; applying it before means
 *       the engine overwrites it.</li>
 *   <li>The game runs on its own thread, and Forge's RNG is thread-local, so the seed is set
 *       as the very first statement on that thread. Set anywhere else it lands on a
 *       {@link Random} nothing will ever read, and the run is silently unseeded.</li>
 *   <li>Both libraries are re-shuffled after the restore. The format records library order
 *       exactly, so without this every rollout would draw the same cards in the same order —
 *       one fixed future replayed K times, rather than K samples of the position.</li>
 * </ul>
 *
 * <p>And after every restore the position is compared, field by field, against the file it
 * came from: life totals, per-zone card counts, turn, phase and active player. A mismatch
 * stops the run. Numbers about a board nobody chose are worse than no numbers.
 *
 * <p>That comparison proves the game matches the file, which is not the same as the file
 * matching the dump that was taken. A dump cut short mid-write restores cleanly into a
 * different position and agrees with itself the whole way. So the file's own
 * {@link StateDumpChecksum} line is checked first, before anything is played; a file whose
 * checksum is present and wrong is refused, and one with no checksum at all — a hand-built
 * board — is accepted with a note saying so.
 */
@Command(
    name = "attribute",
    description = "Replay a dumped board position to completion and record who won",
    mixinStandardHelpOptions = true,
    sortOptions = false
)
public class AttributeCommand implements Callable<Integer> {

    private static final PrintStream ORIGINAL_OUT = System.out;
    private static final PrintStream ORIGINAL_ERR = System.err;

    private static final PrintStream NULL_PRINT_STREAM = new PrintStream(new OutputStream() {
        @Override public void write(int b) { }
        @Override public void write(byte[] b) { }
        @Override public void write(byte[] b, int off, int len) { }
    });

    /** Zone names as {@link GameState} writes them, used to tell a zone line from any other. */
    private static final List<String> ZONE_KEYS = Collections.unmodifiableList(java.util.Arrays.asList(
            "battlefield", "hand", "graveyard", "library", "exile", "command", "sideboard"));

    /** Which arms a run has, and what each one does. */
    public enum Mode {
        /** One arm, the AI playing as it always does. */
        natural,
        /** Two arms: natural, and the same position with the named action forced. */
        ab,
        /** Two natural arms on disjoint seeds — a bias check that should come back at zero. */
        aa
    }

    // === Position ===

    @Option(
        names = {"--state"},
        required = true,
        description = "Board dump to replay, in GameState text (written by the AI decision trace).",
        paramLabel = "FILE"
    )
    private File stateFile;

    @Option(
        names = {"--seat"},
        defaultValue = "0",
        description = "Seat the position was dumped for, 0-based. The forced arm overrules this seat. Default: ${DEFAULT-VALUE}",
        paramLabel = "N"
    )
    private int seat;

    // === Run shape ===

    @Option(
        names = {"--mode"},
        defaultValue = "natural",
        description = "Which arms to run. Valid: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}",
        paramLabel = "MODE"
    )
    private Mode mode;

    @Option(
        names = {"-k", "--rollouts"},
        defaultValue = "0",
        description = "Games to play per arm. 0 only verifies that the position restores. Default: ${DEFAULT-VALUE}",
        paramLabel = "K"
    )
    private int rollouts;

    @Option(
        names = {"--seed"},
        defaultValue = "0",
        description = "Base seed. Every rollout's seed derives from it, and the two arms never share one. Default: ${DEFAULT-VALUE}",
        paramLabel = "S"
    )
    private long seed;

    @Option(
        names = {"-c", "--clock"},
        defaultValue = "120",
        description = "Seconds one game may take before it is abandoned and booked as a draw. Default: ${DEFAULT-VALUE}",
        paramLabel = "SECS"
    )
    private int clock;

    @Option(
        names = {"-P", "--profile"},
        defaultValue = "Default",
        description = "AI profile both seats play with. Default: ${DEFAULT-VALUE}",
        paramLabel = "PROFILE"
    )
    private String profile;

    @Option(
        names = {"--force"},
        description = "Action the forced arm plays whatever the AI thinks of it, as \"Card Name|description-substring\".",
        paramLabel = "SPEC"
    )
    private String force;

    @Option(
        names = {"--force-times"},
        defaultValue = "1",
        description = "Times the forced arm plays the action in its turn. One asks what taking the action"
                + " was worth; more asks what spending the turn on it was worth. Default: ${DEFAULT-VALUE}",
        paramLabel = "N"
    )
    private int forceTimes;

    // === Output ===

    @Option(
        names = {"-o", "--out"},
        description = "Write the result JSON here. Without it the JSON goes to stdout.",
        paramLabel = "FILE"
    )
    private File out;

    /** The state file, blank lines dropped, because the parser reads them as a character. */
    private List<String> stateLines;
    /** What the file says the position is, as a comparable field map. */
    private Map<String, String> expectedFields;
    /** The turn the position starts in, which is the turn the forced arm is armed for. */
    private int restoredTurn;

    @Override
    public Integer call() {
        if (seat < 0 || seat > 1) {
            ORIGINAL_ERR.println("Error: --seat must be 0 or 1; a dumped position is a two-player game.");
            return ExitCode.ARGS_ERROR;
        }
        if (rollouts < 0) {
            ORIGINAL_ERR.println("Error: --rollouts cannot be negative.");
            return ExitCode.ARGS_ERROR;
        }
        if (clock <= 0) {
            ORIGINAL_ERR.println("Error: --clock must be a positive number of seconds.");
            return ExitCode.ARGS_ERROR;
        }
        if (mode == Mode.ab && (force == null || force.trim().isEmpty())) {
            ORIGINAL_ERR.println("Error: --mode ab needs --force \"Card Name|description-substring\"; "
                    + "without it both arms would be the same arm.");
            return ExitCode.ARGS_ERROR;
        }
        if (mode != Mode.ab && force != null && !force.trim().isEmpty()) {
            ORIGINAL_ERR.println("Error: --force only has an arm to act in under --mode ab.");
            return ExitCode.ARGS_ERROR;
        }
        if (forceTimes < 1 || forceTimes > ForcedActionController.MAX_ATTEMPTS) {
            ORIGINAL_ERR.println("Error: --force-times must be between 1 and "
                    + ForcedActionController.MAX_ATTEMPTS + ".");
            return ExitCode.ARGS_ERROR;
        }
        if (profile == null || profile.trim().isEmpty()) {
            profile = "Default";
        }
        profile = profile.trim();
        if (!new File(ForgeConstants.AI_PROFILE_DIR, profile + ".ai").isFile()) {
            ORIGINAL_ERR.println("Error: unknown AI profile '" + profile + "'.");
            ORIGINAL_ERR.println("Run 'sim --list-profiles' to see what is available in "
                    + ForgeConstants.AI_PROFILE_DIR);
            return ExitCode.ARGS_ERROR;
        }

        final String text;
        try {
            text = new String(Files.readAllBytes(stateFile.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ORIGINAL_ERR.println("Error: could not read the state file " + stateFile + " - " + e.getMessage());
            return ExitCode.ARGS_ERROR;
        }
        // Before anything else: is this the board that was written? The restore check below
        // compares the game against the file, which cannot notice that the file itself is
        // no longer the dump — a truncated one restores cleanly into a different position
        // and agrees with itself all the way through.
        final String checksumProblem = StateDumpChecksum.problem(text);
        if (checksumProblem != null) {
            ORIGINAL_ERR.println("Error: " + stateFile + " - " + checksumProblem);
            return ExitCode.ARGS_ERROR;
        }
        if (StateDumpChecksum.declaredIn(text) == null) {
            ORIGINAL_ERR.println("Note: " + stateFile.getName() + " has no '"
                    + StateDumpChecksum.HEADER_PREFIX + "' line, so it is taken on trust. Every dump"
                    + " written by the AI decision trace carries one; a hand-built board does not.");
        }

        stateLines = readableLines(text);
        expectedFields = summarize(text);
        if (expectedFields.isEmpty()) {
            ORIGINAL_ERR.println("Error: " + stateFile + " holds no board at all - nothing to replay.");
            return ExitCode.ARGS_ERROR;
        }
        restoredTurn = intOrZero(expectedFields.get("turn"));
        warnIfSeatDisagrees(text);

        // Initialization is chatty and none of it belongs in the result stream.
        System.setOut(NULL_PRINT_STREAM);
        System.setErr(NULL_PRINT_STREAM);
        try {
            FModel.initialize(null, null);
        } finally {
            System.setOut(ORIGINAL_OUT);
            System.setErr(ORIGINAL_ERR);
        }

        final AttributionResult result = new AttributionResult();
        result.version = BuildInfo.getVersionString();
        result.header = header();

        ORIGINAL_ERR.println("Attribution: " + stateFile.getName() + ", seat " + seat
                + ", turn " + restoredTurn + ", mode " + mode
                + ", " + rollouts + " rollouts per arm, clock " + clock + "s, profile " + profile
                + (mode == Mode.ab ? ", forcing " + forceTimes + "x" : ""));

        // Play nothing until the restored position has been shown to be the position the file
        // describes. Everything downstream is conditional on this having passed.
        System.setOut(NULL_PRINT_STREAM); // the game log is not the result
        final Replay check;
        try {
            check = runOne("verify", 0, seed, false, true);
        } finally {
            restoreOut();
        }
        result.verification = verification(check);
        if (!result.verification.passed) {
            for (final String mismatch : result.verification.mismatches) {
                ORIGINAL_ERR.println("Restore mismatch: " + mismatch);
            }
            ORIGINAL_ERR.println("Refusing to replay " + stateFile.getName()
                    + ": the restored board is not the board the file describes.");
            write(result);
            return ExitCode.RUNTIME_ERROR;
        }
        ORIGINAL_ERR.println("Restore verified: " + result.verification.checkedFields.size()
                + " fields match the state file.");

        if (rollouts == 0) {
            write(result);
            return ExitCode.SUCCESS;
        }

        System.setOut(NULL_PRINT_STREAM);
        try {
            // Interleaved, not one arm's block after the other's. Anything that drifts over
            // the length of a run — JIT warmup, heap growth, the machine heating up, another
            // position's JVM finishing beside this one — lands entirely on the difference
            // between the arms if each arm owns a contiguous block of wall-clock. It reaches
            // the numbers through the per-game clock: a game abandoned at --clock seconds is
            // booked as a draw, so a few percent more timeouts in the second block is a few
            // percent shaved off that arm's win rate, well inside the pre-registered timeout
            // gate and invisible in the output. Seeds derive from the arm and the index only
            // (see seedFor), so the order games are played in changes nothing else.
            for (int i = 0; i < rollouts; i++) {
                for (final Arm arm : arms()) {
                    final long rolloutSeed = seedFor(arm, i);
                    final Replay rollout = runOne(arm.name, i, rolloutSeed, arm.forcing, false);
                    // A crash is recorded on the row (see Rollout.failure) rather than ending
                    // the run: one game dying of a rules-engine bug is a missing result, and
                    // the caller drops it from the arm's denominator and reports how many
                    // there were. A mismatch is different in kind — the board that landed is
                    // not the board the file describes, so every later rollout of this
                    // position would be about the same wrong board. That stops the run.
                    if (!rollout.mismatches.isEmpty()) {
                        result.verification.passed = false;
                        result.verification.mismatches.addAll(rollout.mismatches);
                        restoreOut();
                        ORIGINAL_ERR.println("Refusing to continue: rollout " + arm.name + " " + i
                                + " restored a different board than the file describes.");
                        write(result);
                        return ExitCode.RUNTIME_ERROR;
                    }
                    result.rollouts.add(rollout.row);
                    report(rollout);
                }
            }
        } finally {
            restoreOut();
        }

        write(result);
        return ExitCode.SUCCESS;
    }

    // ------------------------------------------------------------------
    // Arms and seeds
    // ------------------------------------------------------------------

    /** One arm of a run: what it is called, whether it forces, and which seed stream it owns. */
    private static final class Arm {
        final String name;
        final boolean forcing;
        final int stream;

        Arm(final String name, final boolean forcing, final int stream) {
            this.name = name;
            this.forcing = forcing;
            this.stream = stream;
        }
    }

    private List<Arm> arms() {
        final List<Arm> arms = new ArrayList<>();
        arms.add(new Arm("natural", false, 0));
        if (mode == Mode.ab) {
            arms.add(new Arm("forced", true, 1));
        } else if (mode == Mode.aa) {
            arms.add(new Arm("natural-b", false, 1));
        }
        return arms;
    }

    /**
     * The seed for one rollout. Arm 0 takes the even offsets from the base seed and arm 1 the
     * odd ones, so no seed is ever used by both arms.
     *
     * <p>That is not tidiness. Two arms sharing a seed would replay the same game against
     * itself wherever the forcing changed nothing, and the interval the caller puts around
     * the difference assumes the arms are independent samples. Disjoint streams make that
     * assumption a fact of the design rather than a claim about how the games correlate.
     */
    private long seedFor(final Arm arm, final int index) {
        return seed + 2L * index + arm.stream;
    }

    // ------------------------------------------------------------------
    // One rollout
    // ------------------------------------------------------------------

    /** What one replayed game produced: its row, any restore mismatch, any crash. */
    public static final class Replay {
        /** The row this game contributes to the result file. */
        public AttributionResult.Rollout row;
        /** Every field of the position the restored game failed to reproduce. */
        public List<String> mismatches = new ArrayList<>();
        /** What the game died of, or null when it finished. */
        public String failure;
    }

    /**
     * Every reason not to trust this restored position, empty when there is none.
     *
     * <p>A restore can go wrong in two ways and both mean the same thing: the position is not
     * the one the file describes. It can come back different — a card short, a life total
     * unset — or it can fail to come back at all, which is what an already-lost player does to
     * it. Both are refusals, so they are counted in one place rather than at each call site.
     */
    public static List<String> refusals(final Replay check) {
        final List<String> reasons = new ArrayList<>(check.mismatches);
        if (check.failure != null) {
            reasons.add("the restore did not finish: " + check.failure);
        }
        return reasons;
    }

    /**
     * Restore a board written in {@link GameState} text into a fresh game exactly the way the
     * command does, and either only check that it landed ({@code verifyOnly}) or play it out.
     *
     * <p>Here so that the recipe can be exercised in one process rather than only through a
     * subprocess. The card database has to be initialized already.
     */
    public static Replay replay(final String stateText, final String profile, final int clock,
            final long seed, final boolean verifyOnly) {
        final AttributeCommand command = new AttributeCommand();
        command.stateLines = readableLines(stateText);
        command.expectedFields = summarize(stateText);
        command.restoredTurn = intOrZero(command.expectedFields.get("turn"));
        command.profile = profile;
        command.clock = clock;
        command.seat = 0;
        command.mode = Mode.natural;
        return command.runOne("verify", 0, seed, false, verifyOnly);
    }

    /**
     * Build a fresh game from the dumped board and, unless {@code verifyOnly}, play it out.
     *
     * @param verifyOnly stop as soon as the restore has been checked, without playing
     */
    private Replay runOne(final String armName, final int index, final long rolloutSeed,
            final boolean forcing, final boolean verifyOnly) {
        final Replay outcome = new Replay();
        final AttributionResult.Rollout row = new AttributionResult.Rollout();
        outcome.row = row;
        row.arm = armName;
        row.index = index;
        row.seed = rolloutSeed;

        final ForcedLobbyPlayer forcedSeat = forcing ? forcedSeat() : null;
        final List<RegisteredPlayer> seats = seats(forcedSeat);
        final GameRules rules = new GameRules(GameType.Puzzle);
        rules.setGamesPerMatch(1);
        rules.setSimTimeout(clock);
        final Match match = new Match(rules, seats, "Attribution");
        final Game game = match.createGame();
        // Snapshot restore stays off. It is a mid-game rollback path whose copies can desync
        // static abilities, and a rollout never rolls back, so it would buy a known fidelity
        // risk and a per-play copy cost for nothing.
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;

        final List<String> mismatches = Collections.synchronizedList(new ArrayList<>());
        final StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        try {
            runOnGameThread("Game-attribute-" + armName + "-" + index, clock, () -> {
                // FIRST statement on this thread, and it has to stay first: MyRandom is
                // thread-local and this is the thread the game runs on.
                MyRandom.setRandom(new Random(rolloutSeed));
                match.startGame(game, () -> restore(game, mismatches, verifyOnly));
            });
        } catch (TimeoutException e) {
            row.timedOut = true;
        } catch (Exception | StackOverflowError e) {
            outcome.failure = String.valueOf(e);
        } finally {
            if (stopWatch.isStarted()) {
                stopWatch.stop();
            }
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
        }

        outcome.mismatches.addAll(mismatches);
        row.timeMs = stopWatch.getTime();
        row.turns = game.getPhaseHandler().getTurn();
        row.isDraw = game.getOutcome() == null || game.getOutcome().isDraw();
        row.failure = outcome.failure;
        if (row.timedOut) {
            row.endReason = "Timeout";
        } else if (outcome.failure != null) {
            row.endReason = "Error";
        } else if (game.getOutcome() == null) {
            row.endReason = "None";
        } else {
            row.endReason = String.valueOf(game.getOutcome().getWinCondition());
        }
        if (!row.isDraw) {
            row.winnerIndex = winnerIndex(game, match);
        }
        if (forcedSeat != null && forcedSeat.getForcedController() != null) {
            final ForcedActionController controller = forcedSeat.getForcedController();
            row.forcedAttempts = controller.getAttempts();
            row.forcedOffers = controller.getOffers();
            row.forcedFirstPhase = controller.getFirstForcedPhase();
            row.taxonomy = controller.getOutcome().name();
        }
        return outcome;
    }

    /**
     * The start-game hook: put the dumped board into the fresh game, check it landed, and
     * shuffle both libraries so the rollout samples the position instead of replaying one
     * recorded future.
     *
     * <p>Runs on the game thread, which is what makes {@link GameState#applyToGame} apply
     * inline rather than hand the work to another thread and return before it is done.
     */
    private void restore(final Game game, final List<String> mismatches, final boolean verifyOnly) {
        if (!ThreadUtil.isGameThread()) {
            // GameState.applyToGame dispatches to the game thread pool when it is called from
            // anywhere else, so the hook would return with the board not yet applied and the
            // game would start on an empty table. Never let that happen quietly.
            throw new IllegalStateException(
                    "The rollout is not running on a game thread, so the board would be applied asynchronously");
        }
        final GameState state = newGameState();
        state.parse(stateLines);
        state.applyToGame(game);

        mismatches.addAll(compare(expectedFields, summarize(dump(game))));
        mismatches.addAll(stillPlayable(game));
        if (!mismatches.isEmpty() || verifyOnly) {
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
            return;
        }

        // The format records library order exactly and restoring never shuffles.
        for (final Player player : game.getPlayers()) {
            player.shuffle(null);
        }
    }

    // ------------------------------------------------------------------
    // Seats
    // ------------------------------------------------------------------

    private List<RegisteredPlayer> seats(final ForcedLobbyPlayer forcedSeat) {
        final List<RegisteredPlayer> seats = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final RegisteredPlayer registered = new RegisteredPlayer(new Deck());
            // The board supplies the hand; drawing an opening one would add cards the
            // position never had, on top of a library the dump already fixed.
            registered.setStartingHand(0);
            if (forcedSeat != null && i == seat) {
                registered.setPlayer(forcedSeat);
            } else {
                // Explicit avatar and sleeve indices, because the usual overload picks them
                // with MyRandom and would spend seeded randomness on cosmetics.
                registered.setPlayer(GamePlayerUtil.createAiPlayer("Ai(" + (i + 1) + ")", 0, 0, null, profile));
            }
            seats.add(registered);
        }
        return seats;
    }

    private ForcedLobbyPlayer forcedSeat() {
        final ForcedLobbyPlayer forced = new ForcedLobbyPlayer(
                "Ai(" + (seat + 1) + ")-forced", null, force.trim(), restoredTurn, forceTimes);
        forced.setAiProfile(profile);
        forced.setAvatarIndex(0);
        forced.setSleeveIndex(0);
        return forced;
    }

    private static Integer winnerIndex(final Game game, final Match match) {
        final LobbyPlayer winner = game.getOutcome().getWinningLobbyPlayer();
        final List<RegisteredPlayer> players = match.getPlayers();
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getPlayer().equals(winner)) {
                return i;
            }
        }
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i).getPlayer().getName().equals(winner.getName())) {
                return i;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Threading
    // ------------------------------------------------------------------

    /**
     * Run one game on its own thread, named so that Forge treats it as a game thread, and
     * give up on it after {@code seconds}.
     *
     * <p>This is {@code TimeLimitedCodeBlock} with one thing added: the thread's name. Forge
     * decides whether a caller may touch the game directly by looking at the thread name, and
     * a restore that lands on the wrong side of that test is applied on some other thread
     * after the hook has already returned.
     */
    private static void runOnGameThread(final String threadName, final int seconds, final Runnable body)
            throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                return new Thread(runnable, threadName);
            }
        });
        final Future<?> future = executor.submit(body);
        executor.shutdown(); // does not cancel the running task
        try {
            future.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ------------------------------------------------------------------
    // Restore verification
    // ------------------------------------------------------------------

    /**
     * The part of a board that a restore has to reproduce exactly, read out of
     * {@link GameState} text: turn, phase, active player, each player's life, and how many
     * cards each player has in each zone.
     *
     * <p>Deliberately counts rather than compares card by card. The point is to catch a
     * restore that lost or invented cards, and a count catches that; a full comparison would
     * also fail on details the format is allowed to normalize.
     */
    public static Map<String, String> summarize(final String stateText) {
        final Map<String, String> fields = new TreeMap<>();
        for (final String raw : stateText.split("\n")) {
            final String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '[') {
                continue;
            }
            final int equals = line.indexOf('=');
            if (equals < 1) {
                continue;
            }
            final String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(equals + 1).trim();
            if ("turn".equals(key)) {
                fields.put("turn", value);
            } else if ("activeplayer".equals(key)) {
                fields.put("activeplayer", value.toLowerCase(Locale.ROOT));
            } else if ("activephase".equals(key)) {
                fields.put("activephase", value.toUpperCase(Locale.ROOT));
            } else if (key.endsWith("life")) {
                fields.put(key, value);
            } else if (isZoneKey(key)) {
                fields.put(zoneKey(key), String.valueOf(countCards(value)));
            }
        }
        return fields;
    }

    /**
     * Every field of the file the restored game does not reproduce, one line each.
     *
     * <p>A zone the file never mentions is expected to be empty, because restoring clears
     * every zone before it fills the ones the file names.
     */
    public static List<String> compare(final Map<String, String> expected, final Map<String, String> actual) {
        final List<String> problems = new ArrayList<>();
        final Set<String> keys = new TreeSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());
        for (final String key : keys) {
            String want = expected.get(key);
            String got = actual.get(key);
            if (want == null) {
                if (!isZoneKey(key)) {
                    continue; // the file said nothing about it, so there is nothing to check
                }
                want = "0";
            }
            if (got == null) {
                got = isZoneKey(key) ? "0" : "(absent)";
            }
            if (!want.equals(got)) {
                problems.add(key + ": the state file says " + want + ", the restored game has " + got);
            }
        }
        return problems;
    }

    /** Is this the key of a zone line, e.g. {@code p0library}? */
    private static boolean isZoneKey(final String key) {
        return zoneKey(key) != null;
    }

    /** The canonical form of a zone key, or null when the key names no zone. */
    private static String zoneKey(final String key) {
        if (key.endsWith("play")) {
            // "play" is the old spelling of "battlefield"; both restore to the same zone, so
            // they have to compare as the same field.
            return key.substring(0, key.length() - "play".length()) + "battlefield";
        }
        for (final String zone : ZONE_KEYS) {
            if (key.endsWith(zone)) {
                return key;
            }
        }
        return null;
    }

    /**
     * Why the restored position is not a game anyone can still play, if it is not.
     *
     * <p>A position that is already decided would hand every rollout the same free result and
     * the arms would differ by nothing. It is also what an edited life total usually produces:
     * a player written down at zero or less has already lost, whatever the rest of the file
     * says, so the numbers would be about a game that ended before the first decision.
     */
    private static List<String> stillPlayable(final Game game) {
        final List<String> problems = new ArrayList<>();
        for (final Player player : game.getPlayers()) {
            if (player.getLife() <= 0) {
                problems.add("p" + game.getPlayers().indexOf(player) + "life: the restored player is at "
                        + player.getLife() + " life, so the position is already decided");
            }
        }
        if (game.isGameOver()) {
            problems.add("the restored position is already over ("
                    + (game.getOutcome() == null ? "no outcome" : game.getOutcome().getWinCondition()) + ")");
        }
        return problems;
    }

    private static int countCards(final String zoneLine) {
        if (zoneLine.isEmpty()) {
            return 0;
        }
        int cards = 0;
        for (final String entry : zoneLine.split(";")) {
            if (!entry.trim().isEmpty()) {
                cards++;
            }
        }
        return cards;
    }

    private static String dump(final Game game) {
        final GameState state = newGameState();
        state.initFromGame(game);
        return state.toString();
    }

    /**
     * A {@link GameState} that resolves card names against the loaded card database, falling
     * back to any printing when the exact set and art it was dumped from are not installed.
     */
    private static GameState newGameState() {
        return new GameState() {
            @Override
            public IPaperCard getPaperCard(final String cardName, final String setCode, final int artId) {
                if (setCode != null && !setCode.isEmpty()) {
                    final IPaperCard exact = StaticData.instance().getCommonCards()
                            .getCard(cardName, setCode, artId);
                    if (exact != null) {
                        return exact;
                    }
                }
                return StaticData.instance().getCommonCards().getCard(cardName);
            }
        };
    }

    /**
     * The file's lines with blank ones dropped: the state parser reads the first character of
     * every line it is given, so one empty line takes the whole run down.
     */
    public static List<String> readableLines(final String stateText) {
        final List<String> lines = new ArrayList<>();
        for (final String line : stateText.split("\n")) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    private AttributionResult.Header header() {
        final AttributionResult.Header header = new AttributionResult.Header();
        header.stateFile = stateFile.getPath();
        header.seat = seat;
        header.mode = mode.name();
        header.rollouts = rollouts;
        header.baseSeed = seed;
        header.clock = clock;
        header.profile = profile;
        header.force = force == null || force.trim().isEmpty() ? null : force.trim();
        header.forceTimes = forceTimes;
        header.restoredTurn = restoredTurn;
        return header;
    }

    private AttributionResult.Verification verification(final Replay check) {
        final AttributionResult.Verification verification = new AttributionResult.Verification();
        verification.mismatches.addAll(refusals(check));
        verification.passed = verification.mismatches.isEmpty();
        for (final Map.Entry<String, String> field : expectedFields.entrySet()) {
            verification.checkedFields.add(field.getKey() + "=" + field.getValue());
        }
        return verification;
    }

    private void report(final Replay rollout) {
        final AttributionResult.Rollout row = rollout.row;
        final StringBuilder line = new StringBuilder("  ").append(row.arm).append(' ')
                .append(row.index + 1).append('/').append(rollouts)
                .append(" seed=").append(row.seed)
                .append(row.isDraw ? " draw" : " winner=" + row.winnerIndex)
                .append(" turns=").append(row.turns);
        if (row.timedOut) {
            line.append(" (clock)");
        }
        if (row.taxonomy != null) {
            line.append(" forced=").append(row.forcedAttempts);
            if (row.forcedOffers != row.forcedAttempts) {
                line.append("/of ").append(row.forcedOffers).append(" offered");
            }
            line.append(' ').append(row.taxonomy);
        }
        if (rollout.failure != null) {
            line.append(" error=").append(rollout.failure);
        }
        ORIGINAL_ERR.println(line);
    }

    private void write(final AttributionResult result) {
        final Gson gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        final String json = gson.toJson(result);
        if (out == null) {
            ORIGINAL_OUT.println(json);
            ORIGINAL_OUT.flush();
            return;
        }
        try {
            if (out.getParentFile() != null) {
                Files.createDirectories(out.getParentFile().toPath());
            }
            Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
            ORIGINAL_ERR.println("Wrote " + out);
        } catch (IOException e) {
            ORIGINAL_ERR.println("Error: could not write " + out + " - " + e.getMessage());
            ORIGINAL_OUT.println(json);
            ORIGINAL_OUT.flush();
        }
    }

    private static void restoreOut() {
        System.setOut(ORIGINAL_OUT);
        System.setErr(ORIGINAL_ERR);
    }

    /**
     * The dump names the seat it was taken for. Say so when the command was pointed at the
     * other one: forcing the wrong seat measures a different question and looks fine doing it.
     */
    private void warnIfSeatDisagrees(final String stateText) {
        for (final String raw : stateText.split("\n")) {
            final String line = raw.trim();
            if (!line.startsWith("# seat=")) {
                continue;
            }
            final int dumped = intOrZero(line.substring("# seat=".length()).trim());
            if (dumped != seat) {
                ORIGINAL_ERR.println("Warning: " + stateFile.getName() + " was dumped for seat "
                        + dumped + ", but --seat " + seat + " was given.");
            }
            return;
        }
    }

    private static int intOrZero(final String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
