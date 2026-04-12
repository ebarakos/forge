package forge.ai.llm;

/**
 * System and user prompt templates for LLM game play.
 */
public final class PromptTemplates {
    private PromptTemplates() {}

    /**
     * System prompt establishing the LLM's role as an expert MTG player.
     */
    public static final String SYSTEM_PROMPT =
            "You are an expert Magic: The Gathering player. "
            + "You will be shown the current game state and a list of numbered options. "
            + "Respond with ONLY the number of your chosen option "
            + "(or comma-separated numbers for batch decisions like attack declarations). "
            + "No explanation, no text, just the number(s).\n\n"
            + "Strategy guidelines:\n"
            + "- Play lands before casting spells when possible\n"
            + "- Use removal spells to kill the biggest threats\n"
            + "- Attack when the damage dealt is worth more than the risk of losing creatures\n"
            + "- For damage spells: prioritize lethal face damage > killing key creatures > value damage to face\n"
            + "- Cast creatures in MAIN1 (before combat), save instants/removal for MAIN2 or opponent's turn\n"
            + "- Hold counterspells for the opponent's most impactful spells\n"
            + "- Manage your life total as a resource — take damage to preserve tempo\n"
            + "- Consider card advantage and mana efficiency in every decision\n"
            + "- If no spell is worth casting this turn, choose PASS";

    /**
     * Build a user prompt for a spell selection decision.
     */
    public static String spellSelection(String gameState, String options) {
        return gameState + "\nChoose a spell or ability to play, or PASS (last option).\n"
                + "If you have mana available and a good play, prefer acting over passing.\n\n" + options;
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
     * Build a user prompt for batch attack declaration (all creatures at once).
     * B2: also asks for a one-line blocking-plan note on line 2, which is
     * fed back into the subsequent block decision for continuity.
     */
    public static String batchAttack(String gameState, String attackOptions) {
        return gameState + "\nDECLARE ATTACKERS\n" + attackOptions
                + "\n\nLine 1: comma-separated attacker indices (or NONE)."
                + "\nLine 2 (optional): one short sentence describing your blocking plan"
                + " if they swing back — begin it with PLAN:.";
    }

    /**
     * Build a user prompt for a block decision (per attacker).
     */
    public static String block(String gameState, String blockOption) {
        return gameState + "\n" + blockOption;
    }

    /**
     * Build a user prompt for batch block declaration (all attackers/blockers at once).
     */
    public static String batchBlock(String gameState, String blockOptions) {
        return gameState + "\nDECLARE BLOCKERS\n" + blockOptions;
    }

    /**
     * Build a user prompt for a batched scry/surveil decision.
     */
    public static String batchScry(String gameState, String scryOptions) {
        return gameState + "\nSCRY/SURVEIL\n" + scryOptions;
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

    /**
     * Build a prompt for picking multiple items in one batch call (B3).
     * Response format: comma-separated indices (e.g. "0,2,3"), or "NONE" when
     * no items should be picked and {@code min == 0}.
     */
    public static String batchPick(String gameState, String options, String context,
                                    int min, int max) {
        String noneHint = min == 0 ? " or NONE" : "";
        return gameState + "\n" + context
                + "\nPick between " + min + " and " + max + " items."
                + " Respond with comma-separated indices (e.g. 0,2,3)" + noneHint + ".\n\n"
                + options;
    }

    /**
     * Build a prompt for the MAIN-phase plan batching (B1).
     * The LLM returns an ordered list of spell indices (or a subset + PASS)
     * that will be executed in sequence until the plan is invalidated.
     */
    public static String mainPhasePlan(String gameState, String options) {
        return gameState + "\nPLAN YOUR MAIN PHASE.\n"
                + "List the spell indices to cast in order, comma-separated. "
                + "End with PASS to stop casting (e.g. 2,0,PASS). "
                + "If you want to do nothing, reply PASS.\n\n" + options;
    }
}
