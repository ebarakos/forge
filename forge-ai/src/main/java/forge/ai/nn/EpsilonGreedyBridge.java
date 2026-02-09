package forge.ai.nn;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps an NNBridge and applies epsilon-greedy exploration.
 * With probability epsilon, returns a random legal action;
 * otherwise delegates to the inner bridge.
 */
public class EpsilonGreedyBridge implements NNBridge {
    private final NNBridge inner;
    private final float epsilon;

    public EpsilonGreedyBridge(NNBridge inner, float epsilon) {
        this.inner = inner;
        this.epsilon = epsilon;
    }

    @Override
    public int chooseOption(float[] gameState, int decisionType,
                            float[][] optionFeatures, int numOptions) {
        if (ThreadLocalRandom.current().nextFloat() < epsilon) {
            return ThreadLocalRandom.current().nextInt(numOptions);
        }
        return inner.chooseOption(gameState, decisionType, optionFeatures, numOptions);
    }

    @Override
    public float evaluateState(float[] gameState) {
        return inner.evaluateState(gameState);
    }
}
