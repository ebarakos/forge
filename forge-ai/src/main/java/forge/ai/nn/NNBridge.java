package forge.ai.nn;

public interface NNBridge {
    /**
     * Choose one option index from [0, numOptions).
     *
     * @param gameState  encoded game state, float[664]
     * @param decisionType  DecisionType.ordinal() (0-7)
     * @param optionFeatures  float[numOptions][16] — features per option
     * @param numOptions  number of valid options (always >= 1)
     * @return chosen index in [0, numOptions)
     */
    int chooseOption(float[] gameState, int decisionType, float[][] optionFeatures, int numOptions);

    /**
     * Value estimate for current position. Returns 0 if not supported.
     */
    default float evaluateState(float[] gameState) { return 0f; }
}
