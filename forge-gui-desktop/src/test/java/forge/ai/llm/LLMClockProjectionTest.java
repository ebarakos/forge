package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The clock an LLM seat is shown is a projection to each side's next combat, not
 * a report of what can attack this instant.
 *
 * <p>The position that forced this: game 2 of the recorded Burn-versus-Bogles
 * run, Burn's turn-9 draw step. Burn was at 3 life with a lone 1/1. Across the
 * table stood a tapped 9/7 trample, hexproof Slippery Bogle, which would untap
 * in its controller's untap step and kill Burn at the next combat. The summary
 * printed {@code they kill you in no clock}, because a tapped creature was not
 * counted. The model then worked the lethal out for itself from the battlefield
 * list — proof that the position held the information and the summary discarded
 * it.
 */
public class LLMClockProjectionTest {

    /** The Bogle in the logged position: 9 power, no defender. */
    private static final int BOGLE_POWER = 9;

    @Test
    public void aTappedLethalAttackerStillCounts() {
        // Tapped-ness is not an input to the rule at all: the creature untaps
        // before its controller's next combat, so it clocks either way.
        Assert.assertTrue(GameStateSerializer.clocksNextTurn(false, BOGLE_POWER),
                "a tapped 9/7 with Burn at 3 life is a one-turn clock, not 'no clock'");
    }

    @Test
    public void aSummoningSickCreatureStillCounts() {
        // Same reason: sickness is gone by its controller's next combat.
        Assert.assertTrue(GameStateSerializer.clocksNextTurn(false, 2));
    }

    @Test
    public void aDefenderNeverClocks() {
        Assert.assertFalse(GameStateSerializer.clocksNextTurn(true, 5),
                "a creature with defender does not attack next turn either");
    }

    @Test
    public void aCreatureWithNoPowerClocksNothing() {
        Assert.assertFalse(GameStateSerializer.clocksNextTurn(false, 0));
        Assert.assertFalse(GameStateSerializer.clocksNextTurn(false, -1),
                "power can be negative after a shrink effect; that lands no damage");
    }
}
