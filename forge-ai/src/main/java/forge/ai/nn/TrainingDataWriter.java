package forge.ai.nn;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.gson.stream.JsonWriter;

/**
 * Records every NN decision plus the final game outcome as JSONL
 * (newline-delimited JSON). Thread-safe: each game thread gets its own
 * writer instance. One file per game.
 *
 * <p>Decisions are buffered in memory and flushed when the outcome is
 * recorded. This also enables writing a second "mirror" file from the
 * opponent's perspective via
 * {@link #recordOutcome(float, int, String, List)}.
 */
public class TrainingDataWriter {

    /** Holds one captured decision. */
    public static final class DecisionRecord {
        public final int turn;
        public final String phase;
        public final DecisionType type;
        public final float[] state;    // STATE_SIZE floats, from this player's perspective
        public final float[][] options;
        public final int numOptions;
        public final int chosenIndex;
        /** (myLife - oppLife) / 20.0 — life advantage at decision time. */
        public final float lifeDiff;
        /** (myCreaturePower - oppCreaturePower) / 20.0 — board power advantage. */
        public final float boardDiff;
        /** (myHandSize - oppHandSize) / 7.0 — card advantage. */
        public final float cardDiff;

        public DecisionRecord(int turn, String phase, DecisionType type,
                              float[] state, float[][] options,
                              int numOptions, int chosenIndex) {
            this.turn = turn;
            this.phase = phase;
            this.type = type;
            // Defensive copy — state and options arrays come from the encoder and
            // may be reused or modified by the game engine between decisions.
            this.state = state.clone();
            this.options = new float[numOptions][];
            for (int i = 0; i < numOptions; i++) {
                this.options[i] = options[i].clone();
            }
            this.numOptions = numOptions;
            this.chosenIndex = chosenIndex;

            // Compute reward shaping signals directly from the state tensor.
            // state[0] = myLife/20, state[1] = oppLife/20 → diff is already normalised.
            this.lifeDiff = state[0] - state[1];
            // state[2] = myHandSize/7, state[3] = oppHandSize/7 → diff already normalised.
            this.cardDiff = state[2] - state[3];
            // Board power: iterate battlefield slots to sum creature power/20 for each side.
            // My battlefield: offset 24, 16 slots × 16 features; feature[2]=power/20, feature[4]=isCreature.
            this.boardDiff = computeBoardDiff(state);
        }

        /**
         * Sum (myCreaturePower - oppCreaturePower) / 20.0 from the state tensor.
         * Each battlefield has 16 slots of 16 features starting at offsets 24 and 280.
         * Feature index 2 = power/20, feature index 4 = isCreature flag.
         */
        private static float computeBoardDiff(float[] state) {
            float myPower  = sumCreaturePower(state, NNConstants.GLOBAL_FEATURES, NNConstants.BATTLEFIELD_SLOTS);
            float oppPower = sumCreaturePower(state,
                    NNConstants.GLOBAL_FEATURES + NNConstants.BATTLEFIELD_SLOTS * NNConstants.CARD_FEATURES,
                    NNConstants.BATTLEFIELD_SLOTS);
            return myPower - oppPower;
        }

        private static float sumCreaturePower(float[] state, int offset, int slots) {
            float total = 0.0f;
            for (int i = 0; i < slots; i++) {
                int base = offset + i * NNConstants.CARD_FEATURES;
                float isCreature = state[base + 4]; // feature index 4
                if (isCreature > 0.5f) {
                    total += state[base + 2]; // feature index 2 = power/20
                }
            }
            return total;
        }
    }

    // ------------------------------------------------------------------
    // Perspective-flip constants (must match NNConstants / GameStateEncoder)
    // ------------------------------------------------------------------

    /** Offset of the global features block in the state tensor. */
    private static final int GLOBAL_OFF = 0;
    /** Offset of "my battlefield" block. */
    private static final int MY_BF_OFF  = NNConstants.GLOBAL_FEATURES;                         // 24
    /** Size of one battlefield block (16 slots × 16 features). */
    private static final int BF_SIZE    = NNConstants.BATTLEFIELD_SLOTS * NNConstants.CARD_FEATURES; // 256
    /** Offset of "opponent battlefield" block. */
    private static final int OPP_BF_OFF = MY_BF_OFF + BF_SIZE;                                  // 280
    /** Offset of "my hand" block. */
    private static final int MY_HAND_OFF = OPP_BF_OFF + BF_SIZE;                                // 536
    /** Size of the hand block (8 slots × 16 features). */
    private static final int HAND_SIZE  = NNConstants.HAND_SLOTS * NNConstants.CARD_FEATURES;   // 128

    // ------------------------------------------------------------------

    private final String outputDir;
    private final List<DecisionRecord> buffer = new ArrayList<>();
    private boolean closed;

    public TrainingDataWriter(String outputDir) {
        this.outputDir = outputDir;
    }

    // ------------------------------------------------------------------
    // Primary recording API
    // ------------------------------------------------------------------

    public synchronized void recordDecision(int turn, String phase, DecisionType type,
                                            float[] state, float[][] options,
                                            int numOptions, int chosenIndex) {
        if (closed) return;
        buffer.add(new DecisionRecord(turn, phase, type, state, options, numOptions, chosenIndex));
    }

    /**
     * Flush all buffered decisions to a new JSONL file and append the outcome.
     * Also writes a perspective-flipped mirror file if {@code mirrorDecisions}
     * is non-null and non-empty.
     *
     * @param result          1.0 = this player won, 0.0 = loss
     * @param turns           turn count at game end
     * @param reason          win condition string
     * @param mirrorDecisions buffered decisions from the opponent's controller
     *                        (encoded from the opponent's POV), or {@code null}
     *                        to skip mirror writing
     */
    public synchronized void recordOutcome(float result, int turns, String reason,
                                           List<DecisionRecord> mirrorDecisions) {
        if (closed) return;
        closed = true;

        // Write the primary file: this player's decisions + their outcome
        writeFile(outputDir, buffer, result, turns, reason);

        // Write the mirror file: opponent's decisions (already in their own POV)
        // with the outcome inverted (what was a win for us is a loss for them).
        if (mirrorDecisions != null && !mirrorDecisions.isEmpty()) {
            float mirrorResult = 1.0f - result;
            writeMirrorFile(mirrorDecisions, mirrorResult, turns, reason);
        }
    }

    /**
     * Backward-compatible overload — no mirror file.
     */
    public synchronized void recordOutcome(float result, int turns, String reason) {
        recordOutcome(result, turns, reason, null);
    }

    /** Return the buffered decisions so that the peer controller can use them as mirror data. */
    public synchronized List<DecisionRecord> getBuffer() {
        return new ArrayList<>(buffer);
    }

    public synchronized void close() {
        closed = true;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Write a JSONL file containing the given decisions followed by the outcome.
     */
    private static void writeFile(String dir, List<DecisionRecord> decisions,
                                  float result, int turns, String reason) {
        if (decisions.isEmpty()) return;

        File outDir = new File(dir);
        if (!outDir.exists()) {
            outDir.mkdirs();
        }
        File outputFile = new File(dir,
                "game_" + UUID.randomUUID() + "_" + System.currentTimeMillis() + ".jsonl");

        try (BufferedWriter fw = new BufferedWriter(new FileWriter(outputFile))) {
            for (DecisionRecord rec : decisions) {
                fw.write(encodeDecision(rec));
                fw.newLine();
            }
            fw.write(encodeOutcome(result, turns, reason));
            fw.newLine();
            fw.flush();
        } catch (IOException e) {
            System.err.println("TrainingDataWriter: failed to write file — " + e.getMessage());
        }
    }

    /**
     * Write a perspective-flipped mirror file.
     *
     * <p>The {@code mirrorDecisions} list contains decisions recorded from the
     * opponent controller's own perspective (state encoded with the opponent as
     * "me"). We write them as-is — from the opponent's point of view they are
     * already correctly encoded, so they need no further flipping here. The only
     * adjustment is the outcome, which is inverted relative to the primary player.
     *
     * <p>The {@code flipState} helper is provided for cases where a caller needs
     * to construct a mirrored state from the primary player's encoding, but it is
     * NOT applied here because the opponent decisions are already in the opponent's
     * coordinate system.
     */
    private void writeMirrorFile(List<DecisionRecord> mirrorDecisions,
                                 float mirrorResult, int turns, String reason) {
        writeFile(outputDir, mirrorDecisions, mirrorResult, turns, reason);
    }

    // ------------------------------------------------------------------
    // Perspective-flip utility (public so callers can use it if needed)
    // ------------------------------------------------------------------

    /**
     * Produce a new state tensor representing the same game state but from the
     * opponent's perspective. This should only be used when the opponent's state
     * was NOT independently encoded (i.e. the only available state is the primary
     * player's encoding). When the opponent controller encodes state via
     * {@link GameStateEncoder#encode(forge.game.player.Player, forge.game.Game)}
     * using its own {@code Player} reference, no flip is needed.
     *
     * <p>Swaps:
     * <ul>
     *   <li>Global pairs: [0]↔[1] life, [2]↔[3] hand size, [4]↔[5] graveyard,
     *       [6]↔[7] library</li>
     *   <li>[9] "is my turn" inverted (1 - x)</li>
     *   <li>[23] available mana zeroed (opponent's mana is not in primary state)</li>
     *   <li>My battlefield block (24..279) ↔ Opponent battlefield block (280..535)</li>
     *   <li>My hand block (536..663) zeroed (opponent's hand is not in primary state)</li>
     * </ul>
     *
     * @param state the primary player's state tensor (STATE_SIZE floats)
     * @return a new tensor of the same size from the opponent's perspective
     */
    public static float[] flipState(float[] state) {
        float[] flipped = state.clone();

        // --- Global features ---
        swap(flipped, GLOBAL_OFF,      GLOBAL_OFF + 1);  // life
        swap(flipped, GLOBAL_OFF + 2,  GLOBAL_OFF + 3);  // hand size
        swap(flipped, GLOBAL_OFF + 4,  GLOBAL_OFF + 5);  // graveyard size
        swap(flipped, GLOBAL_OFF + 6,  GLOBAL_OFF + 7);  // library size
        // [8] turn number — unchanged (global)
        flipped[GLOBAL_OFF + 9] = 1.0f - state[GLOBAL_OFF + 9]; // is my turn
        // [10..22] phase one-hot — unchanged (global)
        flipped[GLOBAL_OFF + 23] = 0.0f; // opponent's available mana unknown

        // --- Battlefield blocks ---
        // Swap the entire MY_BATTLEFIELD block with the OPP_BATTLEFIELD block.
        for (int i = 0; i < BF_SIZE; i++) {
            float tmp = flipped[MY_BF_OFF + i];
            flipped[MY_BF_OFF  + i] = flipped[OPP_BF_OFF + i];
            flipped[OPP_BF_OFF + i] = tmp;
        }

        // --- Hand ---
        // The primary state only contains MY hand, not the opponent's.
        // After flipping, the "my hand" slot should contain the opponent's hand,
        // which we do not have. Zero it out to avoid feeding wrong data.
        for (int i = 0; i < HAND_SIZE; i++) {
            flipped[MY_HAND_OFF + i] = 0.0f;
        }

        return flipped;
    }

    private static void swap(float[] arr, int i, int j) {
        float tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
    }

    // ------------------------------------------------------------------
    // JSON serialisation helpers
    // ------------------------------------------------------------------

    private static String encodeDecision(DecisionRecord rec) throws IOException {
        StringWriter sw = new StringWriter(512);
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("type").value("decision");
        jw.name("turn").value(rec.turn);
        jw.name("phase").value(rec.phase);
        jw.name("decisionType").value(rec.type.name());

        jw.name("state");
        jw.beginArray();
        for (float v : rec.state) {
            jw.value(v);
        }
        jw.endArray();

        jw.name("options");
        jw.beginArray();
        for (int i = 0; i < rec.numOptions; i++) {
            jw.beginArray();
            for (float v : rec.options[i]) {
                jw.value(v);
            }
            jw.endArray();
        }
        jw.endArray();

        jw.name("numOptions").value(rec.numOptions);
        jw.name("chosenIndex").value(rec.chosenIndex);
        jw.name("lifeDiff").value(rec.lifeDiff);
        jw.name("boardDiff").value(rec.boardDiff);
        jw.name("cardDiff").value(rec.cardDiff);
        jw.endObject();
        jw.close();
        return sw.toString();
    }

    private static String encodeOutcome(float result, int turns, String reason) throws IOException {
        StringWriter sw = new StringWriter(128);
        JsonWriter jw = new JsonWriter(sw);
        jw.beginObject();
        jw.name("type").value("outcome");
        jw.name("result").value(result);
        jw.name("turns").value(turns);
        jw.name("reason").value(reason);
        jw.endObject();
        jw.close();
        return sw.toString();
    }
}
