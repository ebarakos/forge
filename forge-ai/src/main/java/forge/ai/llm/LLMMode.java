package forge.ai.llm;

/**
 * LLM decision routing mode.
 * HEAVY: LLM handles most decisions (attacks, blocks, effects, colors, etc.)
 * LIGHT: LLM handles only key strategic decisions (spells, mulligan, discard, sacrifice)
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
