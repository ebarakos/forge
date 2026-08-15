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
     * D2: two-part user prompt for provider prompt caching.
     * {@code stablePrefix} holds the byte-stable, append-only part of the
     * prompt (per-decision doctrine + action history) and is sent as a
     * separate content block carrying {@code cache_control: ephemeral};
     * {@code volatileTail} holds the current game state + options and is
     * never cached (the model must always see fresh state). When caching is
     * off, rejected by the provider, or the prefix is empty, the two parts
     * are concatenated into the legacy single-string user message — the
     * model sees identical text either way.
     */
    public static final class PromptParts {
        public final String stablePrefix;
        public final String volatileTail;

        public PromptParts(String stablePrefix, String volatileTail) {
            this.stablePrefix = stablePrefix == null ? "" : stablePrefix;
            this.volatileTail = volatileTail == null ? "" : volatileTail;
        }

        /** Full prompt text as a single string (debug / non-caching fallback). */
        public String concatenated() {
            return stablePrefix + volatileTail;
        }
    }

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
          + "RULES YOU MUST NOT GET WRONG\n"
          + "- Summoning sickness blocks ATTACKING and TAP-ABILITIES only. A summoning-sick\n"
          + "  creature CAN block normally — never refuse a chump block because it's sick.\n"
          + "- A tapped creature cannot block. An attacker becomes tapped after attacking.\n"
          + "- Blockers absorb attacker damage; only UNBLOCKED damage reaches the player.\n"
          + "- Sorceries (no Flash/Instant) can only be cast on your own main phase with empty stack.\n"
          + "- Counterspells must be cast in response to a spell already on the stack.\n"
          + "\n"
          + "When in doubt, prefer the option that keeps the most future options open.\n"
          + "\n"
          + "OUTPUT DISCIPLINE\n"
          + "- Reasoning: at most 2 short sentences, under 30 words total. Then commit.\n"
          + "- Never loop, repeat the same word, pad with ellipses, or write more than 2 sentences.\n"
          + "- If unsure, pick the most sensible option and stop — partial certainty is fine.\n"
          + "- Never produce tool-call markers, channels, or anything outside the JSON schema.";

    /** Mulligan-specific guidance. Appended only to mulligan user prompts. */
    private static final String MULLIGAN_DOCTRINE =
            "MULLIGAN DOCTRINE\n"
          + "Keep 7-card hands with 2-4 lands and at least one cheap (CMC ≤ 3) spell. Mulligan\n"
          + "no-land, all-land, and stuck hands. After the first mulligan, lower the bar.\n";

    /** Attack-specific guidance. Appended only to attack-decision user prompts. */
    private static final String ATTACK_DOCTRINE =
            "ATTACK DOCTRINE\n"
          + "When ahead on board: attack everything not needed for defense. When behind:\n"
          + "attack only if you can race or force an unfavorable block. Always count: if you\n"
          + "swing, can the opponent kill you next turn?\n";

    /**
     * Build a user prompt for a spell selection decision.
     * Phase-aware: at instant speed (non-MAIN) we drop the "prefer acting"
     * push and add an explicit timing reminder, because passing is usually
     * correct outside main phases — we observed gpt-oss-20b casting sorceries
     * in COMBAT_DAMAGE because the prompt biased it toward action.
     */
    public static String spellSelection(String gameState, String options, boolean isMainPhase) {
        StringBuilder sb = new StringBuilder(gameState);
        if (isMainPhase) {
            sb.append("\nChoose a spell or ability to play, or PASS (last option).\n");
            sb.append("If you have mana available and a good play, prefer acting over passing.\n\n");
        } else {
            sb.append("\n[INSTANT SPEED — only flash/instants and activated abilities are listed.]\n");
            sb.append("Choose a spell or ability to play, or PASS (last option).\n");
            sb.append("Passing is usually correct unless you can counter, remove, or set up a combat trick.\n\n");
        }
        sb.append(options);
        return sb.toString();
    }

    /** Backwards-compatible overload — assumes main phase. */
    public static String spellSelection(String gameState, String options) {
        return spellSelection(gameState, options, true);
    }

    /** Backwards-compatible overload — no heuristic prior. */
    public static String mulligan(String gameState) {
        return mulligan(gameState, null);
    }

    /**
     * Build a user prompt for a mulligan decision. When {@code heuristicKeep}
     * is non-null, surfaces the heuristic AI's verdict as a policy prior
     * ("Heuristic baseline: KEEP / MULLIGAN. Diverge only with reason.") —
     * the symmetric counterpart to the attack/block heuristic priors.
     */
    public static String mulligan(String gameState, Boolean heuristicKeep) {
        StringBuilder sb = new StringBuilder(gameState);
        sb.append('\n').append(MULLIGAN_DOCTRINE);
        if (heuristicKeep != null) {
            sb.append("Heuristic baseline: ").append(heuristicKeep ? "KEEP" : "MULLIGAN")
              .append(". Diverge only with reason.\n");
        }
        sb.append("\nDo you want to keep this hand?\n\nOPTIONS:\n0: Keep\n1: Mulligan\n");
        return sb.toString();
    }

    /**
     * Build a user prompt for the play-or-draw decision made before the game
     * starts. There is no board and no hand yet, so the prompt carries no game
     * state — only the choice and what each side of it costs. Used only when
     * {@code FORGE_LLM_HARDCODED_PLAY_FIRST} is off; otherwise the seat plays
     * first without asking.
     */
    public static String startingPlayer(boolean isFirstGame) {
        return "You have won the die roll"
             + (isFirstGame ? "" : " (or lost the previous game)")
             + " and choose who takes the first turn.\n"
             + "Playing first wins the race for the board but skips your first draw step.\n"
             + "Drawing first gives you one extra card, which favours decks that answer\n"
             + "threats rather than deploy them.\n"
             + "\nOPTIONS:\n0: Play first\n1: Draw first\n";
    }

    /**
     * Build a user prompt for an attack decision (per creature).
     */
    public static String attack(String gameState, String attackOption) {
        return gameState + "\n" + ATTACK_DOCTRINE + "\n" + attackOption;
    }

    /**
     * Build a user prompt for batch attack declaration (all creatures at once).
     */
    public static String batchAttack(String gameState, String attackOptions) {
        return gameState + "\n" + ATTACK_DOCTRINE + "\nDECLARE ATTACKERS\n" + attackOptions
                + "\nReturn the indices of the creatures that should attack.";
    }

    /**
     * D2: split-block variant of {@link #batchAttack(String, String)}. The
     * attack doctrine + action history form the byte-stable cached prefix;
     * the game state + options stay in the volatile tail.
     */
    public static PromptParts batchAttack(String actionHistory, String gameState,
                                           String attackOptions) {
        return new PromptParts(
                ATTACK_DOCTRINE + (actionHistory == null ? "" : actionHistory),
                "\n" + gameState + "\nDECLARE ATTACKERS\n" + attackOptions
                        + "\nReturn the indices of the creatures that should attack.");
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
     * D2: split-block variant of {@link #batchBlock(String, String)}. There is
     * no block-specific doctrine constant, so the stable cached prefix is just
     * the action history; the game state (including any carried-over attack
     * plan note, which is volatile) + options stay in the uncached tail.
     */
    public static PromptParts batchBlock(String actionHistory, String gameState,
                                          String blockOptions) {
        return new PromptParts(
                actionHistory,
                "\n" + gameState + "\nDECLARE BLOCKERS\n" + blockOptions
                        + "\nReturn an array of {attacker, blockers[]} entries (gang-blocking allowed).");
    }

    /**
     * Target-specific guidance. Appended only to target-choice prompts.
     *
     * <p>Deliberately even-handed about face damage. An earlier attempt to fix
     * a burn deck's targeting by pushing damage at the opponent's face made it
     * lose considerably more, so this text names the question — does the
     * creature actually cost you the race — instead of naming an answer.
     */
    private static final String TARGET_DOCTRINE =
            "TARGET DOCTRINE\n"
          + "Pick the target that most advances your own plan, not the one that looks biggest.\n"
          + "- Damage or removal at a creature buys time; at the opponent it shortens the game.\n"
          + "  Take the creature when it would otherwise block your attackers, kill you first,\n"
          + "  or generate value every turn; take the opponent when nothing on their board\n"
          + "  changes the race and you can finish before they stabilise.\n"
          + "- Never spend a bigger answer than the threat requires, and never point removal\n"
          + "  at something the creature you already have can handle in combat.\n"
          + "- A pump or protection spell goes on the creature that is actually doing work\n"
          + "  this turn — usually an unblocked attacker or a blocker about to die.\n";

    /**
     * Build a user prompt for choosing the targets of a spell or ability the
     * seat has already decided to play. {@code options} comes from
     * {@link OptionSerializer#serializeTargetOptions}; the answer is read back
     * with {@link LLMResponseSchema#TARGETS}.
     *
     * <p>Split into two parts like the combat prompts: the doctrine and the
     * action history are byte-stable and ride in the cached prefix, while the
     * board and the candidate list — which change every call — stay in the
     * fresh tail.
     */
    public static PromptParts chooseTargets(String actionHistory, String gameState, String options) {
        return new PromptParts(
                TARGET_DOCTRINE + (actionHistory == null ? "" : actionHistory),
                "\n" + gameState + "\n" + options);
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
