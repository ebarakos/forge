package forge.ai.llm;

/**
 * LLM decision routing mode.
 * HEAVY: LLM handles most decisions (attacks, blocks, effects, colors, scry, etc.)
 * LIGHT: LLM handles only spell selection (MAIN phases) and mulligan decisions;
 *        all other decisions (combat, discard, sacrifice, card choices) use the heuristic AI.
 */
public enum LLMMode {
    HEAVY, LIGHT;

    /** Parse from string, case-insensitive. Returns HEAVY if unrecognized. */
    public static LLMMode fromString(String s) {
        if (s == null || s.isEmpty()) return HEAVY;
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return HEAVY;
        }
    }
}
