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
import forge.game.combat.CombatUtil;
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

    private final LLMClient client;
    private volatile boolean budgetExceeded = false;

    /** Sliding window of recent action summaries for LLM context. */
    private final List<String> actionHistory = new ArrayList<>();
    private static final int MAX_HISTORY = 10;

    public LLMFullController(Game game, Player p, LobbyPlayer lp, LLMClient client) {
        super(game, p, lp);
        this.client = client;
    }

    /** Record an action summary for the sliding window history. */
    private void recordAction(String summary) {
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

    /** Build the game state text with action history appended. */
    private String buildGameStateWithHistory() {
        return GameStateSerializer.serializeGameState(getPlayer(), getGame())
                + getActionHistoryText();
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
    private boolean shouldCallLLMForInstantSpeed() {
        try {
            // Check if there's a reason to respond at instant speed
            boolean hasStackTarget = !getGame().getStack().isEmpty();
            PhaseType phase = getGame().getPhaseHandler().getPhase();
            boolean isOpponentCombat = !getGame().getPhaseHandler().isPlayerTurn(getPlayer())
                    && (phase == PhaseType.COMBAT_DECLARE_ATTACKERS
                        || phase == PhaseType.COMBAT_DECLARE_BLOCKERS
                        || phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE
                        || phase == PhaseType.COMBAT_DAMAGE);

            if (!hasStackTarget && !isOpponentCombat) {
                return false;
            }

            // Check if we have any affordable instant-speed spells
            CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), getPlayer());
            List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, getPlayer());
            for (SpellAbility sa : candidates) {
                if (sa.isManaAbility() || sa.isLandAbility()) continue;
                sa.setActivatingPlayer(getPlayer());
                if (ComputerUtilCost.canPayCost(sa, getPlayer(), sa.isTrigger())) {
                    return true; // At least one playable instant-speed spell
                }
            }
            return false;
        } catch (Exception e) {
            return false; // On any error, delegate to heuristic
        }
    }

    // =======================================================================
    // Core LLM call helper
    // =======================================================================

    /**
     * Call the LLM with game state, options, and context.
     * Returns the chosen option index, or -1 on failure (caller falls back to heuristic).
     */
    private int callLLM(String userPrompt, int numOptions, String callLabel) {
        if (budgetExceeded) {
            return -1;
        }
        try {
            String response = client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, userPrompt,
                    callLabel, getPlayer().getName());
            int choice = ResponseParser.parseChoiceIndex(response, numOptions);
            if (choice < 0) {
                if (client.isDebug()) {
                    System.err.println("[LLM FALLBACK] " + callLabel + ": parse failed");
                }
                return -1;
            }
            return choice;
        } catch (BudgetExceededException e) {
            budgetExceeded = true;
            return -1;
        } catch (Exception e) {
            if (e instanceof BudgetExceededException) {
                budgetExceeded = true;
            }
            if (client.isDebug()) {
                System.err.println("[LLM FALLBACK] " + callLabel + ": " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
            }
            return -1;
        }
    }

    /**
     * Call the LLM and return the raw response text (not parsed as a single index).
     * Returns null on failure.
     */
    private String callLLMRaw(String userPrompt, String callLabel) {
        if (budgetExceeded) {
            return null;
        }
        try {
            return client.chatCompletion(
                    PromptTemplates.SYSTEM_PROMPT, userPrompt,
                    callLabel, getPlayer().getName());
        } catch (BudgetExceededException e) {
            budgetExceeded = true;
            return null;
        } catch (Exception e) {
            if (client.isDebug()) {
                System.err.println("[LLM FALLBACK] " + callLabel + ": " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Use the heuristic AI's targeting logic to set up targets on the given SA.
     * Returns true if targeting succeeded (or no targeting is needed).
     */
    private boolean validateAndSetTargets(SpellAbility sa) {
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

    private CardCollection chooseMultipleCards(CardCollectionView sourceList, int min, int max,
                                                boolean isOptional, String context) {
        CardCollection result = new CardCollection();
        CardCollection remaining = new CardCollection(sourceList);

        for (int i = 0; i < max && !remaining.isEmpty(); i++) {
            boolean canStop = isOptional || result.size() >= min;

            List<Card> cardList = new ArrayList<>();
            for (Card c : remaining) { cardList.add(c); }

            String gameState = buildGameStateWithHistory();
            String options = OptionSerializer.serializeCardOptions(cardList, canStop);
            int numOpts = canStop ? remaining.size() + 1 : remaining.size();
            String chooseContext = context + " (pick " + (i + 1) + ", need " + min + "-" + max + ")";
            String prompt = PromptTemplates.chooseCard(gameState, options, chooseContext);
            int chosen = callLLM(prompt, numOpts, "chooseMultipleCards");

            if (chosen < 0) {
                // Fallback: fill remaining picks from front
                while (result.size() < min && !remaining.isEmpty()) {
                    result.add(remaining.get(0));
                    remaining.remove(0);
                }
                break;
            }
            if (canStop && chosen >= remaining.size()) {
                break; // chose "done"
            }
            if (chosen >= remaining.size()) {
                chosen = remaining.size() - 1;
            }
            result.add(remaining.get(chosen));
            remaining.remove(chosen);
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
        // Use LLM for MAIN phases always; for non-MAIN phases, only when there's
        // a meaningful instant-speed decision (e.g., counterspell opportunity, combat trick)
        PhaseType phase = getGame().getPhaseHandler().getPhase();
        if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2) {
            if (!shouldCallLLMForInstantSpeed()) {
                return super.chooseSpellAbilityToPlay();
            }
            // Fall through to normal spell selection — getSpellAbilities() already
            // filters to only instant-speed playable spells during non-MAIN phases
        }

        // Delegate land drops to heuristic (no benefit from LLM deciding which basic to play),
        // but ONLY the land drop — don't let heuristic also choose spells.
        // After the land resolves, the engine loops and calls us again for spell decisions.
        CardCollection landsWannaPlay = ComputerUtilAbility.getAvailableLandsToPlay(getGame(), getPlayer());
        if (landsWannaPlay != null && !landsWannaPlay.isEmpty()) {
            List<SpellAbility> heuristicChoice = super.chooseSpellAbilityToPlay();
            if (heuristicChoice != null && !heuristicChoice.isEmpty()
                    && heuristicChoice.get(0).isLandAbility()) {
                return heuristicChoice; // Play just the land; engine will call us again for spells
            }
            // Heuristic decided not to play a land (e.g., holding for MAIN2) — fall through to LLM
        }

        CardCollection cards = ComputerUtilAbility.getAvailableCards(getGame(), getPlayer());
        List<SpellAbility> candidates = ComputerUtilAbility.getSpellAbilities(cards, getPlayer());

        List<SpellAbility> playable = new ArrayList<>();
        for (SpellAbility sa : candidates) {
            // Filter out mana abilities and land abilities — engine handles these automatically
            if (sa.isManaAbility() || sa.isLandAbility()) {
                continue;
            }
            sa.setActivatingPlayer(getPlayer());
            try {
                if (!ComputerUtilCost.canPayCost(sa, getPlayer(), sa.isTrigger())) {
                    continue;
                }
                // Pre-validate targeting: only show spells that can actually target
                if (!validateAndSetTargets(sa)) {
                    continue;
                }
                playable.add(sa);
            } catch (Exception e) {
                // Skip
            }
        }

        if (playable.isEmpty()) {
            return null;
        }

        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeSpellOptions(playable);
        String prompt = PromptTemplates.spellSelection(gameState, options);
        int totalOptions = playable.size() + 1; // +1 for PASS
        int chosen = callLLM(prompt, totalOptions, "chooseSpellAbilityToPlay");

        if (chosen < 0) {
            return super.chooseSpellAbilityToPlay();
        }
        if (chosen >= playable.size()) {
            return null; // PASS
        }

        SpellAbility selectedSa = playable.get(chosen);

        // Record action for sliding window history
        String phaseName = phase != null ? phase.toString() : "MAIN";
        int turn = getGame().getPhaseHandler().getTurn();
        Card host = selectedSa.getHostCard();
        String spellName = host != null ? host.getName() : selectedSa.toString();
        StringBuilder actionSb = new StringBuilder();
        actionSb.append("Turn ").append(turn).append(' ').append(phaseName)
                .append(": Cast ").append(spellName);
        // Include target info if available
        if (selectedSa.usesTargeting() && selectedSa.getTargets() != null
                && !selectedSa.getTargets().isEmpty()) {
            actionSb.append(" targeting ");
            boolean first = true;
            for (Card tc : selectedSa.getTargets().getTargetCards()) {
                if (!first) actionSb.append(", ");
                first = false;
                actionSb.append(tc.getName());
            }
            for (Player tp : selectedSa.getTargets().getTargetPlayers()) {
                if (!first) actionSb.append(", ");
                first = false;
                actionSb.append(tp.getName());
            }
        }
        recordAction(actionSb.toString());

        List<SpellAbility> result = new ArrayList<>();
        result.add(selectedSa);
        return result;
    }

    // =======================================================================
    // Mulligan
    // =======================================================================

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
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
        CardCollection potentialAttackers = attacker.getCreaturesInPlay();
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
        GameEntity defaultDefender = defenders.isEmpty() ? null : defenders.get(0);

        if (potentialAttackers.isEmpty() || defaultDefender == null) {
            return;
        }

        List<Card> canAttack = new ArrayList<>();
        for (Card c : potentialAttackers) {
            if (CombatUtil.canAttack(c, defaultDefender)) {
                canAttack.add(c);
            }
        }
        if (canAttack.isEmpty()) {
            return;
        }

        // Single batched LLM call for all attack decisions
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchAttackOptions(canAttack);
        String prompt = PromptTemplates.batchAttack(gameState, options);
        String response = callLLMRaw(prompt, "declareAttackers");

        if (response == null) {
            // LLM failure — fall back to heuristic attack logic
            super.declareAttackers(attacker, combat);
            return;
        }

        Set<Integer> attackIndices = ResponseParser.parseBatchIndices(response, canAttack.size());
        for (int idx : attackIndices) {
            combat.addAttacker(canAttack.get(idx), defaultDefender);
        }

        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            super.declareAttackers(attacker, combat);
        }

        // Record attack action
        if (!attackIndices.isEmpty()) {
            int turn = getGame().getPhaseHandler().getTurn();
            StringBuilder actionSb = new StringBuilder();
            actionSb.append("Turn ").append(turn).append(" COMBAT: Attacked with ");
            boolean first = true;
            for (int idx : attackIndices) {
                if (!first) actionSb.append(", ");
                first = false;
                Card c = canAttack.get(idx);
                actionSb.append(c.getName());
                if (c.isCreature()) {
                    actionSb.append(" (").append(c.getNetPower()).append('/')
                            .append(c.getNetToughness()).append(')');
                }
            }
            recordAction(actionSb.toString());
        }
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        CardCollection attackers = combat.getAttackers();
        if (attackers.isEmpty()) {
            return;
        }

        CardCollection potentialBlockers = defender.getCreaturesInPlay();
        CardCollection availableBlockers = new CardCollection();
        for (Card b : potentialBlockers) {
            if (CombatUtil.canBlock(b, combat)) {
                availableBlockers.add(b);
            }
        }
        if (availableBlockers.isEmpty()) {
            return;
        }

        // Build lists of attackers that have at least one legal blocker
        List<Card> attackerList = new ArrayList<>();
        for (Card att : attackers) {
            for (Card b : availableBlockers) {
                if (CombatUtil.canBlock(att, b, combat)) {
                    attackerList.add(att);
                    break;
                }
            }
        }
        if (attackerList.isEmpty()) {
            return;
        }

        List<Card> blockerList = new ArrayList<>(availableBlockers);

        // Single batched LLM call for all block assignments
        String gameState = buildGameStateWithHistory();
        String options = OptionSerializer.serializeBatchBlockOptions(attackerList, blockerList);
        String prompt = PromptTemplates.batchBlock(gameState, options);
        String response = callLLMRaw(prompt, "declareBlockers");

        if (response == null) {
            super.declareBlockers(defender, combat);
            return;
        }

        Map<Integer, Set<Integer>> assignments = ResponseParser.parseBatchBlockAssignments(
                response, attackerList.size(), blockerList.size());

        for (Map.Entry<Integer, Set<Integer>> entry : assignments.entrySet()) {
            int attIdx = entry.getKey();
            if (attIdx < 0 || attIdx >= attackerList.size()) continue;
            Card att = attackerList.get(attIdx);
            for (int bIdx : entry.getValue()) {
                if (bIdx < 0 || bIdx >= blockerList.size()) continue;
                Card blocker = blockerList.get(bIdx);
                if (CombatUtil.canBlock(att, blocker, combat)) {
                    combat.addBlocker(att, blocker);
                }
            }
        }

        // Record block action
        if (!assignments.isEmpty()) {
            int turn = getGame().getPhaseHandler().getTurn();
            StringBuilder actionSb = new StringBuilder();
            actionSb.append("Turn ").append(turn).append(" COMBAT: Blocked ");
            boolean first = true;
            for (Map.Entry<Integer, Set<Integer>> entry2 : assignments.entrySet()) {
                if (!first) actionSb.append("; ");
                first = false;
                Card att = attackerList.get(entry2.getKey());
                actionSb.append(att.getName()).append(" with ");
                boolean firstB = true;
                for (int bIdx : entry2.getValue()) {
                    if (!firstB) actionSb.append("+");
                    firstB = false;
                    actionSb.append(blockerList.get(bIdx).getName());
                }
            }
            recordAction(actionSb.toString());
        }
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

        List<T> remaining = new ArrayList<>();
        for (T e : optionList) { remaining.add(e); }
        List<T> selected = new ArrayList<>();
        for (int i = 0; i < max && !remaining.isEmpty(); i++) {
            boolean canStop = selected.size() >= min;
            forge.util.collect.FCollection<T> fc = new forge.util.collect.FCollection<>(remaining);
            T choice = chooseFromEntities(fc, title, canStop);
            if (choice == null) { break; }
            selected.add(choice);
            remaining.remove(choice);
        }
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
        List<SpellAbility> remaining = new ArrayList<>(spells);
        List<SpellAbility> selected = new ArrayList<>();
        for (int i = 0; i < num && !remaining.isEmpty(); i++) {
            SpellAbility choice = chooseFromSpellAbilities(remaining, title);
            if (choice == null) { break; }
            selected.add(choice);
            remaining.remove(choice);
        }
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

    public boolean isBudgetExceeded() {
        return budgetExceeded;
    }
}
