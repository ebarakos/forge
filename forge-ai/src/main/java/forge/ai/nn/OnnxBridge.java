package forge.ai.nn;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.util.Collections;

/**
 * ONNX Runtime bridge for neural-network inference. Implements {@link NNBridge}
 * so it can be used by NN controllers to make game decisions.
 *
 * <p>Input layout (1760 floats total):
 * <ul>
 *   <li>state[664]                — encoded game state</li>
 *   <li>decision_type_onehot[8]   — one-hot encoded {@link DecisionType}</li>
 *   <li>options_flat[64 * 16]     — flattened option feature matrix</li>
 *   <li>mask[64]                  — action mask (1.0 for valid options)</li>
 * </ul>
 *
 * <p>ONNX input name: {@code "input"}, shape {@code [1, 1760]}<br>
 * ONNX output names: {@code "policy"} shape {@code [1, 64]},
 * {@code "value"} shape {@code [1, 1]}
 */
public class OnnxBridge implements NNBridge, AutoCloseable {
    private final OrtEnvironment env;
    private volatile OrtSession session;

    /**
     * Load an ONNX model from the given file path.
     *
     * @param modelPath path to the {@code .onnx} model file
     * @throws OrtException if the model cannot be loaded
     */
    public OnnxBridge(String modelPath) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath, options);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flattens the game state, decision type, option features, and action
     * mask into a single 1760-float input tensor, runs ONNX inference, and
     * returns the index of the highest-scoring legal action.
     */
    @Override
    public int chooseOption(float[] gameState, int decisionType,
                            float[][] optionFeatures, int numOptions) {
        try {
            // 1. Build flat input array
            int inputSize = NNConstants.STATE_SIZE + NNConstants.DECISION_TYPES
                    + NNConstants.MAX_OPTIONS * NNConstants.CARD_FEATURES
                    + NNConstants.MAX_OPTIONS;
            float[] input = new float[inputSize]; // 664 + 8 + 1024 + 64 = 1760

            // Copy state
            System.arraycopy(gameState, 0, input, 0, NNConstants.STATE_SIZE);

            // Decision type one-hot
            int dtOffset = NNConstants.STATE_SIZE;
            input[dtOffset + decisionType] = 1.0f;

            // Flatten option features
            int optOffset = dtOffset + NNConstants.DECISION_TYPES;
            for (int i = 0; i < numOptions && i < NNConstants.MAX_OPTIONS; i++) {
                System.arraycopy(optionFeatures[i], 0, input,
                        optOffset + i * NNConstants.CARD_FEATURES, NNConstants.CARD_FEATURES);
            }

            // Action mask
            int maskOffset = optOffset + NNConstants.MAX_OPTIONS * NNConstants.CARD_FEATURES;
            for (int i = 0; i < numOptions && i < NNConstants.MAX_OPTIONS; i++) {
                input[maskOffset + i] = 1.0f;
            }

            // 2. Run ONNX session
            OnnxTensor inputTensor = OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(input), new long[]{1, inputSize});

            OrtSession.Result result = session.run(
                    Collections.singletonMap("input", inputTensor));

            // 3. Extract policy logits and find best legal action
            float[][] policy = (float[][]) result.get(0).getValue();
            float[] logits = policy[0];

            int bestIdx = 0;
            float bestVal = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < numOptions && i < NNConstants.MAX_OPTIONS; i++) {
                if (logits[i] > bestVal) {
                    bestVal = logits[i];
                    bestIdx = i;
                }
            }

            inputTensor.close();
            result.close();

            return bestIdx;
        } catch (OrtException e) {
            throw new RuntimeException("ONNX inference failed", e);
        }
    }

    /**
     * Hot-reload a new ONNX model, replacing the current session.
     * The old session is closed after the new one is successfully created.
     *
     * @param modelPath path to the new {@code .onnx} model file
     * @throws OrtException if the new model cannot be loaded
     */
    public synchronized void reloadModel(String modelPath) throws OrtException {
        OrtSession oldSession = this.session;
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        this.session = env.createSession(modelPath, options);
        if (oldSession != null) {
            oldSession.close();
        }
    }

    @Override
    public void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException e) {
            // ignore on close
        }
    }
}
