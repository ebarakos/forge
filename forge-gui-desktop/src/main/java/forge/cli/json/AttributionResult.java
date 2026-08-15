package forge.cli.json;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON output model for the {@code attribute} subcommand: one dumped board position replayed
 * to completion many times, once per row.
 *
 * <p>The file is the whole record of a run. Everything needed to repeat the run byte for byte
 * is in {@link Header}, everything needed to judge whether the run may be believed is in
 * {@link Verification}, and every game played is one {@link Rollout}. Aggregation is
 * deliberately not done here: pooling positions and putting an interval around the difference
 * belongs to the caller, which sees all of a candidate's positions and this command sees one.
 *
 * <p>Serialized with Gson, so the field names are the JSON keys. Renaming one is a breaking
 * change for whatever reads the file.
 */
public class AttributionResult {

    /** Forge version string, so a result can be tied to the engine that produced it. */
    public String version;

    /** What was asked for. */
    public Header header;

    /** Whether the restored position matched the file it came from. */
    public Verification verification;

    /** One row per game played, in the order they were played. */
    public List<Rollout> rollouts = new ArrayList<>();

    /** The inputs of the run, complete enough to repeat it. */
    public static class Header {
        /** Path of the board dump that was replayed, as it was given on the command line. */
        public String stateFile;
        /** Seat the position was dumped for, 0-based; the seat the forced arm overrules. */
        public int seat;
        /** {@code natural}, {@code ab} or {@code aa}. */
        public String mode;
        /** Rollouts requested per arm. Zero means the run only verified the restore. */
        public int rollouts;
        /** Base seed every per-rollout seed derives from. */
        public long baseSeed;
        /** Seconds a single game may take before it is abandoned and booked as a draw. */
        public int clock;
        /** AI profile both seats play with. */
        public String profile;
        /** The forced action spec, or null when no arm forces anything. */
        public String force;
        /** The turn the restored position starts in, which is the turn forcing is allowed in. */
        public int restoredTurn;
    }

    /**
     * The result of comparing the restored position against the file it came from.
     *
     * <p>A run that fails this plays no games at all. A position that restores wrong would
     * produce numbers about a board nobody chose, which is worse than producing none.
     */
    public static class Verification {
        /** True when every checked field of the restored game matched the file. */
        public boolean passed;
        /** One line per field that did not match; empty when {@link #passed} is true. */
        public List<String> mismatches = new ArrayList<>();
        /** The fields that were compared, as {@code key=value} from the file. */
        public List<String> checkedFields = new ArrayList<>();
    }

    /** One replayed game. */
    public static class Rollout {
        /** {@code natural} or {@code forced}; in {@code aa} mode, {@code natural} and {@code natural-b}. */
        public String arm;
        /** Index within the arm, from 0. */
        public int index;
        /** The seed this game's RNG was started from. */
        public long seed;
        /** Winning player index (0-based), or null for a draw. */
        public Integer winnerIndex;
        /** True when nobody won. A timeout is booked as a draw, so this is true then too. */
        public boolean isDraw;
        /** Turn number the game ended on. */
        public int turns;
        /** Forge's own end reason, or {@code Timeout} when the clock ended it. */
        public String endReason;
        /** True when the clock ended the game rather than the game ending itself. */
        public boolean timedOut;
        /** How many times the forced action was played. Zero on a natural arm. */
        public int forcedAttempts;
        /** Phase of the first force, or null if it never happened. */
        public String forcedFirstPhase;
        /**
         * How far the forcing got: {@code FORCED}, {@code NO_LEGAL_TARGET},
         * {@code COST_CHECK_FAILED} or {@code NEVER_OFFERED}. Null on a natural arm.
         */
        public String taxonomy;
        /** Wall-clock milliseconds the game took. */
        public long timeMs;
    }
}
