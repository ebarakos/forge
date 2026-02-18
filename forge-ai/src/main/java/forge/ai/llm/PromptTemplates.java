package forge.ai.llm;

/**
 * System and user prompt templates for LLM game play.
 */
public final class PromptTemplates {
    private PromptTemplates() {}

    /**
     * System prompt establishing the LLM's role as an expert MTG player.
     * Kept concise to minimize token usage (~150 tokens).
     */
    public static final String SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player. "
            + "You will be shown the current game state and a list of numbered options. "
            + "Respond with ONLY the number of your chosen option. "
            + "No explanation, no text, just the number.\n\n"
            + "Strategy guidelines:\n"
            + "- Play lands before casting spells when possible\n"
            + "- Use removal on the biggest threats\n"
            + "- Attack when profitable, considering combat tricks and blocks\n"
            + "- Hold counterspells for important threats\n"
            + "- Manage your life total as a resource\n"
            + "- Consider card advantage in every decision";

    /**
     * Build a user prompt for a spell selection decision.
     */
    public static String spellSelection(String gameState, String options) {
        return gameState + "\nChoose a spell or ability to play, or PASS.\n\n" + options;
    }

    /**
     * Build a user prompt for a mulligan decision.
     */
    public static String mulligan(String gameState) {
        return gameState + "\nDo you want to keep this hand?\n\nOPTIONS:\n0: Keep\n1: Mulligan\n";
    }

    /**
     * Build a user prompt for an attack decision (per creature).
     */
    public static String attack(String gameState, String attackOption) {
        return gameState + "\n" + attackOption;
    }

    /**
     * Build a user prompt for a block decision (per attacker).
     */
    public static String block(String gameState, String blockOption) {
        return gameState + "\n" + blockOption;
    }

    /**
     * Build a user prompt for choosing a card from a list.
     */
    public static String chooseCard(String gameState, String options, String context) {
        return gameState + "\n" + context + "\n\n" + options;
    }

    /**
     * Build a user prompt for a boolean/confirm decision.
     */
    public static String booleanChoice(String gameState, String question) {
        return gameState + "\n" + question;
    }

    /**
     * Build a user prompt for a number choice.
     */
    public static String numberChoice(String gameState, String options, String context) {
        return gameState + "\n" + context + "\n\n" + options;
    }

    /**
     * Build a user prompt for a scry/surveil decision.
     */
    public static String scry(String gameState, String option) {
        return gameState + "\n" + option;
    }

    /**
     * Build a user prompt for choosing from a generic list (types, colors, etc).
     */
    public static String genericChoice(String gameState, String options, String context) {
        return gameState + "\n" + context + "\n\n" + options;
    }
}
