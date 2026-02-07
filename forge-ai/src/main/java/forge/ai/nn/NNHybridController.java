package forge.ai.nn;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * NN Hybrid controller: overrides only the 6 most impactful decision methods,
 * delegating everything else to the heuristic {@link PlayerControllerAi}.
 *
 * <p>Overridden methods:
 * <ol>
 *   <li>{@link #chooseSpellAbilityToPlay()} — spell selection (TODO: currently delegates to heuristic)</li>
 *   <li>{@link #mulliganKeepHand(Player, int)} — mulligan decision</li>
 *   <li>{@link #tuckCardsViaMulligan(Player, int)} — London mulligan card selection</li>
 *   <li>{@link #declareAttackers(Player, Combat)} — attack decisions</li>
 *   <li>{@link #declareBlockers(Player, Combat)} — block decisions</li>
 *   <li>{@link #chooseSingleEntityForEffect(FCollectionView, DelayedReveal, SpellAbility, String, boolean, Player, Map)} — targeting</li>
 * </ol>
 */
public class NNHybridController extends PlayerControllerAi {

    private final NNBridge bridge;
    private final TrainingDataWriter dataWriter; // nullable

    public NNHybridController(Game game, Player p, LobbyPlayer lp,
                               NNBridge bridge, TrainingDataWriter dataWriter) {
        super(game, p, lp);
        this.bridge = bridge;
        this.dataWriter = dataWriter;
    }

    // ---------------------------------------------------------------
    // Helper: call bridge and optionally log for training
    // ---------------------------------------------------------------

    private int callBridgeWithLogging(DecisionType type, float[] state,
                                       float[][] options, int numOptions) {
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

    // ---------------------------------------------------------------
    // 1. chooseSpellAbilityToPlay — spell selection
    // ---------------------------------------------------------------

    /**
     * TODO: Full NN spell selection requires enumerating candidate spells
     * (lands, spells from hand, activated abilities) which is tightly coupled
     * to the heuristic AI's cost/payability checks. For now, delegate to
     * the parent heuristic implementation. This will be replaced by NN-driven
     * selection in a future iteration.
     */
    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        return super.chooseSpellAbilityToPlay();
    }

    // ---------------------------------------------------------------
    // 2. mulliganKeepHand — keep or mulligan
    // ---------------------------------------------------------------

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());
        float[][] options = OptionEncoder.encodeBooleanChoice();
        // option 0 = keep, option 1 = mulligan
        int chosen = callBridgeWithLogging(DecisionType.MULLIGAN, state, options, 2);
        return chosen == 0; // 0 = keep
    }

    // ---------------------------------------------------------------
    // 3. tuckCardsViaMulligan — London mulligan: which cards to put back
    // ---------------------------------------------------------------

    @Override
    public CardCollectionView tuckCardsViaMulligan(Player mulliganingPlayer, int cardsToReturn) {
        CardCollection hand = new CardCollection(getPlayer().getCardsIn(ZoneType.Hand));

        if (hand.size() <= cardsToReturn) {
            // Must return all — no meaningful choice
            return CardCollection.getView(hand);
        }

        CardCollection toReturn = new CardCollection();
        // Choose cards one at a time
        for (int i = 0; i < cardsToReturn; i++) {
            CardCollection remaining = new CardCollection(hand);
            remaining.removeAll(toReturn);

            if (remaining.size() <= 1) {
                toReturn.addAll(remaining);
                break;
            }

            float[] state = GameStateEncoder.encode(getPlayer(), getGame());
            float[][] optFeatures = OptionEncoder.encodeCards(remaining);
            int chosen = callBridgeWithLogging(DecisionType.CARD_CHOICE, state,
                    optFeatures, remaining.size());
            chosen = Math.max(0, Math.min(chosen, remaining.size() - 1));
            toReturn.add(remaining.get(chosen));
        }

        return CardCollection.getView(toReturn);
    }

    // ---------------------------------------------------------------
    // 4. declareAttackers — attack decisions
    // ---------------------------------------------------------------

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        CardCollection potentialAttackers = attacker.getCreaturesInPlay();
        List<GameEntity> defenders = new ArrayList<>(combat.getDefenders());
        GameEntity defaultDefender = defenders.isEmpty() ? null : defenders.get(0);

        if (potentialAttackers.isEmpty() || defaultDefender == null) {
            return;
        }

        // Filter to creatures that can legally attack
        CardCollection canAttack = new CardCollection();
        for (Card c : potentialAttackers) {
            if (CombatUtil.canAttack(c, defaultDefender)) {
                canAttack.add(c);
            }
        }

        if (canAttack.isEmpty()) {
            return;
        }

        // For each potential attacker, make a binary attack/don't-attack decision
        float[] state = GameStateEncoder.encode(getPlayer(), getGame());

        for (Card c : canAttack) {
            float[][] options = new float[2][NNConstants.CARD_FEATURES];
            float[] cardFeatures = GameStateEncoder.encodeCard(c);
            // option 0 = attack (features of the card)
            System.arraycopy(cardFeatures, 0, options[0], 0, NNConstants.CARD_FEATURES);
            // option 1 = don't attack (zeros)
            int chosen = callBridgeWithLogging(DecisionType.ATTACK, state, options, 2);
            if (chosen == 0) {
                combat.addAttacker(c, defaultDefender);
            }
        }

        // Validate the attack declaration; fall back to heuristic if invalid
        if (!CombatUtil.validateAttackers(combat)) {
            combat.clearAttackers();
            // Fall back to heuristic AI
            super.declareAttackers(attacker, combat);
        }
    }

    // ---------------------------------------------------------------
    // 5. declareBlockers — block decisions
    // ---------------------------------------------------------------

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

        // For each attacker, decide which blocker (if any) to assign
        for (Card att : attackers) {
            // Build option list: [blocker0, blocker1, ..., no-block]
            List<Card> blockOptions = new ArrayList<>();
            for (Card b : availableBlockers) {
                if (CombatUtil.canBlock(att, b, combat)) {
                    blockOptions.add(b);
                }
            }
            if (blockOptions.isEmpty()) {
                continue; // no legal blocker for this attacker
            }

            // Encode: N blocker options + 1 "no block" option
            int numOpts = blockOptions.size() + 1;
            float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
            for (int i = 0; i < blockOptions.size(); i++) {
                float[] bf = GameStateEncoder.encodeCard(blockOptions.get(i));
                System.arraycopy(bf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
            }
            // Last option = no-block (all zeros)

            int chosen = callBridgeWithLogging(DecisionType.BLOCK, state, optFeatures, numOpts);
            chosen = Math.max(0, Math.min(chosen, numOpts - 1));

            if (chosen < blockOptions.size()) {
                Card blocker = blockOptions.get(chosen);
                combat.addBlocker(att, blocker);
                availableBlockers.remove(blocker);
            }
            // else: no block for this attacker
        }
    }

    // ---------------------------------------------------------------
    // 6. chooseSingleEntityForEffect — targeting
    // ---------------------------------------------------------------

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(
            FCollectionView<T> optionList, DelayedReveal delayedReveal,
            SpellAbility sa, String title, boolean isOptional,
            Player targetedPlayer, Map<String, Object> params) {

        if (delayedReveal != null) {
            reveal(delayedReveal);
        }

        if (optionList == null || optionList.isEmpty()) {
            return null;
        }
        if (optionList.size() == 1) {
            return isOptional ? null : optionList.get(0);
        }

        float[] state = GameStateEncoder.encode(getPlayer(), getGame());

        // Encode each option
        int numOpts = isOptional ? optionList.size() + 1 : optionList.size();
        // Cap at MAX_OPTIONS to prevent buffer overflow
        numOpts = Math.min(numOpts, NNConstants.MAX_OPTIONS);
        int entityCount = Math.min(optionList.size(), numOpts);

        float[][] optFeatures = new float[numOpts][NNConstants.CARD_FEATURES];
        for (int i = 0; i < entityCount; i++) {
            T entity = optionList.get(i);
            if (entity instanceof Card) {
                float[] cf = GameStateEncoder.encodeCard((Card) entity);
                System.arraycopy(cf, 0, optFeatures[i], 0, NNConstants.CARD_FEATURES);
            } else {
                // For non-card entities (players, planeswalkers-as-GameEntity),
                // use a simple ordinal encoding
                optFeatures[i][0] = (float) (i + 1) / numOpts;
            }
        }
        // If optional, last slot is "choose nothing" (all zeros)

        int chosen = callBridgeWithLogging(DecisionType.CARD_CHOICE, state, optFeatures, numOpts);
        chosen = Math.max(0, Math.min(chosen, numOpts - 1));

        if (isOptional && chosen >= optionList.size()) {
            return null; // chose "nothing"
        }
        if (chosen >= optionList.size()) {
            chosen = optionList.size() - 1;
        }
        return optionList.get(chosen);
    }

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
