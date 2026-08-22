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
 * Pins the corrected combat-lethal read for a team-wide pump instant.
 *
 * <p>The board below is taken from a real game. Three white creatures attack
 * unblocked, the defender is on 7 life, and a {1}{W}{W} instant gives every
 * white creature +2/+2. The pumped attack deals 4 + 3 + 3 = 10, so casting it
 * wins the game on the spot.
 *
 * <p>{@code PumpAllAi.checkApiLogic} asks
 * {@code ComputerUtilCard.shouldPumpCard} about one creature at a time. The
 * lethal-on-board branch inside that method must still count the team pump on
 * every affected attacker, not only the creature currently being queried. If it
 * does, a guaranteed lethal is deterministic and never depends on the random
 * fallback draw.
 *
 * <p>The tests fix the draw at both extremes so nothing here is flaky. With the
 * fix in place the boundary sits at the real pumped total of 10 life: the AI
 * casts at 10 and below, and with the draw pinned off it holds at 11.
 *
 * <p>Fix B from the plan stays out of scope here. The final test still records
 * that {@code Guardians' Pledge} is {@code ApiType.PumpAll}, so the separate
 * mana-reservation work keeps its own executable reminder.
 */
public class TeamPumpLethalTest extends AITest {

    /** {1}{W}{W} instant: white creatures you control get +2/+2 until end of turn. */
    private static final String TEAM_PUMP = "Guardians' Pledge";

    /** The pumped attack, and so the life total at which a correct count would see lethal. */
    private static final int REAL_PUMPED_DAMAGE = 10;

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

        final SpellAbility pump = spellInHand(ai, TEAM_PUMP);
        Assert.assertTrue(ComputerUtilCost.canPayCost(pump, ai, false),
                "the AI must be able to pay for " + TEAM_PUMP + ", or the refusal is about mana");
    }

    // ------------------------------------------------------------ lethal is deterministic

    /**
     * A guaranteed lethal must not depend on the random fallback path.
     */
    @Test
    public void castsTheLethalWhenTheCoinFlipDoesNotFire() {
        neverDraw();
        final Game game = scriptedGame(board(7));
        Assert.assertEquals(decision(game), AiPlayDecision.WillPlay,
                "with no luck involved the AI must still cast a lethal team pump");
    }

    /**
     * The same lethal remains cast when the draw is pinned the other way, proving
     * the branch is deterministic rather than lucky.
     */
    @Test
    public void castsTheSameLethalWhenTheCoinFlipDoesFire() {
        alwaysDraw();
        final Game game = scriptedGame(board(7));
        Assert.assertEquals(decision(game), AiPlayDecision.WillPlay,
                "the same lethal should be cast regardless of the random draw");
    }

    // ------------------------------------------------------------- the boundary

    /**
     * Names the branch. With the draw pinned off, the AI casts at the real pumped
     * damage total and holds one point above it.
     */
    @Test
    public void theDeterministicBoundarySitsAtTheRealPumpedTotal() {
        neverDraw();
        Assert.assertEquals(decision(scriptedGame(board(REAL_PUMPED_DAMAGE))), AiPlayDecision.WillPlay,
                "at " + REAL_PUMPED_DAMAGE + " life the real pumped total reaches the defender and the AI casts");

        neverDraw();
        Assert.assertEquals(decision(scriptedGame(board(REAL_PUMPED_DAMAGE + 1))), AiPlayDecision.CantPlayAi,
                "one point of life higher and the deterministic lethal is gone");
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
        Assert.assertEquals(spellInHand(ai, TEAM_PUMP).getApi(), ApiType.PumpAll,
                TEAM_PUMP + " is a team pump, and the mana-reserving trick path only covers ApiType.Pump");
    }

    /**
     * The queried attacker's precomputed trample damage already has blocker
     * toughness accounted for. The team-pump board-sum helper must not subtract
     * the blocker a second time or it turns a lethal trampler into a false miss.
     */
    @Test
    public void aBlockedTramplerIsNotCountedAfterSubtractingItsBlockerTwice() {
        neverDraw();

        final Game game = scriptedGame(
                "turn=9",
                "activeplayer=p1",
                "activephase=COMBAT_DECLARE_BLOCKERS",
                "p0life=3",
                "p0battlefield=Grizzly Bears",
                "p1life=12",
                "p1battlefield=Mountain;Mountain;Mountain;Mountain;Mountain;"
                        + "Hill Giant|Attacking|NoETBTrigs",
                "p1hand=Volcanic Rush"
        );

        final Combat combat = game.getCombat();
        final Card attacker = findCardWithName(game, "Hill Giant");
        final Card blocker = findCardWithName(game, "Grizzly Bears");
        combat.addBlocker(attacker, blocker);
        combat.getBandOfAttacker(attacker).setBlocked(true);
        combat.orderBlockersForDamageAssignment();
        combat.orderAttackersForDamageAssignment();
        game.getAction().checkStateEffects(true);

        Assert.assertEquals(decision(game, "Volcanic Rush"), AiPlayDecision.WillPlay,
                "the trampler should still count for 3 damage after one blocker subtraction");
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
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(spellInHand(ai, TEAM_PUMP));
    }

    private AiPlayDecision decision(final Game game, final String spellName) {
        final Player ai = game.getPlayers().get(1);
        return ((PlayerControllerAi) ai.getController()).getAi().canPlaySa(spellInHand(ai, spellName));
    }

    private SpellAbility spellInHand(final Player ai, final String spellName) {
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (spellName.equals(c.getName())) {
                final SpellAbility sa = c.getFirstSpellAbility();
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        throw new AssertionError(spellName + " is not in hand");
    }
}
