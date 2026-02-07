package forge.ai.nn;

public final class NNConstants {
    private NNConstants() { }

    public static final int MAX_OPTIONS = 64;
    public static final int CARD_FEATURES = 16;       // features per card
    public static final int GLOBAL_FEATURES = 24;     // life, mana, phase, etc.
    public static final int BATTLEFIELD_SLOTS = 16;   // per player
    public static final int HAND_SLOTS = 8;
    public static final int STATE_SIZE = GLOBAL_FEATURES
        + (BATTLEFIELD_SLOTS * CARD_FEATURES * 2)  // my + opp battlefield
        + (HAND_SLOTS * CARD_FEATURES);            // my hand
    // STATE_SIZE = 24 + 256 + 256 + 128 = 664
    public static final int DECISION_TYPES = DecisionType.values().length;  // 8
}
