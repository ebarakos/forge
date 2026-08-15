package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests the rule that decides who owns each decision on an LLM seat: the
 * heuristic AI, or the model.
 *
 * <p>The switches themselves are {@code static final} fields read from the
 * process environment at class-load time, which a test cannot change. The rule
 * that turns an environment value into a setting is separate and takes its
 * inputs as arguments, so it can be checked directly. Two properties matter and
 * both are asserted here:
 *
 * <ul>
 *   <li>with nothing set, every muzzle stays where it was before these switches
 *       existed — the heuristic keeps the decisions it always kept;</li>
 *   <li>{@code FORGE_LLM_UNMUZZLED} moves every one of them to the model, and a
 *       single named variable still overrides the aggregate in either direction.</li>
 * </ul>
 */
public class LLMMuzzleFlagTest {

    /** Every boolean muzzle. Named here so a new one added without a test fails review, not a run. */
    private static final String[] BOOLEAN_MUZZLES = {
            "FORGE_LLM_TRUST_HEURISTIC_TOP",
            "FORGE_LLM_PRUNE_HOPELESS",
            "FORGE_LLM_SINGLE_OPTION_SHORTCUT",
            "FORGE_LLM_PLAN_STEP_APPROVAL",
            "FORGE_LLM_EMPTY_PLAN_OVERRIDE",
            "FORGE_LLM_HEURISTIC_LAND_DROPS",
            "FORGE_LLM_HARDCODED_PLAY_FIRST",
    };

    @Test
    public void unsetMeansEveryBooleanMuzzleStaysOn() {
        for (String muzzle : BOOLEAN_MUZZLES) {
            Assert.assertTrue(LLMFullController.resolveMuzzle(null, false),
                    muzzle + " must stay on when nothing is set");
            Assert.assertTrue(LLMFullController.resolveMuzzle("", false),
                    muzzle + " must stay on when set to an empty value");
        }
    }

    @Test
    public void unmuzzledTurnsEveryBooleanMuzzleOff() {
        for (String muzzle : BOOLEAN_MUZZLES) {
            Assert.assertFalse(LLMFullController.resolveMuzzle(null, true),
                    muzzle + " must go off under FORGE_LLM_UNMUZZLED");
        }
    }

    @Test
    public void namingOneMuzzleBeatsTheAggregate() {
        // Put a single muzzle back inside an otherwise unmuzzled run.
        Assert.assertTrue(LLMFullController.resolveMuzzle("1", true));
        // And take a single muzzle off without unmuzzling the rest.
        Assert.assertFalse(LLMFullController.resolveMuzzle("0", false));
        Assert.assertFalse(LLMFullController.resolveMuzzle("false", false));
        Assert.assertFalse(LLMFullController.resolveMuzzle("FALSE", false));
    }

    @Test
    public void optionCapIsEightByDefaultAndUncappedWhenUnmuzzled() {
        Assert.assertEquals(LLMFullController.resolveInt(null, 8), 8);
        Assert.assertEquals(LLMFullController.resolveInt(null, 0), 0);
        // An explicit FORGE_LLM_TOPK wins over either default.
        Assert.assertEquals(LLMFullController.resolveInt("3", 8), 3);
        Assert.assertEquals(LLMFullController.resolveInt("3", 0), 3);
        // Garbage falls back rather than throwing mid-game.
        Assert.assertEquals(LLMFullController.resolveInt("eight", 8), 8);
    }

    @Test
    public void combatGoesToTheHeuristicUnlessAskedOtherwise() {
        Assert.assertEquals(LLMFullController.parseCombatMode(null, "heuristic"), "heuristic");
        Assert.assertEquals(LLMFullController.parseCombatMode("nonsense", "heuristic"), "heuristic");
        // Under FORGE_LLM_UNMUZZLED the fallback becomes "llm".
        Assert.assertEquals(LLMFullController.parseCombatMode(null, "llm"), "llm");
        // An explicit FORGE_LLM_COMBAT still wins in both directions.
        Assert.assertEquals(LLMFullController.parseCombatMode("heuristic", "llm"), "heuristic");
        Assert.assertEquals(LLMFullController.parseCombatMode("LLM", "heuristic"), "llm");
        Assert.assertEquals(LLMFullController.parseCombatMode(" shadow ", "heuristic"), "shadow");
    }

    /**
     * The defaults compiled into the running build, read through the same
     * fields the game reads. This is the "byte-identical when unset" check: if
     * the test environment has no FORGE_LLM_* variables set, every muzzle must
     * read as on.
     */
    @Test
    public void compiledDefaultsMatchThePreSwitchBehaviour() {
        if (System.getenv("FORGE_LLM_UNMUZZLED") != null) {
            throw new org.testng.SkipException("FORGE_LLM_UNMUZZLED is set in this environment");
        }
        Assert.assertFalse(LLMFullController.UNMUZZLED);
        Assert.assertTrue(LLMFullController.TRUST_HEURISTIC_TOP);
        Assert.assertTrue(LLMFullController.PRUNE_HOPELESS);
        Assert.assertTrue(LLMFullController.SINGLE_OPTION_SHORTCUT);
        Assert.assertTrue(LLMFullController.PLAN_STEP_APPROVAL);
        Assert.assertTrue(LLMFullController.EMPTY_PLAN_OVERRIDE);
        Assert.assertTrue(LLMFullController.HEURISTIC_LAND_DROPS);
        Assert.assertTrue(LLMFullController.HARDCODED_PLAY_FIRST);
        Assert.assertEquals(LLMFullController.HEURISTIC_TOPK, 8);
        Assert.assertEquals(LLMFullController.COMBAT_MODE, "heuristic");
    }
}
