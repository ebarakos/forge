package forge.ai.llm;

import com.google.common.collect.ListMultimap;
import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.PlanarDice;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardState;
import forge.game.card.CounterType;
import forge.game.combat.Combat;
import forge.game.cost.CostPart;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.zone.PlayerZone;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * LLM Full controller: routes nearly every decision method through the
 * {@link LLMClient}. On any LLM failure (timeout, parse error, budget
 * exceeded), falls back to the heuristic parent {@link PlayerControllerAi}.
 */
public class LLMFullController extends PlayerControllerAi {

    /** Package-private so {@link LLMSpellSelection} and {@link LLMCombat} can call it. */
    final LLMClient client;

    /** Sliding window of recent action summaries for LLM context. */
    private final List<String> actionHistory = new ArrayList<>();
    // 6 entries = roughly 2 turns of action; older history rarely informs the
    // current decision (board state already reflects it) and just costs tokens.
    private static final int MAX_HISTORY = 6;

    /** B2: blocking-plan note carried over from the most recent attack LLM call.
     *  Package-private so {@link LLMCombat} can read/clear it. */
    String lastAttackPlan = "";

    /** B1: cached sequence of card names to cast this MAIN phase (from a single plan LLM call).
     *  Package-private so {@link LLMSpellSelection} can mutate it. */
    final java.util.Deque<String> mainPhasePlan = new java.util.ArrayDeque<>();
    PhaseType planPhase = null;
    int planTurn = -1;
    int planStackSize = 0;

    /** D1: cached serialized game state (stable anchor for the current turn+phase). */
    private String cachedGameState = null;
    private int cachedStateTurn = -1;
    private PhaseType cachedStatePhase = null;

    /**
     * Per-option eval hints (PR3.c) — opt-in via {@code FORGE_LLM_EVAL_HINTS=true}.
     * Off by default because building a fresh {@link forge.ai.simulation.GameSimulator}
     * per candidate is expensive on the current GameCopier; PR4 makes this cheap.
     * Capped at {@link #EVAL_HINT_MAX_CANDIDATES} to bound CPU when the hand is wide.
     */
    static final boolean EVAL_HINTS_ENABLED = isTruthy(System.getenv("FORGE_LLM_EVAL_HINTS"));
    static final int EVAL_HINT_MAX_CANDIDATES = 6;

    /**
     * Heuristic-prior pruning: cap the number of spell options shown to the
     * LLM and sort heuristic-preferred candidates first. Set
     * {@code FORGE_LLM_TOPK=0} to disable; defaults to 8. Lower = cheaper
     * prompts + tighter LLM attention; higher = more freedom for the LLM to
     * disagree with the heuristic.
     */
    static final int HEURISTIC_TOPK = parseIntEnv("FORGE_LLM_TOPK", 8);

    /**
     * Heuristic-prior verdict annotation: tag each option with the heuristic
     * AI's own opinion (WillPlay / WaitForMain2 / Removal / …). Off by default
     * because tags add ~10 tokens per option; flip on for tuning runs.
     * Pruning of hopeless candidates still happens regardless of this flag.
     */
    static final boolean VERDICT_TAGS_ENABLED = isTruthy(System.getenv("FORGE_LLM_VERDICT_TAGS"));

    /**
     * Shadow mode: log heuristic vs LLM divergence per call to stderr without
     * changing behaviour. Useful for offline analysis from mtg-discovery
     * transcripts. Opt-in via {@code FORGE_LLM_SHADOW=1}.
     */
    static final boolean SHADOW_MODE = isTruthy(System.getenv("FORGE_LLM_SHADOW"));

    /**
     * Set true around heuristic feasibility checks (canPayCost, validateAndSetTargets)
     * so cost-paying callbacks like {@link #chooseCardsToDelve} can short-circuit
     * with a heuristic pick instead of burning an LLM call. Per-thread because the
     * engine is not multi-threaded per match but parallel matches each run on their
     * own controller.
     */
    static final ThreadLocal<Boolean> IN_FEASIBILITY = ThreadLocal.withInitial(() -> false);

    /** Spell-selection delegate (heuristic prior, plan caching, choose ability). */
    private final LLMSpellSelection spellSelection;

    /** Combat delegate (declare attackers/blockers with heuristic baselines). */
    private final LLMCombat combat;

    private static boolean isTruthy(String v) {
        return v != null && !v.isEmpty() && !"false".equalsIgnoreCase(v) && !"0".equals(v);
    }

    private static int parseIntEnv(String name, int fallback) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) return fallback;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    public LLMFullController(Game game, Player p, LobbyPlayer lp, LLMClient client) {
        super(game, p, lp);
        this.client = client;
        this.spellSelection = new LLMSpellSelection(this);
        this.combat = new LLMCombat(this);
    }

    /** Record an action summary for the sliding window history. */
    void recordAction(String summary) {
        actionHistory.add(summary);
        if (actionHistory.size() > MAX_HISTORY) {
            actionHistory.remove(0);
        }
    }

    /** Format the action history section for inclusion in prompts. */
    private String getActionHistoryText() {
        if (actionHistory.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\nRECENT ACTIONS:\n");
        for (String action : actionHistory) {
            sb.append("- ").append(action).append('\n');
        }
        return sb.toString();
    }

    /**
     * Build the game state text with action history appended.
     * D1: Caches the serialized game state per (turn, phase). Multiple priority
     * calls in the same phase reuse the same state string, which both skips
     * re-serialization CPU and gives the provider prompt cache a byte-stable
     * prefix across consecutive calls. Action history is appended volatile.
     */
    String buildGameStateWithHistory() {
        int currentTurn = getGame().getPhaseHandler().getTurn();
        PhaseType currentPhase = getGame().getPhaseHandler().getPhase();
        if (cachedGameState == null || cachedStateTurn != currentTurn
                || cachedStatePhase != currentPhase) {
            cachedGameState = GameStateSerializer.serializeGameState(getPlayer(), getGame());
            cachedStateTurn = currentTurn;
            cachedStatePhase = currentPhase;
        }
        return cachedGameState + getActionHistoryText();
    }

    // =======================================================================
    // Instant-speed decision check
    // =======================================================================

    /**
     * Check if the LLM should be called for an instant-speed decision.
     * Returns true when: there's a spell on the stack (counterspell opportunity)
     * or it's the opponent's combat phase (combat trick opportunity),
     * AND the player has affordable instant-speed spells in hand.
     */
    boolean shouldCallLLMForInstantSpeed() {
        try {
            boolean hasStackTarget = !getGame().getStack().isEmpty();
            PhaseType phase = getGame().getPhaseHandler().getPhase();
            // A3: dropped COMBAT_DECLARE_ATTACKERS — we can't usefully respond
            // before knowing who's attacking. Wait until DECLARE_BLOCKERS / damage.
            boolean isOpponentCombat = !getGame().getPhaseHandler().isPlayerTurn(getPlayer())
                    && (phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                        || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                        || phase == PhaseType.COMBAT_DAMAGE);

            if (!hasStackTarget && !isOpponentCombat) {
                return false;
            }

            // A3: combat-trick filter — skip when there are no actual attackers.
            if (isOpponentCombat) {
                Combat combat = getGame().getCombat();
                if (combat == null || combat.getAttackers().isEmpty()) {
                    return false;
                }
            }

            // A3: stack/combat response filter — only fire when at least one
            // affordable candidate can actually be cast right now (cost paid AND
            // targeting valid). This catches cases where we hold an instant but
            // it has no legal target on the current stack/board.
            CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), getPlayer());
            List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, getPlayer());
            IN_FEASIBILITY.set(true);
            try {
                for (SpellAbility sa : candidates) {
                    if (sa.isManaAbility() || sa.isLandAbility()) continue;
                    sa.setActivatingPlayer(getPlayer());
                    if (!sa.canCastTiming(getPlayer())) continue;
                    if (!ComputerUtilCost.canPayCost(sa, getPlayer(), sa.isTrigger())) continue;
                    if (validateAndSetTargets(sa)) {
                        return true;
                    }
                }
            } finally {
                IN_FEASIBILITY.set(false);
            }
            return false;
        } catch (Exception e) {
            IN_FEASIBILITY.set(false);
            return false;
        }
    }

    // =======================================================================
    // Core LLM call helper
    // =======================================================================

    /**
     * Call the LLM with game state, options, and context.
     * Returns the chosen option index, or -1 on failure (caller falls back to heuristic).
     */
    int callLLM(String userPrompt, int numOptions, String callLabel) {
        try {
            String response = client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, userPrompt,
                    callLabel, getPlayer().getName(),
                    LLMResponseSchema.CHOICE);
            int choice = ResponseParser.parseChoiceIndex(response, numOptions);
            if (choice < 0) {
                if (client.isDebug()) {
                    System.err.println("[LLM FALLBACK] " + callLabel + ": parse failed");
                }
                client.recordFallback();
                return -1;
            }
            return choice;
        } catch (Exception e) {
            if (client.isDebug()) {
                System.err.println("[LLM FALLBACK] " + callLabel + ": " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
            }
            client.recordFallback();
            return -1;
        }
    }

    /**
     * Call the LLM and return the raw response text (not parsed as a single index).
     * Returns null on failure.
     */
    String callLLMRaw(String userPrompt, String callLabel) {
        return callLLMRaw(userPrompt, callLabel, LLMResponseSchema.INDICES);
    }

    /** Schema-aware variant for batch calls. */
    String callLLMRaw(String userPrompt, String callLabel, LLMResponseSchema schema) {
        try {
            return client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, userPrompt,
                    callLabel, getPlayer().getName(), schema);
        } catch (Exception e) {
            if (client.isDebug()) {
                System.err.println("[LLM FALLBACK] " + callLabel + ": " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
            }
            client.recordFallback();
            return null;
        }
    }

    /**
     * Use the heuristic AI's targeting logic to set up targets on the given SA.
     * Returns true if targeting succeeded (or no targeting is needed).
     */
    boolean validateAndSetTargets(SpellAbility sa) {
        if (!sa.usesTargeting()) {
            return true;
        }

        try {
            sa.setActivatingPlayer(getPlayer());
            SpellAbility root = sa.getRootAbility();
            if (root.isSpell() || root.isTrigger() || root.isReplacementAbility()) {
                sa.setLastStateBattlefield(getGame().getLastStateBattlefield());
                sa.setLastStateGraveyard(getGame().getLastStateGraveyard());
            }

            AiPlayDecision decision = getAi().canPlaySa(sa);
            sa.clearLastState();

            if (decision == AiPlayDecision.WillPlay) {
                return true;
            }
            if (decision == AiPlayDecision.TargetingFailed) {
                return false;
            }
            // Strategic refusal — targets may have been set as a side effect
            return sa.isTargetNumberValid();
        } catch (Exception e) {
            sa.clearLastState();
            if (client.isDebug()) {
                System.err.println("[LLM] validateAndSetTargets failed: " + e.getMessage());
            }
            return false;
        }
    }

    // =======================================================================
    // Generic helpers (mirror NNFullController patterns)
    // =======================================================================

    private Card chooseFromCards(CardCollectionView cards, String context) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        if (cards.size() == 1) {
            return cards.get(0);
        }
        List<Card> cardList = new ArrayList<>();
        for (Card c : cards) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, false);
        String prompt = PromptTemplates.chooseCard(gameState, options, context);
        int chosen = callLLM(prompt, cards.size(), "chooseCard");
        if (chosen < 0) {
            return null;
        }
        return cards.get(chosen);
    }

    private <T extends GameEntity> T chooseFromEntities(FCollectionView<T> entities,
                                                          String context, boolean isOptional) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        if (entities.size() == 1 && !isOptional) {
            return entities.get(0);
        }

        List<T> entityList = new ArrayList<>();
        for (T e : entities) { entityList.add(e); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeEntityOptions(entityList, isOptional);
        int numOpts = isOptional ? entityList.size() + 1 : entityList.size();
        String prompt = PromptTemplates.chooseCard(gameState, options, context);
        int chosen = callLLM(prompt, numOpts, "chooseEntity");
        if (chosen < 0) {
            return null;
        }
        if (isOptional && chosen >= entityList.size()) {
            return null;
        }
        if (chosen >= entityList.size()) {
            chosen = entityList.size() - 1;
        }
        return entityList.get(chosen);
    }

    private int chooseNumberLLM(int min, int max, String context) {
        if (min == max) {
            return min;
        }
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeNumberOptions(min, max);
        String prompt = PromptTemplates.numberChoice(gameState, options, context);
        int chosen = callLLM(prompt, max - min + 1, "number");
        if (chosen < 0) {
            return min;
        }
        return min + chosen;
    }

    private SpellAbility chooseFromSpellAbilities(List<SpellAbility> sas, String context) {
        if (sas == null || sas.isEmpty()) {
            return null;
        }
        if (sas.size() == 1) {
            return sas.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeGenericOptions(sas);
        String prompt = PromptTemplates.genericChoice(gameState, options, context);
        int chosen = callLLM(prompt, sas.size(), "chooseSpellAbility");
        if (chosen < 0) {
            return sas.get(0);
        }
        return sas.get(chosen);
    }

    /**
     * B3: Single-call batched picker for cards. One LLM call returns all picks
     * as comma-separated indices. On any failure, fills from the front to satisfy
     * min and returns.
     */
    private CardCollection chooseMultipleCards(CardCollectionView sourceList, int min, int max,
                                                boolean isOptional, String context) {
        CardCollection result = new CardCollection();
        if (sourceList == null || sourceList.isEmpty()) return result;

        int effectiveMin = isOptional ? 0 : min;
        int effectiveMax = Math.min(max, sourceList.size());

        // Short-circuit: forced to take everything.
        if (effectiveMin >= sourceList.size()) {
            for (Card c : sourceList) result.add(c);
            return result;
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : sourceList) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, false);
        String prompt = PromptTemplates.batchPick(gameState, options, context, effectiveMin, effectiveMax);
        String response = callLLMRaw(prompt, "chooseMultipleCardsBatched");

        if (response == null) {
            fillFromFront(result, cardList, effectiveMin);
            return result;
        }
        Set<Integer> picks = ResponseParser.parseBatchIndices(response, cardList.size());
        // Honour max
        int taken = 0;
        for (int idx : picks) {
            if (taken >= effectiveMax) break;
            result.add(cardList.get(idx));
            taken++;
        }
        // Honour min — fill from front with items not already picked
        if (result.size() < effectiveMin) {
            for (Card c : cardList) {
                if (result.size() >= effectiveMin) break;
                if (!result.contains(c)) result.add(c);
            }
        }
        return result;
    }

    /** B3 fallback: fill {@code result} from the front of {@code source} until at least {@code min}. */
    private void fillFromFront(CardCollection result, List<Card> source, int min) {
        for (Card c : source) {
            if (result.size() >= min) break;
            result.add(c);
        }
    }

    /**
     * B3: Batched picker for generic entities (Cards or Players, etc). One LLM call.
     */
    private <T extends GameEntity> List<T> chooseMultipleEntitiesBatched(List<T> sourceList,
                                                                          int min, int max, String context) {
        List<T> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) return result;

        int effectiveMax = Math.min(max, sourceList.size());
        if (min >= sourceList.size()) {
            result.addAll(sourceList);
            return result;
        }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeEntityOptions(sourceList, false);
        String prompt = PromptTemplates.batchPick(gameState, options, context, min, effectiveMax);
        String response = callLLMRaw(prompt, "chooseMultipleEntitiesBatched");

        if (response == null) {
            for (int i = 0; i < min && i < sourceList.size(); i++) result.add(sourceList.get(i));
            return result;
        }
        Set<Integer> picks = ResponseParser.parseBatchIndices(response, sourceList.size());
        int taken = 0;
        for (int idx : picks) {
            if (taken >= effectiveMax) break;
            result.add(sourceList.get(idx));
            taken++;
        }
        if (result.size() < min) {
            for (T e : sourceList) {
                if (result.size() >= min) break;
                if (!result.contains(e)) result.add(e);
            }
        }
        return result;
    }

    /**
     * B3: Batched picker for spell abilities. One LLM call.
     */
    private List<SpellAbility> chooseMultipleSpellAbilitiesBatched(List<SpellAbility> sourceList,
                                                                    int num, String context) {
        List<SpellAbility> result = new ArrayList<>();
        if (sourceList == null || sourceList.isEmpty()) return result;
        int take = Math.min(num, sourceList.size());
        if (take == sourceList.size()) {
            result.addAll(sourceList);
            return result;
        }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeGenericOptions(sourceList);
        String prompt = PromptTemplates.batchPick(gameState, options, context, take, take);
        String response = callLLMRaw(prompt, "chooseMultipleSAsBatched");

        if (response == null) {
            for (int i = 0; i < take; i++) result.add(sourceList.get(i));
            return result;
        }
        Set<Integer> picks = ResponseParser.parseBatchIndices(response, sourceList.size());
        int taken = 0;
        for (int idx : picks) {
            if (taken >= take) break;
            result.add(sourceList.get(idx));
            taken++;
        }
        if (result.size() < take) {
            for (SpellAbility sa : sourceList) {
                if (result.size() >= take) break;
                if (!result.contains(sa)) result.add(sa);
            }
        }
        return result;
    }

    private <T> T chooseFromGenericList(List<T> options, String context) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String optText = OptionSerializer.serializeGenericOptions(options);
        String prompt = PromptTemplates.genericChoice(gameState, optText, context);
        int chosen = callLLM(prompt, options.size(), "genericChoice");
        if (chosen < 0) {
            return options.get(0);
        }
        return options.get(chosen);
    }

    private String chooseFromStringList(List<String> options, String context) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        String gameState = buildGameStateWithHistory();
        String optText = OptionSerializer.serializeStringOptions(options);
        String prompt = PromptTemplates.genericChoice(gameState, optText, context);
        int chosen = callLLM(prompt, options.size(), "stringChoice");
        if (chosen < 0) {
            return options.get(0);
        }
        return options.get(chosen);
    }

    // =======================================================================
    // Spell selection
    // =======================================================================

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return spellSelection.chooseSpellAbilityToPlay();
    }

    /** Default heuristic spell selection — exposed so {@link LLMSpellSelection} can fall back to it. */
    List<SpellAbility> defaultChooseSpellAbilityToPlay() {
        return super.chooseSpellAbilityToPlay();
    }


    // =======================================================================
    // Mulligan
    // =======================================================================

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        // A4: Auto-keep obvious 7-card hands. 2-4 lands + ≥1 cheap spell is a
        // standard "keep" for any reasonable player; no need to spend an LLM call.
        CardCollectionView hand = getPlayer().getCardsIn(ZoneType.Hand);
        if (hand.size() == 7 && isObviousKeep(hand)) {
            recordAction("Auto-kept opening hand");
            return true;
        }

        String gameState = buildGameStateWithHistory();
        String prompt = PromptTemplates.mulligan(gameState);
        int chosen = callLLM(prompt, 2, "mulligan");
        if (chosen < 0) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        boolean keep = chosen == 0;
        recordAction(keep ? "Kept opening hand" : "Mulliganed");
        return keep;
    }

    private boolean isObviousKeep(CardCollectionView hand) {
        int lands = 0, cheapSpells = 0;
        for (Card c : hand) {
            if (c.isLand()) lands++;
            else if (c.getCMC() <= 3) cheapSpells++;
        }
        return lands >= 2 && lands <= 4 && cheapSpells >= 1;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        if (hand.size() <= cardsToReturn) {
            return CardCollection.getView(hand);
        }
        CardCollection chosen = chooseMultipleCards(hand, cardsToReturn, cardsToReturn,
                false, "Choose cards to put on bottom for mulligan");
        if (chosen.size() < cardsToReturn) {
            return super.tuckCardsViaMulligan(mulliganingPlayer, cardsToReturn);
        }
        return CardCollection.getView(chosen);
    }

    // confirmMulliganScry: delegated to heuristic (always scries, not worth an LLM call)

    // =======================================================================
    // Combat
    // =======================================================================

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        this.combat.declareAttackers(attacker, combat);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        this.combat.declareBlockers(defender, combat);
    }

    /** Default heuristic attackers — exposed so {@link LLMCombat} can fall back to it. */
    void defaultDeclareAttackers(Player attacker, Combat combat) {
        super.declareAttackers(attacker, combat);
    }

    /** Default heuristic blockers — exposed so {@link LLMCombat} can fall back to it. */
    void defaultDeclareBlockers(Player defender, Combat combat) {
        super.declareBlockers(defender, combat);
    }

    // exertAttackers: delegated to heuristic (combat-phase decision, handled by AI logic)

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        return super.enlistAttackers(attackers);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        return super.orderBlockers(attacker, blockers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        return super.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        return super.orderAttackers(blocker, attackers);
    }

    // =======================================================================
    // Card choice / targeting
    // =======================================================================

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(
            FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional,
            Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        T result = chooseFromEntities(optionList, title, isOptional);
        if (result == null && !isOptional && optionList != null && !optionList.isEmpty()) {
            return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, targetedPlayer, params);
        }
        return result;
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(
            FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
            SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (optionList == null || optionList.isEmpty()) {
            return new ArrayList<>();
        }

        // B3: single batched LLM call instead of per-pick loop.
        List<T> sourceList = new ArrayList<>();
        for (T e : optionList) { sourceList.add(e); }
        List<T> selected = chooseMultipleEntitiesBatched(sourceList, min, max, title);

        if (selected.size() < min) {
            return super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, targetedPlayer, params);
        }
        return selected;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                    String title, Map<String, Object> params) {
        SpellAbility result = chooseFromSpellAbilities(spells, title);
        return result != null ? result : super.chooseSingleSpellForEffect(spells, sa, title, params);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                             String title, int num, Map<String, Object> params) {
        // B3: single batched LLM call.
        List<SpellAbility> selected = chooseMultipleSpellAbilitiesBatched(spells, num, title);
        if (selected.isEmpty()) {
            return super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        }
        return selected;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                           CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, "Choose permanents to sacrifice: " + message);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                         CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, "Choose permanents to destroy: " + message);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
                                                    String title, int min, int max,
                                                    boolean isOptional, Map<String, Object> params) {
        return chooseMultipleCards(sourceList, min, max, isOptional, title);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
                                                        SpellAbility sa, String title, boolean isOptional) {
        CardCollection choices = new CardCollection();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            CardCollection cc = new CardCollection(entry.getValue());
            cc.removeAll(choices);
            if (!cc.isEmpty()) {
                Card chosen = chooseFromCards(cc, entry.getKey());
                if (chosen != null) {
                    choices.add(chosen);
                }
            }
        }
        return choices;
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa,
                                                    CardCollection validCards, int min, int max) {
        return chooseMultipleCards(validCards, min, max, false, "Choose cards to discard");
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand,
                                                              String param, SpellAbility sa) {
        return chooseMultipleCards(hand, 1, num, false, "Choose cards to discard");
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        return chooseMultipleCards(hand, numDiscard, numDiscard, false, "Discard to hand size");
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        // During heuristic feasibility checks (canPayCost / canCastTiming for
        // every delve card in hand) the engine asks "can you pay?" — the LLM
        // shouldn't burn a call answering it. ~22% of all calls in transcripts
        // were speculative delve prompts where the spell was never cast. Use
        // the heuristic pick (cheapest cards first by simple grave order) for
        // feasibility; the real LLM call still fires when the spell actually
        // resolves and the choice matters.
        if (IN_FEASIBILITY.get()) {
            int take = Math.min(genericAmount, grave.size());
            CardCollection picked = new CardCollection();
            for (int i = 0; i < take; i++) picked.add(grave.get(i));
            return picked;
        }
        return chooseMultipleCards(grave, 0, genericAmount, true, "Choose cards to delve");
    }

    @Override
    public CardCollection chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return chooseMultipleCards(valid, min, max, min == 0, "Choose cards to reveal");
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin,
                                               SpellAbility sa, CardCollection fetchList,
                                               DelayedReveal delayedReveal, String selectPrompt,
                                               boolean isOptional, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (fetchList == null || fetchList.isEmpty()) {
            return null;
        }
        if (fetchList.size() == 1 && !isOptional) {
            return fetchList.get(0);
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : fetchList) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeCardOptions(cardList, isOptional);
        int numOpts = isOptional ? fetchList.size() + 1 : fetchList.size();
        String prompt = PromptTemplates.chooseCard(gameState, options, selectPrompt);
        int chosen = callLLM(prompt, numOpts, "chooseCardForZone");

        if (chosen < 0) {
            return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        }
        if (isOptional && chosen >= fetchList.size()) {
            return null;
        }
        if (chosen >= fetchList.size()) {
            chosen = fetchList.size() - 1;
        }
        return fetchList.get(chosen);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin,
                                                SpellAbility sa, CardCollection fetchList,
                                                int min, int max, DelayedReveal delayedReveal,
                                                String selectPrompt, Player decider) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }
        if (fetchList == null || fetchList.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(chooseMultipleCards(fetchList, min, max, min == 0, selectPrompt));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                                                   SpellAbility source) {
        return super.orderMoveToZoneList(cards, destinationZone, source);
    }

    // =======================================================================
    // Boolean / confirm decisions
    // =======================================================================

    // confirmAction, confirmBidAction, confirmReplacementEffect, confirmStaticApplication,
    // confirmTrigger, confirmPayment: all delegated to heuristic AI (inherited from
    // PlayerControllerAi). These are secondary decisions where the heuristic's doTrigger()
    // and specialized logic outperforms LLM reasoning, especially for slow local models
    // where each call costs 15-30s. LLM value is in strategic spell/combat decisions.

    // chooseBinary, willPutCardOnTop: delegated to heuristic (same rationale as above)

    // chooseCardsPile: delegated to heuristic

    // =======================================================================
    // Number choices
    // =======================================================================

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return chooseNumberLLM(min, max, title);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max,
                             Map<String, Object> params) {
        return chooseNumberLLM(min, max, title);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values,
                             Player relatedPlayer) {
        if (values == null || values.isEmpty()) { return 0; }
        if (values.size() == 1) { return values.get(0); }
        Integer result = chooseFromGenericList(values, title);
        return result != null ? result : values.get(0);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return chooseNumberLLM(min, max, "Choose cost reduction amount");
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost,
                                           KeywordInterface keyword, String prompt, int max) {
        return chooseNumberLLM(0, max, prompt);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, String announce) {
        return super.announceRequirements(ability, announce);
    }

    // =======================================================================
    // Scry, Surveil
    // =======================================================================

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        if (topN.size() <= 1) {
            // Single card: one LLM call with top/bottom choice
            CardCollection toTop = new CardCollection();
            CardCollection toBottom = new CardCollection();
            String gameState = buildGameStateWithHistory();
            String option = OptionSerializer.serializeScryOption(topN.get(0), false);
            String prompt = PromptTemplates.scry(gameState, option);
            int chosen = callLLM(prompt, 2, "scry");
            if (chosen == 0) { toTop.add(topN.get(0)); } else { toBottom.add(topN.get(0)); }
            return ImmutablePair.of(toTop, toBottom);
        }

        // Batch: single LLM call for all cards
        List<Card> cardList = new ArrayList<>();
        for (Card c : topN) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchScryOptions(cardList, false);
        String prompt = PromptTemplates.batchScry(gameState, options);
        String response = callLLMRaw(prompt, "scry");

        CardCollection toTop = new CardCollection();
        CardCollection toBottom = new CardCollection();
        if (response == null) {
            return super.arrangeForScry(topN);
        }

        Set<Integer> keepOnTop = ResponseParser.parseBatchIndices(response, cardList.size());
        for (int i = 0; i < cardList.size(); i++) {
            if (keepOnTop.contains(i)) { toTop.add(cardList.get(i)); }
            else { toBottom.add(cardList.get(i)); }
        }
        return ImmutablePair.of(toTop, toBottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        if (topN.size() <= 1) {
            CardCollection toTop = new CardCollection();
            CardCollection toGraveyard = new CardCollection();
            String gameState = buildGameStateWithHistory();
            String option = OptionSerializer.serializeScryOption(topN.get(0), true);
            String prompt = PromptTemplates.scry(gameState, option);
            int chosen = callLLM(prompt, 2, "surveil");
            if (chosen == 0) { toTop.add(topN.get(0)); } else { toGraveyard.add(topN.get(0)); }
            return ImmutablePair.of(toTop, toGraveyard);
        }

        List<Card> cardList = new ArrayList<>();
        for (Card c : topN) { cardList.add(c); }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchScryOptions(cardList, true);
        String prompt = PromptTemplates.batchScry(gameState, options);
        String response = callLLMRaw(prompt, "surveil");

        CardCollection toTop = new CardCollection();
        CardCollection toGraveyard = new CardCollection();
        if (response == null) {
            return super.arrangeForSurveil(topN);
        }

        Set<Integer> keepOnTop = ResponseParser.parseBatchIndices(response, cardList.size());
        for (int i = 0; i < cardList.size(); i++) {
            if (keepOnTop.contains(i)) { toTop.add(cardList.get(i)); }
            else { toGraveyard.add(cardList.get(i)); }
        }
        return ImmutablePair.of(toTop, toGraveyard);
    }

    // =======================================================================
    // Color / type choices
    // =======================================================================

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        if (colors.countColors() <= 1) {
            return super.chooseColor(message, sa, colors);
        }
        List<String> colorNames = new ArrayList<>();
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color c : colors) {
            colorList.add(c);
            colorNames.add(c.toString());
        }
        String result = chooseFromStringList(colorNames, message);
        if (result == null) {
            return super.chooseColor(message, sa, colors);
        }
        int idx = colorNames.indexOf(result);
        return colorList.get(idx >= 0 ? idx : 0).getColorMask();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        List<String> colorNames = new ArrayList<>();
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color col : colors) {
            colorList.add(col);
            colorNames.add(col.toString());
        }
        if (colorList.isEmpty()) {
            return MagicColor.Color.COLORLESS.getColorMask();
        }
        if (colorList.size() == 1) {
            return colorList.get(0).getColorMask();
        }
        String result = chooseFromStringList(colorNames, message);
        if (result == null) {
            return colorList.get(0).getColorMask();
        }
        int idx = colorNames.indexOf(result);
        return colorList.get(idx >= 0 ? idx : 0).getColorMask();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        return super.chooseColors(message, sa, min, max, options);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
                                  boolean isOptional) {
        List<String> types = new ArrayList<>(validTypes);
        if (types.isEmpty()) { return ""; }
        // Generic prompt-size guard: when valid type list is huge (e.g. ALL ~200
        // creature types from cards like Distant Melody / Cavern of Souls), narrow
        // to types that actually appear on either player's battlefield, hand, or
        // graveyard. The relevant choice is almost always one of those. Fall back
        // to the full list if nothing matches.
        if (types.size() > 30 && "Creature".equalsIgnoreCase(kindOfType)) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Player p : getGame().getPlayers()) {
                for (forge.game.zone.ZoneType z : new forge.game.zone.ZoneType[]{
                        forge.game.zone.ZoneType.Battlefield,
                        forge.game.zone.ZoneType.Hand,
                        forge.game.zone.ZoneType.Graveyard}) {
                    for (Card c : p.getCardsIn(z)) {
                        for (String t : c.getType().getCreatureTypes()) seen.add(t);
                    }
                }
            }
            List<String> filtered = new ArrayList<>();
            for (String t : types) if (seen.contains(t)) filtered.add(t);
            if (!filtered.isEmpty()) types = filtered;
        }
        String result = chooseFromStringList(types, "Choose a " + kindOfType);
        return result != null ? result : types.get(0);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        String result = chooseFromStringList(sectors, "Choose sector");
        return result != null ? result : sectors.get(0);
    }

    @Override
    public int chooseSprocket(Card assignee, boolean forceDifferent) {
        return super.chooseSprocket(assignee, forceDifferent);
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        String result = chooseFromStringList(choices, "Choose protection type");
        return result != null ? result : choices.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
                                        Card tgtCard) {
        if (options == null || options.isEmpty()) { return null; }
        String result = chooseFromStringList(options, prompt);
        return result != null ? result : options.get(0);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
                                          Map<String, Object> params) {
        if (options == null || options.isEmpty()) { return null; }
        if (options.size() == 1) { return options.get(0); }
        return chooseFromGenericList(options, prompt);
    }

    // =======================================================================
    // Card face / state choices
    // =======================================================================

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces == null || faces.isEmpty()) { return null; }
        if (faces.size() == 1) { return faces.get(0); }
        return chooseFromGenericList(faces, message);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
                                            Map<String, Object> params) {
        if (states == null || states.isEmpty()) { return null; }
        if (states.size() == 1) { return states.get(0); }
        return chooseFromGenericList(states, message);
    }

    // =======================================================================
    // Replacement effects / static abilities
    // =======================================================================

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.size() <= 1) {
            return super.chooseSingleReplacementEffect(possibleReplacers);
        }
        return chooseFromGenericList(possibleReplacers, "Choose replacement effect");
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleStatics) {
        if (possibleStatics == null || possibleStatics.size() <= 1) {
            return super.chooseSingleStaticAbility(prompt, possibleStatics);
        }
        return chooseFromGenericList(possibleStatics, prompt);
    }

    // =======================================================================
    // Mode / optional cost choices
    // =======================================================================

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                   int min, int num, boolean allowRepeat) {
        return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                        List<OptionalCostValue> optionalCostValues) {
        return super.chooseOptionalCosts(chosen, optionalCostValues);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        return super.orderCosts(costs);
    }

    // =======================================================================
    // Dice / planar dice
    // =======================================================================

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to ignore");
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to ignore");
    }

    // chooseDiceToReroll: delegated to heuristic

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to modify");
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return chooseFromGenericList(rolls, "Choose roll to swap");
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult,
                                       int power, int toughness) {
        return chooseFromStringList(swapChoices, "Choose swap value");
    }

    // =======================================================================
    // Voting
    // =======================================================================

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                        ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        if (options == null || options.isEmpty()) { return null; }
        return chooseFromGenericList(options, prompt);
    }

    // =======================================================================
    // Targeting
    // =======================================================================

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(
            SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return chooseFromGenericList(allTargets, "Choose target");
    }

    // =======================================================================
    // Starting player / hand
    // =======================================================================

    @Override
    public Player chooseStartingPlayer(boolean isFirstgame) {
        // Always choose to play first
        return getPlayer();
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return chooseFromGenericList(zones, "Choose starting hand");
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    // =======================================================================
    // Mana choices
    // =======================================================================

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return chooseFromGenericList(manaChoices, "Choose mana from pool");
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
                                                boolean different) {
        return super.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    // =======================================================================
    // Contraptions
    // =======================================================================

    // chooseContraptionsToCrank: delegated to heuristic

    // =======================================================================
    // Splice
    // =======================================================================

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        return super.chooseCardsForSplice(sa, cards);
    }

    // =======================================================================
    // Card name choices
    // =======================================================================

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        return super.chooseCardName(sa, faces, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid,
                                  String message) {
        return super.chooseCardName(sa, cpp, valid, message);
    }

    // =======================================================================
    // Convoke / Improvise
    // =======================================================================

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa,
                                                                      forge.card.mana.ManaCost manaCost,
                                                                      CardCollectionView untappedCards,
                                                                      boolean artifacts, boolean creatures,
                                                                      Integer maxReduction) {
        return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    // =======================================================================
    // Stats access
    // =======================================================================

    public LLMClient getClient() {
        return client;
    }
}
