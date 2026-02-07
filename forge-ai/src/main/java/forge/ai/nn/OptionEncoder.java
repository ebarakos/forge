package forge.ai.nn;

import java.util.List;

import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.spellability.SpellAbility;

/**
 * Encodes available choices for a decision into float[numOptions][CARD_FEATURES].
 *
 * Each option is represented as a float[16] feature vector, matching
 * the per-card encoding used by {@link GameStateEncoder#encodeCard(Card)}.
 */
public final class OptionEncoder {

    private OptionEncoder() { }

    /**
     * Encode a collection of cards as options.
     *
     * @param cards the available card choices
     * @return float[cards.size()][CARD_FEATURES]
     */
    public static float[][] encodeCards(CardCollectionView cards) {
        float[][] result = new float[cards.size()][NNConstants.CARD_FEATURES];
        int i = 0;
        for (Card card : cards) {
            result[i] = encodeCard(card);
            i++;
        }
        return result;
    }

    /**
     * Encode a list of spell abilities as options, using each ability's host card.
     *
     * @param sas the available spell ability choices
     * @return float[sas.size()][CARD_FEATURES]
     */
    public static float[][] encodeSpellAbilities(List<SpellAbility> sas) {
        float[][] result = new float[sas.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < sas.size(); i++) {
            result[i] = encodeCard(sas.get(i).getHostCard());
        }
        return result;
    }

    /**
     * Encode a list of game entities as options.
     * If the entity is a Card, encode it normally; otherwise use a default encoding.
     *
     * @param entities the available entity choices
     * @return float[entities.size()][CARD_FEATURES]
     */
    public static float[][] encodeGameEntities(List<? extends GameEntity> entities) {
        float[][] result = new float[entities.size()][NNConstants.CARD_FEATURES];
        for (int i = 0; i < entities.size(); i++) {
            GameEntity entity = entities.get(i);
            if (entity instanceof Card) {
                result[i] = encodeCard((Card) entity);
            } else {
                // Default encoding for non-card entities (e.g., Player):
                // just mark as present with all other features zeroed
                result[i][0] = 1.0f;
            }
        }
        return result;
    }

    /**
     * Encode a boolean (yes/no) choice as two options.
     * Option 0 (yes): [1, 0, 0, ..., 0]
     * Option 1 (no):  [0, 1, 0, ..., 0]
     *
     * @return float[2][CARD_FEATURES]
     */
    public static float[][] encodeBooleanChoice() {
        float[][] result = new float[2][NNConstants.CARD_FEATURES];
        result[0][0] = 1.0f; // yes
        result[1][1] = 1.0f; // no
        return result;
    }

    /**
     * Encode a numeric range as options, one per integer in [min, max].
     * Each option's first feature is the normalized value: (i - min) / (max - min).
     * If max == min, the single option's first feature is 1.0.
     *
     * @param min the minimum value (inclusive)
     * @param max the maximum value (inclusive)
     * @return float[(max - min + 1)][CARD_FEATURES]
     */
    public static float[][] encodeNumberRange(int min, int max) {
        int count = max - min + 1;
        float[][] result = new float[count][NNConstants.CARD_FEATURES];
        float range = max - min;
        for (int i = 0; i < count; i++) {
            result[i][0] = (range == 0) ? 1.0f : (float) i / range;
        }
        return result;
    }

    /**
     * Encode a single card. Delegates to {@link GameStateEncoder#encodeCard(Card)}.
     *
     * @param card the card to encode
     * @return float[CARD_FEATURES]
     */
    public static float[] encodeCard(Card card) {
        return GameStateEncoder.encodeCard(card);
    }
}
