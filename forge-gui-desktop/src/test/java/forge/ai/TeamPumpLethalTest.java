package forge.ai;

import java.security.SecureRandom;
import java.util.Random;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import forge.game.Game;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.MyRandom;

/**
 * Pins how the heuristic AI decides a team-wide pump instant during combat: a
 * guaranteed win is settled by a coin flip rather than recognised.
 *
 * <p>The board below is taken from a real game. Three white creatures attack
 * unblocked, the defender is on 7 life, and a {1}{W}{W} instant gives every
 * white creature +2/+2. The pumped attack deals 4 + 3 + 3 = 10, so casting it
 * wins the game on the spot.
 *
 * <p>{@code PumpAllAi.checkApiLogic} asks
 * {@code ComputerUtilCard.shouldPumpCard} about one creature at a time. The
 * deterministic lethal check inside that method — the {@code totalPowerUnblocked}
 * loop — adds the pump to the single creature it was asked about and counts every
 * other unblocked attacker at its <em>unpumped</em> damage. A pump that reads
 * "+2/+2 to all your creatures" is therefore scored as +2 damage in total instead
 * of +2 per attacker, and here it comes to 4 + 1 + 1 = 6 rather than 10.
 *
 * <p>Six is short of the defender's 7 life, so the deterministic branch does not
 * fire. What remains is the last line of the method, a random draw against a
 * chance derived from one creature's extra damage. The AI does often cast the
 * pump — it just does so by luck, and with the same probability whether the
 * attack is lethal or not.
 *
 * <p>The tests fix the draw at both extremes so nothing here is flaky. With the
 * draw pinned to never fire, the boundary between casting and holding sits at 6
 * life — the undercounted sum — and not at 10, which is where a correct count
 * would put it. That is what identifies the branch.
 *
 * <p>This test endorses nothing. It records the behaviour as it is, so that a
 * later fix has to change it deliberately.
 */
public class TeamPumpLethalTest extends AITest {

    /** {1}{W}{W} instant: white creatures you control get +2/+2 until end of turn. */
    private static final String TEAM_PUMP = "Guardians' Pledge";

    /** The pumped attack, and so the life total at which a correct count would see lethal. */
    private static final int REAL_PUMPED_DAMAGE = 10;

    /** What the {@code totalPowerUnblocked} loop counts instead: one creature pumped, the rest not. */
    private static final int UNDERCOUNTED_SUM = 6;

    /**
     * Three unblocked white attackers, exactly enough untapped lands for the
     * pump, and a defender on the given life total.
     */
    private static String[] board(final int defenderLife) {
        return new String[] {
                "turn=15",
                "activeplayer=p1",
                "activephase=COMBAT_DECLARE_BLOCKERS",
                "p0life=" + defenderLife,
                "p0battlefield=",
                "p1life=12",
                "p1battlefield=Plains;Plains;Plains;"
                        + "Kor Skyfisher|Attacking|NoETBTrigs;"
                        + "Squadron Hawk|Attacking|NoETBTrigs;"
                        + "Squadron Hawk|Attacking|NoETBTrigs",
                "p1hand=" + TEAM_PUMP,
        };
    }

    @AfterMethod
    public void restoreRandomness() {
        MyRandom.setRandom(new SecureRandom());
    }

    // ------------------------------------------------------------- the premise

    /**
     * If the position ever stops being a lethal the AI could actually take, every
     * assertion below would pass while testing nothing. Check it first.
     */
    @Test
    public void thePositionIsAnAffordableLethal() {
        final Game game = scriptedGame(board(7));
        final Player ai = game.getPlayers().get(1);
        final Player defender = game.getPlayers().get(0);
        final Combat combat = game.getCombat();

        Assert.assertNotNull(combat, "the scripted board must be in combat");
        Assert.assertEquals(combat.getAttackers().size(), 3, "all three creatures must be attacking");
        for (final Card attacker : combat.getAttackers()) {
            Assert.assertFalse(combat.isBlocked(attacker), attacker + " must be unblocked");
        }

        int unpumped = 0;
        int pumped = 0;
        for (final Card attacker : combat.getAttackers()) {
            unpumped += attacker.getNetPower();
            pumped += attacker.getNetPower() + 2;
        }
        Assert.assertEquals(unpumped, 4, "the unpumped attack must not be lethal");
        Assert.assertEquals(pumped, REAL_PUMPED_DAMAGE, "the pumped attack must be lethal");
        Assert.assertTrue(pumped >= defender.getLife(), "the pump must win the game on the spot");

        final SpellAbility pump = teamPump(ai);
        Assert.assertTrue(ComputerUtilCost.canPayCost(pump, ai, false),
                "the AI must be able to pay for " + TEAM_PUMP + ", or the refusal is about mana");
    }

    // ------------------------------------------------- the decision is a gamble

    /**
     * With the random draw pinned so it never fires, nothing deterministic in the
     * AI sees the win: it holds a pump that would end the game this turn.
     */
    @Test
    public void holdsTheLethalWhenTheCoinFlipDoesNotFire() {
        neverDraw();
        final Game game = scriptedGame(board(7));
        Assert.assertEquals(decision(game), AiPlayDecision.CantPlayAi,
                "with no luck involved the AI is expected to hold the lethal team pump");
    }

    /**
     * The very same board, with the draw pinned so it always fires, is cast. The
     * two results differ only by the coin flip, which is the point: whether the
     * AI takes a guaranteed win here is decided at random.
     */
    @Test
    public void castsTheSameLethalWhenTheCoinFlipFires() {
        alwaysDraw();
        final Game game = scriptedGame(board(7));
        Assert.assertEquals(decision(game), AiPlayDecision.WillPlay,
                "the same position is cast when the draw happens to succeed");
    }

    // ------------------------------------------------------------- the boundary

    /**
     * Names the branch. With the draw pinned off, the AI casts the pump as soon
     * as the defender's life drops to the undercounted sum of 6 — not at the
     * real pumped damage of 10, and not at 4, which is the most any single
     * pumped creature deals. A boundary at exactly 6 can only come from the
     * {@code totalPowerUnblocked} loop, which counts one attacker pumped and the
     * other two unpumped.
     */
    @Test
    public void theDeterministicBoundarySitsAtTheUndercountedSum() {
        neverDraw();
        Assert.assertEquals(decision(scriptedGame(board(UNDERCOUNTED_SUM))), AiPlayDecision.WillPlay,
                "at " + UNDERCOUNTED_SUM + " life the undercounted sum reaches the defender and the AI casts");

        neverDraw();
        Assert.assertEquals(decision(scriptedGame(board(UNDERCOUNTED_SUM + 1))), AiPlayDecision.CantPlayAi,
                "one point of life higher and the same lethal is held");

        neverDraw();
        Assert.assertEquals(decision(scriptedGame(board(REAL_PUMPED_DAMAGE))), AiPlayDecision.CantPlayAi,
                "even at exactly the real pumped damage the AI does not see the win");
    }

    // ------------------------------------------------- why no mana is held back

    /**
     * A second reason this card is decided badly, recorded here because it shares
     * the position. {@code ComputerUtilCard.shouldPumpCard} can reserve mana so a
     * combat trick is still payable at declare blockers, but that path is gated on
     * {@code sa.getApi() == ApiType.Pump}. A team pump is {@code ApiType.PumpAll},
     * so no mana is ever held back for it, however much the AI wants it later in
     * the turn.
     */
    @Test
    public void theTeamPumpIsOutsideTheCombatTrickHoldPath() {
        final Game game = scriptedGame(board(7));
        final Player ai = game.getPlayers().get(1);
        Assert.assertEquals(teamPump(ai).getApi(), ApiType.PumpAll,
                TEAM_PUMP + " is a team pump, and the mana-reserving trick path only covers ApiType.Pump");
    }

    // ----------------------------------------------------------------- helpers

    /** Fix the AI's random draw so {@code nextFloat() < chance} is never true. */
    private void neverDraw() {
        MyRandom.setRandom(new Random(0L) {
            private static final long serialVersionUID = 1L;
            @Override
            public float nextFloat() {
                return 1.0f;
            }
        });
    }

    /** Fix the AI's random draw so {@code nextFloat() < chance} is true for any positive chance. */
    private void alwaysDraw() {
        MyRandom.setRandom(new Random(0L) {
            private static final long serialVersionUID = 1L;
            @Override
            public float nextFloat() {
                return 0.0f;
            }
        });
    }

    /** The AI's own verdict on casting the pump, as {@code AiController} asks for it. */
    private AiPlayDecision decision(final Game game) {
        final Player ai = game.getPlayers().get(1);
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(teamPump(ai));
    }

    private SpellAbility teamPump(final Player ai) {
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (TEAM_PUMP.equals(c.getName())) {
                final SpellAbility sa = c.getFirstSpellAbility();
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError(TEAM_PUMP + " is not in hand");
    }
}
