package forge.ai.nn;

import java.util.concurrent.ThreadLocalRandom;

public class RandomBridge implements NNBridge {
    @Override
    public int chooseOption(float[] gameState, int decisionType, float[][] optionFeatures, int numOptions) {
        return ThreadLocalRandom.current().nextInt(numOptions);
    }
}
