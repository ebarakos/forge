package forge.gamesimulationtests;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.ai.GameState;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.item.IPaperCard;
import forge.util.ThreadUtil;

/**
 * Small, deterministic board states with one undisputed AI decision.
 *
 * <p>Each puzzle comes from a real play bug. Keep only positions whose correct line
 * does not depend on hidden information or a debatable strategic preference.
 */
public class AiDecisionPuzzleTest extends AITest {

    @Test(enabled = false, description = "Executable specification for a rejected change, not a pending bug. "
            + "Keep it disabled unless the attack-pump idea is revived and re-measured.")
    public void basiliskGatePumpsForLethalBeforeAttacking() {
        // Four Gates make Squadron Hawk exactly lethal against an opponent at five.
        // A measured experiment rejected the attack-pump change this asserts: the
        // pre-registered decision rule was not met and the implementation was removed.
        // The AI passes here instead of pumping, so this test fails by design; it is
        // kept only as a written-down spec of what the rejected change would have done.
        final Game game = scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "removesummoningsickness=true",
                "p0life=5",
                "p0battlefield=",
                "p1life=20",
                "p1battlefield=Basilisk Gate;Basilisk Gate;Basilisk Gate;Basilisk Gate;Squadron Hawk");
        final Player ai = game.getPlayers().get(1);
        final List<SpellAbility> choice = ((PlayerControllerAi) ai.getController())
                .getAi().chooseSpellAbilityToPlay();

        Assert.assertNotNull(choice);
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Basilisk Gate");
        Assert.assertEquals(choice.get(0).getApi(), ApiType.Pump);
        Assert.assertEquals(choice.get(0).getTargetCard().getName(), "Squadron Hawk");
    }

    @Test(description = "Lethal burn at sorcery speed against an empty board")
    public void burnsOutAnOpponentWhoHasNothingInPlay() {
        // Neither player controls a creature, so the spell's only legal targets are
        // players, and three damage to an opponent on three ends the game this turn.
        // There is deliberately no creature to aim at: whether to point removal at a
        // creature or at a face is a real strategic judgement, and a puzzle that
        // forced the face over a live creature would be asserting a preference, not
        // a fact. This position is sensitive - raise the opponent to ten life and
        // the AI holds the spell instead - so it does guard the burn decision.
        final Game game = scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "p0life=3",
                "p0battlefield=",
                "p1life=20",
                "p1battlefield=Mountain;Mountain;Mountain",
                "p1hand=Lightning Bolt");
        final Player opponent = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);
        final List<SpellAbility> choice = ((PlayerControllerAi) ai.getController())
                .getAi().chooseSpellAbilityToPlay();

        Assert.assertNotNull(choice, "AI passed instead of casting a burn spell that wins now");
        Assert.assertEquals(choice.get(0).getHostCard().getName(), "Lightning Bolt");
        Assert.assertEquals(choice.get(0).getApi(), ApiType.DealDamage);
        Assert.assertSame(choice.get(0).getTargets().getFirstTargetedPlayer(), opponent,
                "AI cast the burn spell but did not aim it at the opponent");
    }

    @Test(description = "Lethal attack into an empty battlefield")
    public void attacksForLethalWhenTheOpponentCannotBlock() {
        // The opponent controls nothing at all, so the attack cannot be blocked and
        // exactly kills an opponent on two; not attacking wins nothing this turn.
        // Be honest about what this guards: the AI attacks with everything whenever
        // the opponent has no blockers, at any life total, so this catches the AI
        // failing to attack from a scripted position rather than proving it counts
        // lethal. It is here because it is the combat-side check that the scripted
        // state feeds a working combat plan at all.
        final Game game = scriptedGame(
                "turn=5",
                "activeplayer=p1",
                "activephase=MAIN1",
                "removesummoningsickness=true",
                "p0life=2",
                "p0battlefield=",
                "p1life=20",
                "p1battlefield=Grizzly Bears");
        final Player ai = game.getPlayers().get(1);
        final Combat combat = ((PlayerControllerAi) ai.getController()).getAi().getPredictedCombat();

        Assert.assertNotNull(combat, "AI produced no combat plan on a turn it can win");
        Assert.assertEquals(combat.getAttackers().size(), 1, "AI declined a lethal unblockable attack");
        Assert.assertEquals(combat.getAttackers().get(0).getName(), "Grizzly Bears");
    }

    private Game scriptedGame(final String... lines) {
        final Game game = initAndCreateGame();
        final GameState state = new GameState() {
            @Override
            public IPaperCard getPaperCard(final String cardName, final String setCode, final int artId) {
                if (setCode != null && !setCode.isEmpty()) {
                    final IPaperCard exact = StaticData.instance().getCommonCards()
                            .getCard(cardName, setCode, artId);
                    if (exact != null) {
                        return exact;
                    }
                }
                return StaticData.instance().getCommonCards().getCard(cardName);
            }
        };
        state.parse(Arrays.asList(lines));
        final CountDownLatch applied = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        ThreadUtil.invokeInGameThread(() -> {
            try {
                // applyToGame runs inline here because this is already a Forge game
                // thread, so the latch observes the fully restored position.
                state.applyToGame(game);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                applied.countDown();
            }
        });
        try {
            Assert.assertTrue(applied.await(10, TimeUnit.SECONDS), "Timed out applying scripted game state");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while applying scripted game state", e);
        }
        if (failure.get() != null) {
            throw new AssertionError("Could not apply scripted game state", failure.get());
        }
        return game;
    }
}
