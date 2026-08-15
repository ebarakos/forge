package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

/** Focused coverage for the reasons attached to ordinary mulligan decisions. */
public class MulliganHandEvaluationTest extends AITest {

    @Test
    public void ordinaryOneLandHandReportsWhyItMulligans() {
        final Player ai = openingHand("Mountain", "Lightning Bolt", "Lightning Bolt",
                "Lightning Bolt", "Lightning Bolt", "Shock", "Shock");

        final ComputerUtil.HandEvaluation evaluation = ComputerUtil.evaluateHand(
                ai.getCardsIn(ZoneType.Hand), ai, 0);

        Assert.assertFalse(evaluation.shouldKeep(), evaluation.getReason());
        Assert.assertEquals(evaluation.getReason(),
                "mulligan: fewer than two mana sources (1)");
    }

    @Test
    public void ordinaryTwoLandHandReportsWhyItKeeps() {
        final Player ai = openingHand("Mountain", "Mountain", "Lightning Bolt",
                "Lightning Bolt", "Lightning Bolt", "Shock", "Shock");

        final ComputerUtil.HandEvaluation evaluation = ComputerUtil.evaluateHand(
                ai.getCardsIn(ZoneType.Hand), ai, 0);

        Assert.assertTrue(evaluation.shouldKeep(), evaluation.getReason());
        Assert.assertTrue(evaluation.getReason().startsWith("keep: scored "),
                evaluation.getReason());
        Assert.assertTrue(evaluation.getReason().contains("mana sources 2/7"),
                evaluation.getReason());
    }

    private Player openingHand(final String... cards) {
        final Game game = initAndCreateGame();
        final Player ai = game.getPlayers().get(1);
        ((LobbyPlayerAi) ai.getLobbyPlayer()).setAiProfile("Default");
        for (final String card : cards) {
            addCardToZone(card, ai, ZoneType.Hand);
        }
        // Fill out a normal constructed shell so the evaluator takes its ordinary
        // land-ratio path instead of treating this as a landless test deck.
        for (int i = 0; i < 19; i++) {
            addCardToZone("Forest", ai, ZoneType.Library);
        }
        for (int i = 0; i < 34; i++) {
            addCardToZone("Lightning Bolt", ai, ZoneType.Library);
        }
        return ai;
    }
}
