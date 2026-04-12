package forge.ai.nn;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import forge.ai.simulation.GameStateEvaluator;
import forge.game.Game;
import forge.game.player.Player;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * Plugs a value-only ONNX model into Forge's existing heuristic search as a
 * terminal evaluation function.
 *
 * <p>The model contract (produced by {@code python -m forge_train.train --value-only}):
 * <ul>
 *   <li>Input  name="state"  shape=[1, STATE_SIZE=704]  dtype=float32</li>
 *   <li>Output name="value"  shape=[1, 1]               dtype=float32</li>
 *   <li>Output range: [-1, 1] where +1 = certain win, -1 = certain loss</li>
 * </ul>
 *
 * <p>The raw float value is converted to an integer {@link GameStateEvaluator.Score}
 * by scaling into a range that is compatible with the heuristic score magnitudes
 * (heuristic scores typically range from a few hundred to a few thousand; we scale
 * the NN output to ±10,000 to dominate when the NN is confident but remain
 * comparable at intermediate values).
 *
 * <p>Usage: pass an instance to
 * {@link GameStateEvaluator#setNNEvaluator(NNEvaluator)} before calling
 * {@link GameStateEvaluator#getScoreForGameState}. Enable via {@code --nn-eval}
 * on the Forge CLI.
 */
public class NNEvaluator implements AutoCloseable {

    /** Scale factor: NN output [-1,1] → integer score [-SCALE, SCALE]. */
    private static final int SCALE = 10_000;

    /**
     * Global shared instance set by SimulateMatch from {@code --nn-eval}.
     * Picked up by {@link forge.ai.AiController} to inject into SpellAbilityPicker.
     * Thread-safe: one instance is shared across all games (ONNX Runtime is thread-safe).
     */
    private static volatile NNEvaluator sharedInstance = null;

    /** Set the global shared instance. Call before games start. */
    public static void setSharedInstance(NNEvaluator eval) {
        sharedInstance = eval;
    }

    /** Get the global shared instance, or null if not configured. */
    public static NNEvaluator getSharedInstance() {
        return sharedInstance;
    }

    private final OrtEnvironment env;
    private final OrtSession session;

    /**
     * Load a value-only ONNX model.
     *
     * @param modelPath path to the {@code .onnx} file exported with
     *                  {@code forge_train.train --value-only}
     * @throws OrtException if the model cannot be loaded
     */
    public NNEvaluator(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath, options);
    }

    /**
     * Evaluate the game state from {@code aiPlayer}'s perspective.
     *
     * <p>Encodes the game state using {@link GameStateEncoder}, runs ONNX
     * inference, and converts the raw value in [-1, 1] to an integer score.
     *
     * @param game     the game to evaluate
     * @param aiPlayer the player whose perspective we evaluate from
     * @return integer score (larger = better for {@code aiPlayer})
     */
    public GameStateEvaluator.Score evaluate(Game game, Player aiPlayer) {
        float[] state = GameStateEncoder.encode(aiPlayer, game);
        float rawValue = runInference(state);
        // rawValue in [-1, 1]; convert to integer score
        int score = Math.round(rawValue * SCALE);
        return new GameStateEvaluator.Score(score);
    }

    /**
     * Run ONNX inference on a pre-encoded state vector.
     *
     * @param state float array of size {@link NNConstants#STATE_SIZE}
     * @return raw value estimate in [-1, 1]
     */
    public float runInference(float[] state) {
        try {
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(state), new long[]{1, NNConstants.STATE_SIZE});

            OrtSession.Result result = session.run(
                    Collections.singletonMap("state", inputTensor));

            float[][] valueOutput = (float[][]) result.get(0).getValue();
            float rawValue = valueOutput[0][0];

            inputTensor.close();
            result.close();

            return rawValue;
        } catch (OrtException e) {
            throw new RuntimeException("NNEvaluator: ONNX inference failed", e);
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            // ignore on close
        }
    }
}
