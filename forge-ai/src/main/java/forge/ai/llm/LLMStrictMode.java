package forge.ai.llm;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Strict mode for LLM-piloted seats, switched on by setting the environment
 * variable {@code FORGE_LLM_STRICT} to a truthy value.
 *
 * <p>Two things normally happen quietly and both of them turn a run that looks
 * like an LLM playing into a run of the plain heuristic AI:
 *
 * <ol>
 *   <li>A failed LLM call — a timeout, an HTTP error, an answer that cannot be
 *       parsed, an option index outside the list offered — is replaced by the
 *       heuristic AI's decision. The game continues and the final result looks
 *       entirely normal. The only trace is a line printed under
 *       {@code --llm-debug} plus a fallback counter in the end-of-run
 *       summary.</li>
 *   <li>A profile string that names an LLM but cannot be turned into a working
 *       client (missing endpoint URL, unsupported routing, missing model name)
 *       leaves the seat on the heuristic AI. The run starts anyway.</li>
 * </ol>
 *
 * <p>Either one silently voids a measurement. With {@code FORGE_LLM_STRICT} set
 * they end the run instead, with a message naming the seat, the failure and how
 * many LLM calls had been made.
 *
 * <p><b>How the run ends depends on who is running it.</b> A headless run has
 * {@link #useProcessHalt()} armed by the command-line entry point and stops the
 * JVM with {@link #EXIT_CODE}; anywhere else — the desktop client above all —
 * the abort throws instead, so Forge's own error handling reports it. That
 * distinction is not cosmetic: {@code FORGE_LLM_STRICT} is read from a
 * {@code .env} file as well as the process environment, so a line left in the
 * project's {@code .env} after a benchmark is live for the next interactive
 * game too, and a single timed-out call would otherwise make the whole
 * application vanish mid-match with no dialog and nothing catchable.
 *
 * <p>With the variable unset every method here is a no-op and behaviour is
 * exactly what it was before this class existed.
 */
public final class LLMStrictMode {

    /** Environment variable (also read from a {@code .env} file) that turns strict mode on. */
    public static final String ENV_VAR = "FORGE_LLM_STRICT";

    /** Process exit status used whenever strict mode ends a run. */
    public static final int EXIT_CODE = 4;

    /** Resolved lazily so that an ordinary run never touches the filesystem for this. */
    private static volatile Boolean enabled;

    /**
     * What to do with the abort message.
     *
     * <p>Throwing is the default because it is the only ending that is safe everywhere:
     * a GUI game can report it, and a headless run that has not armed the process halt
     * still fails loudly. {@link #useProcessHalt()} swaps in the harder ending for
     * command-line runs, where a sim can be several threads deep inside the engine.
     */
    private static volatile Consumer<String> aborter = LLMStrictMode::throwAbort;

    private LLMStrictMode() {
    }

    /** Whether {@code FORGE_LLM_STRICT} is set to a truthy value. Cached after the first call. */
    public static boolean isEnabled() {
        Boolean cached = enabled;
        if (cached == null) {
            synchronized (LLMStrictMode.class) {
                if (enabled == null) {
                    enabled = isTruthy(LLMConfig.loadProviderApiKey(ENV_VAR));
                }
                cached = enabled;
            }
        }
        return cached;
    }

    /**
     * Called for every LLM call that fell back to the heuristic AI. Ends the run
     * when strict mode is on; does nothing otherwise.
     *
     * @param seat      name of the player whose call failed
     * @param endpoint  provider/model the seat was configured to use
     * @param reason    what went wrong, as specific as the caller can make it
     * @param calls     LLM calls this client has made so far, this one included
     * @param fallbacks fallbacks this client has recorded so far, this one included
     */
    public static void onFallback(String seat, String endpoint, String reason,
                                   int calls, int fallbacks) {
        if (!isEnabled()) {
            return;
        }
        abort(fallbackMessage(seat, endpoint, reason, calls, fallbacks));
    }

    /** The text {@link #onFallback} aborts with. Split out so it can be asserted in tests. */
    static String fallbackMessage(String seat, String endpoint, String reason,
                                   int calls, int fallbacks) {
        StringBuilder sb = new StringBuilder();
        sb.append("[LLM STRICT] Seat '").append(nonEmpty(seat, "<unnamed>"))
                .append("' fell back to the heuristic AI, so this run no longer measures the model.\n");
        sb.append("[LLM STRICT]   configured as: ").append(nonEmpty(endpoint, "<unknown endpoint>")).append('\n');
        sb.append("[LLM STRICT]   failure: ").append(nonEmpty(reason, "<no reason recorded>")).append('\n');
        sb.append("[LLM STRICT]   LLM calls so far: ").append(calls)
                .append(", fallbacks so far: ").append(fallbacks).append('\n');
        sb.append("[LLM STRICT] ").append(ENV_VAR).append(" is set, so the run stops here with exit status ")
                .append(EXIT_CODE).append('.');
        return sb.toString();
    }

    /**
     * Arm the hard ending for headless runs: print to the real stderr and stop the JVM.
     *
     * <p>Called by the command-line entry point, and only there. A sim abort can come
     * from a game worker thread while other threads hold engine locks, so
     * {@link Runtime#halt} — which no shutdown hook can block — is the only ending that
     * reliably happens. In the desktop client the same call would make the application
     * disappear mid-game, which is why it is opt-in rather than the default.
     */
    public static void useProcessHalt() {
        aborter = LLMStrictMode::printAndHalt;
    }

    /**
     * Message for a seat whose profile names an LLM but which would play as the
     * plain heuristic AI. The caller decides how to fail — the command line
     * returns {@link #EXIT_CODE}, the desktop client throws.
     *
     * @param seat    which seat, in whatever wording the caller uses ("player 1")
     * @param profile the profile string that was asked for
     * @param cause   why no working client came out of it
     */
    public static String silentHeuristicSeatMessage(String seat, String profile, String cause) {
        StringBuilder sb = new StringBuilder();
        sb.append("[LLM STRICT] Seat ").append(nonEmpty(seat, "<unknown>"))
                .append(" asked for LLM profile '").append(nonEmpty(profile, ""))
                .append("' but no working LLM client could be built for it.\n");
        sb.append("[LLM STRICT]   cause: ").append(nonEmpty(cause, "<no cause recorded>")).append('\n');
        sb.append("[LLM STRICT] Without ").append(ENV_VAR)
                .append(" this seat would have played as the plain heuristic AI and the run would have")
                .append(" looked normal. Stopping with exit status ").append(EXIT_CODE).append('.');
        return sb.toString();
    }

    /**
     * Message for a seat that had a working LLM client but never used it — the
     * end-of-run counterpart to {@link #silentHeuristicSeatMessage}. Zero calls
     * means every decision in that seat came from the heuristic AI.
     */
    public static String zeroCallSeatMessage(String seat, String endpoint) {
        StringBuilder sb = new StringBuilder();
        sb.append("[LLM STRICT] Seat ").append(nonEmpty(seat, "<unknown>"))
                .append(" was configured as ").append(nonEmpty(endpoint, "<unknown endpoint>"))
                .append(" but made 0 LLM calls, so the heuristic AI played every decision.\n");
        sb.append("[LLM STRICT] Stopping with exit status ").append(EXIT_CODE).append('.');
        return sb.toString();
    }

    /** Same truthiness rule as the other {@code FORGE_LLM_*} switches: anything but 0/false. */
    static boolean isTruthy(String value) {
        return value != null && !value.isEmpty()
                && !"false".equalsIgnoreCase(value) && !"0".equals(value);
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private static void abort(String message) {
        aborter.accept(message);
    }

    /**
     * Default abort: throw, so whoever is running the game can report it.
     *
     * <p>Safe on the GUI's event and game threads, where Forge's own exception handler
     * turns it into a bug report the player can see, and loud enough in any other
     * embedding that the run cannot quietly continue as a heuristic one.
     */
    private static void throwAbort(String message) {
        throw new IllegalStateException(message);
    }

    /**
     * Hard abort, armed by {@link #useProcessHalt()}: write to the process's real
     * stderr and stop the JVM.
     *
     * <p>The file descriptor is opened directly rather than going through
     * {@link System#err} because simulation runs replace {@code System.err} with
     * a sink whenever {@code -q}, {@code --json} or {@code -j} is passed, which
     * is exactly when a strict abort matters most.
     *
     * <p>{@link Runtime#halt} rather than {@link System#exit}: the abort can come
     * from a game worker thread while other threads hold engine locks, and halt
     * cannot be blocked by a shutdown hook waiting on them.
     */
    private static void printAndHalt(String message) {
        PrintStream realErr = new PrintStream(new FileOutputStream(FileDescriptor.err), true);
        realErr.println(message);
        realErr.flush();
        System.out.flush();
        Runtime.getRuntime().halt(EXIT_CODE);
    }

    // ---- test seams -------------------------------------------------------
    // Package-private on purpose: only the tests in this package touch them.

    /** Overrides the resolved env value; pass null to go back to reading the environment. */
    static void setEnabledForTesting(Boolean value) {
        enabled = value;
    }

    /** Swaps the abort action and returns the previous one so a test can restore it. */
    static Consumer<String> setAborterForTesting(Consumer<String> next) {
        Consumer<String> previous = aborter;
        aborter = (next == null) ? LLMStrictMode::throwAbort : next;
        return previous;
    }
}
