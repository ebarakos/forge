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
 * They are the things that have to be true before a measurement means anything: the forced
 * seat really does overrule a refusal the heuristic AI still makes; the costs of a forced
 * card really can be paid once the AI's worth-it checks are out of the way; a cost that
 * turns out not to be payable is reported as such rather than counted as a force; and one
 * rollout forces one action rather than spending the whole turn on a repeatable one.
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

        // Offering the ability is not forcing it. Nothing is recorded until the engine has
        // actually played it, so the counters are still at zero here.
        Assert.assertEquals(controller.getOffers(), 1);
        Assert.assertEquals(controller.getAttempts(), 0,
                "An offered action was counted as forced before it was played");
        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.NEVER_OFFERED);

        Assert.assertTrue(playOnGameThread(controller, choice.get(0)), "The forced play reported failure");
        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.FORCED);
        Assert.assertEquals(controller.getAttempts(), 1);
        Assert.assertEquals(controller.getFirstForcedPhase(), "MAIN1");
    }

    /**
     * A cost the estimate approves and the payment refuses is not a force.
     *
     * <p>{@code ComputerUtilCost.canPayCost} asks the rules whether a cost could be paid;
     * paying it runs through the AI's own cost decisions, which can refuse. Fling is the
     * sharpest case: its additional cost is "sacrifice a creature", and the fixed targeting
     * rule aims it at the seat's own best creature — the only creature it could sacrifice.
     * The rules-level check sees a creature to sacrifice, because it runs before targets are
     * chosen; the payment then drops that creature from the choices precisely because it is
     * the target, and fails.
     *
     * <p>Before 2026-08-15 that rollout was recorded as {@code FORCED} anyway. The forced
     * arm was byte-for-byte the natural arm, the pre-registered "the action was taken in at
     * least 70% of forced rollouts" gate passed, and a candidate that was never once forced
     * came back as measured with a difference of about zero.
     */
    @Test(description = "A cost that the estimate approved but payment refused is not a force")
    public void reportsPaymentFailureRatherThanCountingItAsAForce() {
        final ForcedLobbyPlayer seat = new ForcedLobbyPlayer("p1", null, "Fling|deals damage", 0);
        final Game game = scriptedGame(seat,
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "removesummoningsickness=true",
                "p0life=20",
                "p0battlefield=",
                "p0library=" + FILLER_LIBRARY,
                "p1life=20",
                "p1battlefield=Mountain;Mountain;Grizzly Bears",
                "p1hand=Fling",
                "p1library=" + FILLER_LIBRARY);
        final Player ai = game.getPlayers().get(1);
        final ForcedActionController controller = (ForcedActionController) ai.getController();

        final List<SpellAbility> choice = controller.chooseSpellAbilityToPlay();
        Assert.assertNotNull(choice, "Forcing never offered Fling; it got as far as "
                + controller.getOutcome() + ", so this board no longer tests payment failure");
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Fling");

        playOnGameThread(controller, choice.get(0));

        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.PAYMENT_FAILED,
                "A forced cast whose sacrifice could not be paid was recorded as " + controller.getOutcome());
        Assert.assertEquals(controller.getAttempts(), 0, "A cast that never happened was counted as a force");
        Assert.assertEquals(controller.getOffers(), 1);
        Assert.assertEquals(zoneOf(game, "Grizzly Bears"), "Battlefield",
                "The sacrifice was paid after all, so this board no longer tests payment failure");
    }

    /**
     * One rollout forces one action, unless the caller asks for more.
     *
     * <p>The forcing window is a whole turn, so with no per-rollout limit a repeatable
     * activated ability is forced at every priority until the mana runs out. That is a
     * different measurement from the one the tool is for: "would taking this action have
     * changed who won" becomes "would spending the whole turn on it have", and for a
     * repeatable self-damaging ability the difference falls on the forced arm alone.
     */
    @Test(description = "A repeatable ability is forced once per rollout, not until the mana runs out")
    public void forcesTheActionOnceAndThenPlaysNormally() {
        final ForcedLobbyPlayer seat = new ForcedLobbyPlayer("p1", null, "Basilisk Gate|Target creature", 0);
        final Game game = scriptedGame(seat, lethalBasiliskGateBoard());
        final Player ai = game.getPlayers().get(1);
        final ForcedActionController controller = (ForcedActionController) ai.getController();
        Assert.assertEquals(controller.getMaxForces(), 1, "One force per rollout has to be the default");

        final List<SpellAbility> first = controller.chooseSpellAbilityToPlay();
        Assert.assertNotNull(first, "Forcing did not offer the pump");
        Assert.assertTrue(playOnGameThread(controller, first.get(0)), "The forced play reported failure");
        Assert.assertEquals(controller.getAttempts(), 1);

        // Three more Gates are untapped and the ability is repeatable, so without the limit
        // the seat would pump again here and keep going.
        controller.chooseSpellAbilityToPlay();
        Assert.assertEquals(controller.getAttempts(), 1,
                "The action was forced more than once, so the rollout measured spending the turn "
                        + "on it rather than taking it");
        Assert.assertEquals(controller.getOffers(), 1,
                "The action was offered again after its one force, so the window never closed");
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

        Assert.assertTrue(playOnGameThread(controller, choice.get(0)), "The forced cast reported failure");
        Assert.assertEquals(controller.getOutcome(), ForcedActionController.Outcome.FORCED,
                "The cast was not recorded as a force; it got as far as " + controller.getOutcome());
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
