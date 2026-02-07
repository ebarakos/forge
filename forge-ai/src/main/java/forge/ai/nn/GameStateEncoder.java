package forge.ai.nn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import forge.card.ColorSet;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/**
 * Encodes game state into a fixed-size float tensor for neural network input.
 *
 * Layout (total STATE_SIZE = 664):
 *   [0..23]    Global features (life, hand size, phase, mana, etc.)
 *   [24..279]  My battlefield (16 slots x 16 features)
 *   [280..535] Opponent battlefield (16 slots x 16 features)
 *   [536..663] My hand (8 slots x 16 features)
 */
public final class GameStateEncoder {

    private static final int GLOBAL_OFFSET = 0;
    private static final int MY_BATTLEFIELD_OFFSET = NNConstants.GLOBAL_FEATURES; // 24
    private static final int OPP_BATTLEFIELD_OFFSET = MY_BATTLEFIELD_OFFSET
            + NNConstants.BATTLEFIELD_SLOTS * NNConstants.CARD_FEATURES; // 24 + 256 = 280
    private static final int MY_HAND_OFFSET = OPP_BATTLEFIELD_OFFSET
            + NNConstants.BATTLEFIELD_SLOTS * NNConstants.CARD_FEATURES; // 280 + 256 = 536

    /** Comparator: creatures first (by CMC descending), then non-creatures (by CMC descending). */
    private static final Comparator<Card> IMPORTANCE_ORDER = (a, b) -> {
        boolean aCre = a.isCreature();
        boolean bCre = b.isCreature();
        if (aCre != bCre) {
            return aCre ? -1 : 1; // creatures first
        }
        return Integer.compare(b.getCMC(), a.getCMC()); // higher CMC first
    };

    private GameStateEncoder() { }

    /**
     * Encode the full game state from the perspective of the given player.
     *
     * @param me   the player whose perspective we encode
     * @param game the current game
     * @return float array of size {@link NNConstants#STATE_SIZE}
     */
    public static float[] encode(Player me, Game game) {
        float[] state = new float[NNConstants.STATE_SIZE];

        Player opp = findOpponent(me, game);

        // --- Global features (offset 0, size 24) ---
        state[GLOBAL_OFFSET]      = me.getLife() / 20.0f;
        state[GLOBAL_OFFSET + 1]  = opp.getLife() / 20.0f;
        state[GLOBAL_OFFSET + 2]  = me.getCardsIn(ZoneType.Hand).size() / 7.0f;
        state[GLOBAL_OFFSET + 3]  = opp.getCardsIn(ZoneType.Hand).size() / 7.0f;
        state[GLOBAL_OFFSET + 4]  = me.getZone(ZoneType.Graveyard).size() / 20.0f;
        state[GLOBAL_OFFSET + 5]  = opp.getZone(ZoneType.Graveyard).size() / 20.0f;
        state[GLOBAL_OFFSET + 6]  = me.getZone(ZoneType.Library).size() / 60.0f;
        state[GLOBAL_OFFSET + 7]  = opp.getZone(ZoneType.Library).size() / 60.0f;
        state[GLOBAL_OFFSET + 8]  = Math.min(game.getPhaseHandler().getTurn() / 20.0f, 1.0f);
        state[GLOBAL_OFFSET + 9]  = game.getPhaseHandler().isPlayerTurn(me) ? 1.0f : 0.0f;

        // Phase one-hot (indices 10..22, 13 values)
        PhaseType currentPhase = game.getPhaseHandler().getPhase();
        PhaseType[] phases = PhaseType.values();
        for (int i = 0; i < phases.length; i++) {
            state[GLOBAL_OFFSET + 10 + i] = (phases[i] == currentPhase) ? 1.0f : 0.0f;
        }

        // Available mana: count untapped lands
        int untappedLands = 0;
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                untappedLands++;
            }
        }
        state[GLOBAL_OFFSET + 23] = untappedLands / 10.0f;

        // --- My battlefield (offset 24, 16 slots x 16 features) ---
        encodeZoneSlots(state, MY_BATTLEFIELD_OFFSET, me.getCardsIn(ZoneType.Battlefield),
                NNConstants.BATTLEFIELD_SLOTS);

        // --- Opponent battlefield (offset 280, 16 slots x 16 features) ---
        encodeZoneSlots(state, OPP_BATTLEFIELD_OFFSET, opp.getCardsIn(ZoneType.Battlefield),
                NNConstants.BATTLEFIELD_SLOTS);

        // --- My hand (offset 536, 8 slots x 16 features) ---
        encodeZoneSlots(state, MY_HAND_OFFSET, me.getCardsIn(ZoneType.Hand),
                NNConstants.HAND_SLOTS);

        return state;
    }

    /**
     * Encode a single card into a float[16] feature vector.
     * This is reused by {@link OptionEncoder} for option encoding.
     *
     * @param card the card to encode
     * @return float array of size {@link NNConstants#CARD_FEATURES}
     */
    public static float[] encodeCard(Card card) {
        float[] features = new float[NNConstants.CARD_FEATURES];

        features[0]  = 1.0f;                                        // present
        features[1]  = card.getCMC() / 10.0f;                       // CMC
        features[2]  = card.isCreature() ? card.getNetPower() / 20.0f : 0.0f;   // power
        features[3]  = card.isCreature() ? card.getNetToughness() / 20.0f : 0.0f; // toughness
        features[4]  = card.isCreature() ? 1.0f : 0.0f;             // is_creature
        features[5]  = card.isLand() ? 1.0f : 0.0f;                 // is_land
        features[6]  = (card.isInstant() || card.isSorcery()) ? 1.0f : 0.0f; // is_instant_or_sorcery
        features[7]  = card.isEnchantment() ? 1.0f : 0.0f;          // is_enchantment
        features[8]  = card.isArtifact() ? 1.0f : 0.0f;             // is_artifact

        ColorSet colors = card.getColor();
        features[9]  = colors.hasWhite() ? 1.0f : 0.0f;             // color_W
        features[10] = colors.hasBlue() ? 1.0f : 0.0f;              // color_U
        features[11] = colors.hasBlack() ? 1.0f : 0.0f;             // color_B
        features[12] = colors.hasRed() ? 1.0f : 0.0f;               // color_R
        features[13] = colors.hasGreen() ? 1.0f : 0.0f;             // color_G

        features[14] = card.isTapped() ? 1.0f : 0.0f;               // is_tapped
        features[15] = card.hasSickness() ? 1.0f : 0.0f;            // has_summoning_sickness

        return features;
    }

    // --- Private helpers ---

    /**
     * Sort cards by importance and encode into fixed slots at the given offset.
     * Extra cards beyond maxSlots are dropped; unused slots remain zeroed.
     */
    private static void encodeZoneSlots(float[] state, int offset, CardCollectionView cards, int maxSlots) {
        List<Card> sorted = new ArrayList<>(cards.size());
        for (Card c : cards) {
            sorted.add(c);
        }
        sorted.sort(IMPORTANCE_ORDER);

        int count = Math.min(sorted.size(), maxSlots);
        for (int i = 0; i < count; i++) {
            float[] encoded = encodeCard(sorted.get(i));
            System.arraycopy(encoded, 0, state, offset + i * NNConstants.CARD_FEATURES,
                    NNConstants.CARD_FEATURES);
        }
        // Remaining slots stay at 0.0 (default float array value)
    }

    /**
     * Find the opponent in a two-player game.
     */
    private static Player findOpponent(Player me, Game game) {
        for (Player p : game.getPlayers()) {
            if (p != me) {
                return p;
            }
        }
        // Fallback: should not happen in a normal 2-player game
        return me;
    }
}
