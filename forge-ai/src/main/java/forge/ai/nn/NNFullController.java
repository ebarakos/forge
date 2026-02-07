package forge.ai.nn;

import com.google.common.collect.ListMultimap;
import forge.LobbyPlayer;
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
import forge.game.player.PlayerActionConfirmMode;
import forge.game.zone.PlayerZone;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * NN Full controller: routes nearly every decision method through the
 * {@link NNBridge}. Methods that are purely informational (reveal, notify)
 * or that require complex engine interaction (mana payment, sideboarding,
 * combat damage assignment) are left to the heuristic parent
 * {@link PlayerControllerAi}.
 *
 * <p>Helper methods collapse most overrides into a few patterns:
 * <ul>
 *   <li>{@link #chooseFromCards(CardCollectionView, DecisionType)} — pick one card from a list</li>
 *   <li>{@link #chooseFromEntities(FCollectionView, DecisionType, boolean)} — pick one entity</li>
 *   <li>{@link #chooseBoolean(DecisionType)} — binary yes/no</li>
 *   <li>{@link #chooseNumberNN(DecisionType, int, int)} — pick a number in range</li>
 * </ul>
 */
public class NNFullController extends PlayerControllerAi {

    private final NNBridge bridge;
    private final TrainingDataWriter dataWriter; // nullable

    public NNFullController(Game game, Player p, LobbyPlayer lp,
                             NNBridge bridge, TrainingDataWriter dataWriter) {
        super(game, p, lp);
        this.bridge = bridge;
        this.dataWriter = dataWriter;
    }

    // =======================================================================
    // Helper methods
    // =======================================================================

    private int callBridgeWithLogging(DecisionType type, float[] state,
                                       float[][] options, int numOptions) {
        if (numOptions <= 0) {
            return 0;
        }
        int chosen = bridge.chooseOption(state, type.ordinal(), options, numOptions);
        chosen = Math.max(0, Math.min(chosen, numOptions - 1));

        if (dataWriter != null) {
            forge.game.phase.PhaseType phase = getGame().getPhaseHandler().getPhase();
            dataWriter.recordDecision(
                    getGame().getPhaseHandler().getTurn(),
                    phase != null ? phase.toString() : "NONE",
                    type, state, options, numOptions, chosen);
        }
        return chosen;
    }

    /** Pick one card from a card collection. Returns null if empty. */
    private Card chooseFromCards(CardCollectionView cards, DecisionType type) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        if (cards.size() == 1) {
            return cards.get(0);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = OptionEncoder.encodeCards(cards);
        int chosen = callBridgeWithLogging(type, state, optFeatures, cards.size());
        return cards.get(chosen);
    }

    /** Pick one entity from a collection. Returns null if empty or if optional and "none" is chosen. */
    private <T extends GameEntity> T chooseFromEntities(FCollectionView<T> entities,
                                                         DecisionType type, boolean isOptional) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }
        if (entities.size() == 1 && !isOptional) {
            return entities.get(0);
        }

        int entityCount = entities.size();
        int numOpts = isOptional ? entityCount + 1 : entityCount;
        numOpts = Math.min(numOpts, NNConstants.MAX_OPTIONS);
        int encodeCount = Math.min(entityCount, numOpts);

        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
        for (int i = 0; i < encodeCount; i++) {
            T entity = entities.get(i);
            if (entity instanceof Card) {
                float[] cf = GameStateEncoder.encodeCard((Card) entity);
                System.arraycopy(cf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
            } else {
                optFeatures[i][0] = (float) (i + 1) / numOpts;
            }
        }
        // If optional, the last slot is "choose nothing" (zeros)

        int chosen = callBridgeWithLogging(type, state, optFeatures, numOpts);
        if (isOptional && chosen >= entityCount) {
            return null;
        }
        if (chosen >= entityCount) {
            chosen = entityCount - 1;
        }
        return entities.get(chosen);
    }

    /** Binary yes/no decision. Returns true for "yes" (index 0). */
    private boolean chooseBoolean(DecisionType type) {
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] opts = OptionEncoder.encodeBooleanChoice();
        int chosen = callBridgeWithLogging(type, state, opts, 2);
        return chosen == 0;
    }

    /** Choose a number in [min, max] via the NN. */
    private int chooseNumberNN(DecisionType type, int min, int max) {
        if (min == max) {
            return min;
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] opts = OptionEncoder.encodeNumberRange(min, max);
        int chosen = callBridgeWithLogging(type, state, opts, max - min + 1);
        return min + chosen;
    }

    /** Pick one SpellAbility from a list. */
    private SpellAbility chooseFromSpellAbilities(List<SpellAbility> sas, DecisionType type) {
        if (sas == null || sas.isEmpty()) {
            return null;
        }
        if (sas.size() == 1) {
            return sas.get(0);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = OptionEncoder.encodeSpellAbilities(sas);
        int chosen = callBridgeWithLogging(type, state, optFeatures, sas.size());
        return sas.get(chosen);
    }

    /**
     * Choose multiple cards from a collection. Picks one at a time.
     * If isOptional and count < min, returns what was chosen.
     */
    private CardCollection chooseMultipleCards(CardCollectionView sourceList, int min, int max,
                                               boolean isOptional, DecisionType type) {
        CardCollection result = new CardCollection();
        CardCollection remaining = new CardCollection(sourceList);

        for (int i = 0; i < max && !remaining.isEmpty(); i++) {
            boolean canStop = isOptional || result.size() >= min;

            int numOpts = canStop ? remaining.size() + 1 : remaining.size();
            numOpts = Math.min(numOpts, NNConstants.MAX_OPTIONS);
            int encodeCount = Math.min(remaining.size(), numOpts);

            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
            for (int j = 0; j < encodeCount; j++) {
                float[] cf = GameStateEncoder.encodeCard(remaining.get(j));
                System.arraycopy(cf, 0, optFeatures[j], 0, NNConstants.CARD_FEATURES);
            }

            int chosen = callBridgeWithLogging(type, state, optFeatures, numOpts);
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

    /** Choose one item from a generic list by index. */
    private <T> T chooseFromGenericList(List<T> options, DecisionType type) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = new float[options.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < options.size(); i++) {
            T item = options.get(i);
            if (item instanceof Card) {
                float[] cf = GameStateEncoder.encodeCard((Card) item);
                System.arraycopy(cf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
            } else if (item instanceof SpellAbility) {
                Card host = ((SpellAbility) item).getHostCard();
                if (host != null) {
                    float[] cf = GameStateEncoder.encodeCard(host);
                    System.arraycopy(cf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
                } else {
                    optFeatures[i][0] = (float) (i + 1) / options.size();
                }
            } else {
                optFeatures[i][0] = (float) (i + 1) / options.size();
            }
        }
        int chosen = callBridgeWithLogging(type, state, optFeatures, options.size());
        return options.get(chosen);
    }

    /** Choose one string from a list by index. */
    private String chooseFromStringList(List<String> options, DecisionType type) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = new float[options.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < options.size(); i++) {
            optFeatures[i][0] = (float) (i + 1) / options.size();
        }
        int chosen = callBridgeWithLogging(type, state, optFeatures, options.size());
        return options.get(chosen);
    }

    // =======================================================================
    // Spell selection
    // =======================================================================

    /**
     * TODO: Full NN spell selection requires enumerating candidate spells.
     * Currently delegates to the heuristic parent.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return super.chooseSpellAbilityToPlay();
    }

    // =======================================================================
    // Mulligan decisions
    // =======================================================================

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return chooseBoolean(DecisionType.MULLIGAN);
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        if (hand.size() <= cardsToReturn) {
            return CardCollection.getView(hand);
        }
        return CardCollection.getView(chooseMultipleCards(hand, cardsToReturn, cardsToReturn,
                false, DecisionType.CARD_CHOICE));
    }

    @Override
    public boolean confirmMulliganScry(Player p) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

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

        CardCollection canAttack = new CardCollection();
        for (Card c : potentialAttackers) {
            if (CombatUtil.canAttack(c, defaultDefender)) {
                canAttack.add(c);
            }
        }
        if (canAttack.isEmpty()) {
            return;
        }

        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        for (Card c : canAttack) {
            float[][] options = new float[2][NNConstants.CARD_FEATURES];
            float[] cardFeatures = GameStateEncoder.encodeCard(c);
            System.arraycopy(cardFeatures, 0, options[0], 0, NNConstants.CARD_FEATURES);
            // options[1] = don't attack (zeros)
            int chosen = callBridgeWithLogging(DecisionType.ATTACK, state, options, 2);
            if (chosen == 0) {
                combat.addAttacker(c, defaultDefender);
            }
        }

        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            super.declareAttackers(attacker, combat);
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

        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        for (Card att : attackers) {
            List<Card> blockOptions = new ArrayList<>();
            for (Card b : availableBlockers) {
                if (CombatUtil.canBlock(att, b, combat)) {
                    blockOptions.add(b);
                }
            }
            if (blockOptions.isEmpty()) {
                continue;
            }

            int numOpts = blockOptions.size() + 1; // +1 for no-block
            float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
            for (int i = 0; i < blockOptions.size(); i++) {
                float[] bf = GameStateEncoder.encodeCard(blockOptions.get(i));
                System.arraycopy(bf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
            }

            int chosen = callBridgeWithLogging(DecisionType.BLOCK, state, optFeatures, numOpts);
            chosen = Math.max(0, Math.min(chosen, numOpts - 1));

            if (chosen < blockOptions.size()) {
                Card blocker = blockOptions.get(chosen);
                combat.addBlocker(att, blocker);
                availableBlockers.remove(blocker);
            }
        }
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        // For each attacker, decide whether to exert
        List<Card> exerted = new ArrayList<>();
        for (Card c : attackers) {
            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] options = new float[2][NNConstants.CARD_FEATURES];
            float[] cf = GameStateEncoder.encodeCard(c);
            System.arraycopy(cf, 0, options[0], 0, NNConstants.CARD_FEATURES);
            int chosen = callBridgeWithLogging(DecisionType.BOOLEAN, state, options, 2);
            if (chosen == 0) {
                exerted.add(c);
            }
        }
        return exerted;
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        // Delegate to heuristic — enlist logic is complex with cost evaluation
        return super.enlistAttackers(attackers);
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        // Ordering is a permutation problem — delegate to heuristic
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
        return chooseFromEntities(optionList, DecisionType.CARD_CHOICE, isOptional);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(
            FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
            SpellAbility sa, String title, Player targetedPlayer, Map<String, Object> params) {
        if (delayedReveal != null) {
            reveal(delayedReveal);
        }

        List<T> remaining = new ArrayList<>();
        for (T e : optionList) {
            remaining.add(e);
        }
        List<T> selected = new ArrayList<>();
        for (int i = 0; i < max && !remaining.isEmpty(); i++) {
            boolean canStop = selected.size() >= min;
            // Build a temp FCollectionView
            forge.util.collect.FCollection<T> fc = new forge.util.collect.FCollection<>(remaining);
            T choice = chooseFromEntities(fc, DecisionType.CARD_CHOICE, canStop);
            if (choice == null) {
                break;
            }
            selected.add(choice);
            remaining.remove(choice);
        }
        return selected;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                    String title, Map<String, Object> params) {
        return chooseFromSpellAbilities(spells, DecisionType.CARD_CHOICE);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa,
                                                             String title, int num, Map<String, Object> params) {
        List<SpellAbility> remaining = new ArrayList<>(spells);
        List<SpellAbility> selected = new ArrayList<>();
        for (int i = 0; i < num && !remaining.isEmpty(); i++) {
            SpellAbility choice = chooseFromSpellAbilities(remaining, DecisionType.CARD_CHOICE);
            if (choice == null) {
                break;
            }
            selected.add(choice);
            remaining.remove(choice);
        }
        return selected;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max,
                                                           CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max,
                                                         CardCollectionView validTargets, String message) {
        return chooseMultipleCards(validTargets, min, max, min == 0, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa,
                                                    String title, int min, int max,
                                                    boolean isOptional, Map<String, Object> params) {
        return chooseMultipleCards(sourceList, min, max, isOptional, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap,
                                                        SpellAbility sa, String title, boolean isOptional) {
        CardCollection choices = new CardCollection();
        for (Map.Entry<String, CardCollection> entry : validMap.entrySet()) {
            CardCollection cc = new CardCollection(entry.getValue());
            cc.removeAll(choices);
            if (!cc.isEmpty()) {
                Card chosen = chooseFromCards(cc, DecisionType.CARD_CHOICE);
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
        return chooseMultipleCards(validCards, min, max, false, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int num, CardCollectionView hand,
                                                              String param, SpellAbility sa) {
        return chooseMultipleCards(hand, 1, num, false, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollection chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));
        return chooseMultipleCards(hand, numDiscard, numDiscard, false, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        return chooseMultipleCards(grave, 0, genericAmount, true, DecisionType.CARD_CHOICE);
    }

    @Override
    public CardCollection chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        return chooseMultipleCards(valid, min, max, min == 0, DecisionType.CARD_CHOICE);
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

        // If optional, add "none" option
        int numOpts = isOptional ? fetchList.size() + 1 : fetchList.size();
        numOpts = Math.min(numOpts, NNConstants.MAX_OPTIONS);

        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
        int encodeCount = Math.min(fetchList.size(), numOpts);
        for (int i = 0; i < encodeCount; i++) {
            float[] cf = GameStateEncoder.encodeCard(fetchList.get(i));
            System.arraycopy(cf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
        }
        int chosen = callBridgeWithLogging(DecisionType.CARD_CHOICE, state, optFeatures, numOpts);
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
        return new ArrayList<>(chooseMultipleCards(fetchList, min, max, min == 0, DecisionType.CARD_CHOICE));
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone,
                                                   SpellAbility source) {
        // Ordering is a permutation — delegate to heuristic for now
        return super.orderMoveToZoneList(cards, destinationZone, source);
    }

    // =======================================================================
    // Boolean / confirm decisions
    // =======================================================================

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message,
                                  List<String> options, Card cardToShow, Map<String, Object> params) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode mode, String string,
                                     int bid, Player winner) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA,
                                             GameEntity affected, String question) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode,
                                             String message, String logic) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        if (wrapper.isMandatory()) {
            return true;
        }
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String prompt, SpellAbility sa) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
                                 Boolean defaultChoice) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice,
                                 Map<String, Object> params) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean[] results, boolean call) {
        // Coin flip: just pick randomly — no meaningful NN input
        return super.chooseFlipResult(sa, flipper, results, call);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2,
                                    String faceUp) {
        return chooseBoolean(DecisionType.BOOLEAN);
    }

    // =======================================================================
    // Number choices
    // =======================================================================

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        return chooseNumberNN(DecisionType.NUMBER, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max,
                             Map<String, Object> params) {
        return chooseNumberNN(DecisionType.NUMBER, min, max);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values,
                             Player relatedPlayer) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] optFeatures = new float[values.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < values.size(); i++) {
            optFeatures[i][0] = (float) values.get(i) / 20.0f; // normalize
        }
        int chosen = callBridgeWithLogging(DecisionType.NUMBER, state, optFeatures, values.size());
        return values.get(chosen);
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return chooseNumberNN(DecisionType.NUMBER, min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, forge.game.cost.Cost cost,
                                           KeywordInterface keyword, String prompt, int max) {
        return chooseNumberNN(DecisionType.NUMBER, 0, max);
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, String announce) {
        // Delegate to heuristic — announcement logic is API-specific
        return super.announceRequirements(ability, announce);
    }

    // =======================================================================
    // Scry, Surveil
    // =======================================================================

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        CardCollection toTop = new CardCollection();
        CardCollection toBottom = new CardCollection();
        for (Card c : topN) {
            // Binary: top or bottom
            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] opts = new float[2][NNConstants.CARD_FEATURES];
            float[] cf = GameStateEncoder.encodeCard(c);
            System.arraycopy(cf, 0, opts[0], 0, NNConstants.CARD_FEATURES);
            // opts[0] = top (card features), opts[1] = bottom (zeros)
            int chosen = callBridgeWithLogging(DecisionType.BOOLEAN, state, opts, 2);
            if (chosen == 0) {
                toTop.add(c);
            } else {
                toBottom.add(c);
            }
        }
        return ImmutablePair.of(toTop, toBottom);
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        CardCollection toTop = new CardCollection();
        CardCollection toGraveyard = new CardCollection();
        for (Card c : topN) {
            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] opts = new float[2][NNConstants.CARD_FEATURES];
            float[] cf = GameStateEncoder.encodeCard(c);
            System.arraycopy(cf, 0, opts[0], 0, NNConstants.CARD_FEATURES);
            int chosen = callBridgeWithLogging(DecisionType.BOOLEAN, state, opts, 2);
            if (chosen == 0) {
                toTop.add(c);
            } else {
                toGraveyard.add(c);
            }
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
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color c : colors) {
            colorList.add(c);
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] opts = new float[colorList.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < colorList.size(); i++) {
            opts[i][0] = (float) (i + 1) / colorList.size();
        }
        int chosen = callBridgeWithLogging(DecisionType.GENERIC, state, opts, colorList.size());
        return colorList.get(chosen).getColorMask();
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        List<MagicColor.Color> colorList = new ArrayList<>();
        for (MagicColor.Color col : colors) {
            colorList.add(col);
        }
        if (colorList.isEmpty()) {
            return MagicColor.Color.COLORLESS.getColorMask();
        }
        if (colorList.size() == 1) {
            return colorList.get(0).getColorMask();
        }
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] opts = new float[colorList.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < colorList.size(); i++) {
            opts[i][0] = (float) (i + 1) / colorList.size();
        }
        int chosen = callBridgeWithLogging(DecisionType.GENERIC, state, opts, colorList.size());
        return colorList.get(chosen).getColorMask();
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        // Delegate to heuristic — multi-color selection is complex
        return super.chooseColors(message, sa, min, max, options);
    }

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes,
                                  boolean isOptional) {
        List<String> types = new ArrayList<>(validTypes);
        if (types.isEmpty()) {
            return "";
        }
        String result = chooseFromStringList(types, DecisionType.GENERIC);
        return result != null ? result : types.get(0);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        String result = chooseFromStringList(sectors, DecisionType.GENERIC);
        return result != null ? result : sectors.get(0);
    }

    @Override
    public int chooseSprocket(Card assignee, boolean forceDifferent) {
        // Delegate to heuristic — sprocket logic is game-specific
        return super.chooseSprocket(assignee, forceDifferent);
    }

    @Override
    public String chooseProtectionType(String string, SpellAbility sa, List<String> choices) {
        String result = chooseFromStringList(choices, DecisionType.GENERIC);
        return result != null ? result : choices.get(0);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt,
                                        Card tgtCard) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        String result = chooseFromStringList(options, DecisionType.GENERIC);
        return result != null ? result : options.get(0);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt,
                                          Map<String, Object> params) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        if (options.size() == 1) {
            return options.get(0);
        }
        return chooseFromGenericList(options, DecisionType.GENERIC);
    }

    // =======================================================================
    // Card face / state choices
    // =======================================================================

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        if (faces.size() == 1) {
            return faces.get(0);
        }
        return chooseFromGenericList(faces, DecisionType.GENERIC);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message,
                                            Map<String, Object> params) {
        if (states == null || states.isEmpty()) {
            return null;
        }
        if (states.size() == 1) {
            return states.get(0);
        }
        return chooseFromGenericList(states, DecisionType.GENERIC);
    }

    // =======================================================================
    // Replacement effects / static abilities
    // =======================================================================

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers == null || possibleReplacers.size() <= 1) {
            return super.chooseSingleReplacementEffect(possibleReplacers);
        }
        return chooseFromGenericList(possibleReplacers, DecisionType.GENERIC);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(String prompt, List<StaticAbility> possibleStatics) {
        if (possibleStatics == null || possibleStatics.size() <= 1) {
            return super.chooseSingleStaticAbility(prompt, possibleStatics);
        }
        return chooseFromGenericList(possibleStatics, DecisionType.GENERIC);
    }

    // =======================================================================
    // Mode / optional cost choices
    // =======================================================================

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible,
                                                   int min, int num, boolean allowRepeat) {
        // Mode selection is complex with order dependency — delegate to heuristic
        return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility chosen,
                                                        List<OptionalCostValue> optionalCostValues) {
        // Delegate to heuristic — cost evaluation requires payability checks
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
        return chooseFromGenericList(rolls, DecisionType.GENERIC);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        return chooseFromGenericList(rolls, DecisionType.GENERIC);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        // Each die: binary reroll or keep
        List<Integer> toReroll = new ArrayList<>();
        for (Integer roll : rolls) {
            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] opts = OptionEncoder.encodeBooleanChoice();
            int chosen = callBridgeWithLogging(DecisionType.BOOLEAN, state, opts, 2);
            if (chosen == 0) {
                toReroll.add(roll);
            }
        }
        return toReroll;
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        return chooseFromGenericList(rolls, DecisionType.GENERIC);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        return chooseFromGenericList(rolls, DecisionType.GENERIC);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult,
                                       int power, int toughness) {
        return chooseFromStringList(swapChoices, DecisionType.GENERIC);
    }

    // =======================================================================
    // Voting
    // =======================================================================

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options,
                        ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        if (options == null || options.isEmpty()) {
            return null;
        }
        return chooseFromGenericList(options, DecisionType.GENERIC);
    }

    // =======================================================================
    // Targeting
    // =======================================================================

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(
            SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        return chooseFromGenericList(allTargets, DecisionType.CARD_CHOICE);
    }

    // =======================================================================
    // Starting player / hand
    // =======================================================================

    @Override
    public Player chooseStartingPlayer(boolean isFirstgame) {
        return chooseBoolean(DecisionType.BOOLEAN) ? getPlayer() : getPlayer();
        // AI always chooses self — can't easily encode opponent player as option
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        return chooseFromGenericList(zones, DecisionType.GENERIC);
    }

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        // Delegate to heuristic — opening hand logic is specialized
        return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    // =======================================================================
    // Mana choices
    // =======================================================================

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        return chooseFromGenericList(manaChoices, DecisionType.GENERIC);
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount,
                                                boolean different) {
        // Delegate to heuristic — mana combination logic is complex
        return super.specifyManaCombo(sa, colorSet, manaAmount, different);
    }

    // =======================================================================
    // Contraptions
    // =======================================================================

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        // For each contraption, binary crank/don't-crank
        List<Card> toCrank = new ArrayList<>();
        for (Card c : contraptions) {
            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] opts = new float[2][NNConstants.CARD_FEATURES];
            float[] cf = GameStateEncoder.encodeCard(c);
            System.arraycopy(cf, 0, opts[0], 0, NNConstants.CARD_FEATURES);
            int chosen = callBridgeWithLogging(DecisionType.BOOLEAN, state, opts, 2);
            if (chosen == 0) {
                toCrank.add(c);
            }
        }
        return toCrank;
    }

    // =======================================================================
    // Splice
    // =======================================================================

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        // Delegate to heuristic — splice cost/play evaluation is complex
        return super.chooseCardsForSplice(sa, cards);
    }

    // =======================================================================
    // Card name choices
    // =======================================================================

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        // Delegate to heuristic — naming requires game knowledge beyond state encoding
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
        // Delegate to heuristic — convoke requires mana cost analysis
        return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    // =======================================================================
    // Methods left to heuristic parent (info / reveal / payment / complex logic)
    // =======================================================================
    // The following are NOT overridden and use PlayerControllerAi's implementation:
    //
    // - reveal() variants: purely informational
    // - notifyOfValue(): informational
    // - tempShowCards() / endTempShowCards(): UI
    // - autoPassCancel() / awaitNextInput() / cancelAwaitNextInput(): control flow
    // - resetInputs(): control flow
    // - resetAtEndOfTurn(): memory cleanup
    // - playSpellAbilityNoStack(): complex engine interaction
    // - orderAndPlaySimultaneousSa(): complex ordering + play
    // - playTrigger() / playSaFromPlayEffect(): complex play logic
    // - playChosenSpellAbility(): play logic
    // - chooseTargetsFor(): target assignment (modifies SA)
    // - chooseNewTargetsFor(): target retargeting
    // - sideboard(): deck construction
    // - chooseCardsYouWonToAddToDeck(): deck construction
    // - assignCombatDamage(): damage distribution
    // - divideShield(): shield distribution
    // - payManaCost(): mana payment
    // - payCombatCost(): combat cost payment
    // - payCostToPreventEffect(): cost evaluation
    // - payCostDuringRoll(): roll cost
    // - helpPayForAssistSpell(): assist payment
    // - choosePlayerToAssistPayment(): player selection for assist
    // - complainCardsCantPlayWell() / cheatShuffle(): AI-specific
    // - revealAnte() / revealAISkipCards() / revealUnsupported(): informational
    // - getAbilityToPlay(): trigger event handling
    // - chooseSingleCardFace(predicate): throws UnsupportedOperationException in parent

    /**
     * Record the game outcome and close the training data writer.
     * Called by SimulateMatch after each game ends.
     */
    public void finishGame(boolean won, int turns, String reason) {
        if (dataWriter != null) {
            dataWriter.recordOutcome(won ? 1.0f : 0.0f, turns, reason);
            dataWriter.close();
        }
    }
}
