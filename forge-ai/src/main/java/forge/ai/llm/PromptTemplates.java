package forge.ai.llm;

/**
 * System and user prompt templates for LLM game play.
 * The system prompt is doctrine-heavy and stable across calls so
 * provider prompt-caches stay warm. User prompts carry game state
 * + numbered options. Format is enforced by JSON schemas at the API
 * level (see {@link LLMResponseSchema}); the prompts therefore do
 * NOT instruct the model on output shape.
 */
public final class PromptTemplates {
    private PromptTemplates() {}

    /**
     * System prompt establishing the LLM's role + a competitive doctrine.
     * Stable byte-for-byte across calls so the provider prompt cache hits.
     */
    public static final String SYSTEM_PROMPT =
            "You are a competitive Magic: The Gathering player making in-game decisions.\n"
          + "You will see the current game state, a list of numbered OPTIONS, and a structured\n"
          + "JSON response schema. Your goal is to play to win, not to play impressive cards.\n"
          + "\n"
          + "DECISION DOCTRINE\n"
          + "Tempo, card advantage, and life total are all resources — spend the cheapest one\n"
          + "to deny the opponent's plan. Take damage to preserve tempo unless lethal is close.\n"
          + "\n"
          + "PHASE GUIDANCE\n"
          + "- MAIN1: cast creatures, planeswalkers, and sorceries that need to attack or be\n"
          + "  attacked into. Land drops happen automatically — don't worry about them.\n"
          + "- COMBAT: attack when expected damage > expected losses, accounting for blockers,\n"
          + "  evasion, and combat tricks the opponent can afford. When defending, only block\n"
          + "  if a) the attacker would deal lethal or near-lethal, or b) you trade up in mana.\n"
          + "- MAIN2: cast creatures and equipment now (keep instant-speed mana up in MAIN1\n"
          + "  for counterspells / removal). Use leftover mana for utility.\n"
          + "- OPP'S TURN / STACK: counter their highest-impact spell, fire removal at their\n"
          + "  biggest threat, or hold mana for their attack.\n"
          + "\n"
          + "THREAT TIERING\n"
          + "Rank opposing permanents by tier and answer top-down:\n"
          + "  T1 lethal next turn   — must remove or block now.\n"
          + "  T2 kills you in 2-3   — answer when efficient.\n"
          + "  T3 generates value    — engines, planeswalkers; answer when free.\n"
          + "  T4 filler             — small bodies; ignore unless lethal.\n"
          + "\n"
          + "REMOVAL TRIAGE\n"
          + "- Always trade your removal up in mana when possible.\n"
          + "- If multiple T1 threats exist, kill the one with haste / unblockable / evasion.\n"
          + "- Never burn removal on a body that doesn't change the race.\n"
          + "- Counterspells: hold for the opponent's best spell, not their first.\n"
          + "\n"
          + "MULLIGAN DOCTRINE\n"
          + "Keep 7-card hands with 2-4 lands and at least one cheap (CMC ≤ 3) spell. Mulligan\n"
          + "no-land, all-land, and stuck hands. After the first mulligan, lower the bar.\n"
          + "\n"
          + "ATTACK DOCTRINE\n"
          + "When ahead on board: attack everything not needed for defense. When behind:\n"
          + "attack only if you can race or force an unfavorable block. Always count: if you\n"
          + "swing, can the opponent kill you next turn?\n"
          + "\n"
          + "When in doubt, prefer the option that keeps the most future options open.\n"
          + "Always provide brief reasoning (1-3 sentences) before committing to a choice.";

    /**
     * Build a user prompt for a spell selection decision.
     */
    public static String spellSelection(String gameState, String options) {
        return gameState + "\nChoose a spell or ability to play, or PASS (last option).\n"
                + "If you have mana available and a good play, prefer acting over passing.\n\n"
                + options;
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
     */
    public static String batchAttack(String gameState, String attackOptions) {
        return gameState + "\nDECLARE ATTACKERS\n" + attackOptions
                + "\nReturn the indices of the creatures that should attack.";
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
        return gameState + "\nDECLARE BLOCKERS\n" + blockOptions
                + "\nReturn an array of {attacker, blockers[]} entries (gang-blocking allowed).";
    }

    /**
     * Build a user prompt for a batched scry/surveil decision.
     */
    public static String batchScry(String gameState, String scryOptions) {
        return gameState + "\nSCRY/SURVEIL\n" + scryOptions
                + "\nReturn the indices of cards to keep on TOP of your library.";
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
     * Build a prompt for picking multiple items in one batch call.
     */
    public static String batchPick(String gameState, String options, String context,
                                    int min, int max) {
        return gameState + "\n" + context
                + "\nPick between " + min + " and " + max + " items. "
                + "Return the indices of the items you choose.\n\n" + options;
    }

    /**
     * Build a prompt for the MAIN-phase plan batching.
     * The LLM returns an ordered list of spell indices to execute in sequence.
     */
    public static String mainPhasePlan(String gameState, String options) {
        return gameState + "\nPLAN YOUR MAIN PHASE.\n"
                + "Return an ordered array of spell indices to cast this phase. "
                + "Empty array = do nothing this phase.\n\n"
                + options;
    }
}
