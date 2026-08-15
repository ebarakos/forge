package forge.gamesimulationtests;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.ForcedActionController;
import forge.ai.ForcedLobbyPlayer;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.ThreadUtil;

/**
 * Can a card the AI refused be played anyway, from the position where it refused it?
 *
 * <p>These are not tests of whether forcing wins games — that is measured, not asserted.
 * They are the two things that have to be true before a measurement means anything: the
 * forced seat really does overrule a refusal the heuristic AI still makes, and the costs of
 * a forced card really can be paid once the AI's worth-it checks are out of the way.
 */
public class ForcedActionControllerTest extends AITest {

    /** Twelve cards a side, so nobody loses to an empty library while a rollout plays out. */
    private static final String FILLER_LIBRARY =
            "Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain";

    @Test(description = "Forcing plays a pump the heuristic AI passes on, at its own creature")
    public void forcesTheBasiliskGatePumpTheHeuristicRefuses() {
        final ForcedLobbyPlayer seat = new ForcedLobbyPlayer("p1", null, "Basilisk Gate|Target creature", 0);
        final Game game = scriptedGame(seat, lethalBasiliskGateBoard());
        final Player ai = game.getPlayers().get(1);
        final ForcedActionController controller = (ForcedActionController) ai.getController();

        // The refusal has to still be there, or the rest of the test proves nothing.
        final List<SpellAbility> natural = controller.getAi().chooseSpellAbilityToPlay();
        Assert.assertNull(natural, "The heuristic AI no longer passes on this board, so it no longer "
                + "tests forcing against a refusal; it chose " + describe(natural));

        final List<SpellAbility> choice = controller.chooseSpellAbilityToPlay();
        Assert.assertNotNull(choice, "Forcing did not play the pump; it got as far as " + controller.getOutcome());
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Basilisk Gate");
        Assert.assertEquals(choice.get(0).getApi(), ApiType.Pump);
        Assert.assertEquals(choice.get(0).getTargetCard().getName(), "Squadron Hawk",
                "The fixed rule has to aim the pump at the AI's own creature, not at the biggest "
                        + "creature on the board");

        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.FORCED);
        Assert.assertEquals(controller.getAttempts(), 1);
        Assert.assertEquals(controller.getFirstForcedPhase(), "MAIN1");
    }

    @Test(description = "Outside its one turn the forced seat is an ordinary heuristic seat")
    public void isInertOutsideTheTurnItIsArmedFor() {
        // Same board, same forced action, but the window is a turn that has already gone by.
        final ForcedLobbyPlayer seat = new ForcedLobbyPlayer("p1", null, "Basilisk Gate|Target creature", 4);
        final Game game = scriptedGame(seat, lethalBasiliskGateBoard());
        final Player ai = game.getPlayers().get(1);
        final ForcedActionController controller = (ForcedActionController) ai.getController();

        Assert.assertNull(controller.chooseSpellAbilityToPlay(),
                "A seat outside its forcing window has to decide exactly what the heuristic AI decides");
        Assert.assertEquals(controller.getAttempts(), 0);
        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.NEVER_OFFERED);
        Assert.assertNull(controller.getFirstForcedPhase());
    }

    /**
     * The board the known-positive validation gate uses, so the same position is checked
     * here and there. Four Gates make the Squadron Hawk exactly lethal against an opponent
     * on five, and two tapped 5/5s kill the AI next turn, so passing this turn loses the
     * game. The opposing creatures are also the reason the targeting rule is worth testing:
     * they are the highest-power legal targets on the board and pumping one of them would
     * be worse than doing nothing.
     */
    private static String[] lethalBasiliskGateBoard() {
        return new String[] {
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "removesummoningsickness=true",
                "p0life=5",
                "p0battlefield=Silverback Ape|Tapped;Silverback Ape|Tapped",
                "p0library=" + FILLER_LIBRARY,
                "p1life=10",
                "p1battlefield=Basilisk Gate;Basilisk Gate;Basilisk Gate;Basilisk Gate;Squadron Hawk",
                "p1library=" + FILLER_LIBRARY,
        };
    }

    /**
     * A sacrifice cost can be force-paid — measured here, not assumed.
     *
     * <p>This mattered because forcing only bypasses the AI's opinion about whether to play
     * a card; the cost is still paid by the AI's own cost-payment code, which has opinions
     * of its own and can refuse by returning no choice. If sacrifice costs could not be
     * force-paid, every candidate whose cost is a sacrifice would be unmeasurable and would
     * have to be reported as such.
     *
     * <p>They can. On this board the heuristic AI passes on Village Rites, the forced seat
     * casts it, and the sacrifice really happens: the creature reaches the graveyard and the
     * spell reaches the stack.
     */
    @Test(description = "A sacrifice cost is really paid when the worth-it checks are bypassed")
    public void paysASacrificeCostThatTheHeuristicRefusesToPay() {
        final ForcedLobbyPlayer seat = new ForcedLobbyPlayer("p1", null, "Village Rites|Draw two cards", 0);
        final Game game = scriptedGame(seat,
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "removesummoningsickness=true",
                "p0life=20",
                "p0battlefield=",
                "p0library=" + FILLER_LIBRARY,
                "p1life=20",
                "p1battlefield=Swamp;Grizzly Bears",
                "p1hand=Village Rites",
                "p1library=" + FILLER_LIBRARY);
        final Player ai = game.getPlayers().get(1);
        final ForcedActionController controller = (ForcedActionController) ai.getController();

        final List<SpellAbility> natural = controller.getAi().chooseSpellAbilityToPlay();
        Assert.assertNull(natural, "The heuristic AI no longer refuses Village Rites here, so this "
                + "board no longer tests forced cost payment; it chose " + describe(natural));

        final List<SpellAbility> choice = controller.chooseSpellAbilityToPlay();
        Assert.assertNotNull(choice, "Forcing did not reach Village Rites; it got as far as "
                + controller.getOutcome() + ". If this ever becomes COST_CHECK_FAILED, sacrifice "
                + "costs are no longer force-payable and candidates that cost a sacrifice are "
                + "unmeasurable by forcing.");
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Village Rites");
        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.FORCED);

        Assert.assertTrue(playOnGameThread(controller, choice.get(0)), "The forced cast reported failure");
        Assert.assertEquals(zoneOf(game, "Grizzly Bears"), "Graveyard",
                "Village Rites was cast without its sacrifice being paid");
        Assert.assertEquals(zoneOf(game, "Village Rites"), "Stack",
                "The sacrifice was paid but the spell did not reach the stack");
    }

    private static String describe(final List<SpellAbility> choice) {
        if (choice == null || choice.isEmpty()) {
            return "a pass";
        }
        return choice.get(0).getHostCard().getName() + ": " + choice.get(0).getDescription();
    }

    private static String zoneOf(final Game game, final String cardName) {
        for (final ZoneType zone : ZoneType.values()) {
            for (final Card card : game.getCardsIn(zone)) {
                if (card.getName().equals(cardName)) {
                    return zone.name();
                }
            }
        }
        return "nowhere";
    }

    /** Play the chosen ability the way the engine would, on a Forge game thread. */
    private static boolean playOnGameThread(final ForcedActionController controller, final SpellAbility sa) {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> result = new AtomicReference<>(Boolean.FALSE);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        ThreadUtil.invokeInGameThread(() -> {
            try {
                result.set(controller.playChosenSpellAbility(sa));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out playing the forced ability");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while playing the forced ability", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("Playing the forced ability threw", failure.get());
        }
        return result.get();
    }
}
