package forge.ai.llm;

import forge.ai.AiPlayDecision;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests the two rules that decide what an LLM seat is shown and what it is
 * asked, both of which used to let the heuristic AI answer in the model's name.
 *
 * <ul>
 *   <li>Which targeted spells reach the option list — the rule in
 *       {@link LLMFullController#acceptTargetedOption}.</li>
 *   <li>The guard that stops an LLM call being spent inside a "could this even
 *       be cast?" check, whose answer is thrown away.</li>
 * </ul>
 */
public class LLMOptionFilterTest {

    // ------------------------------------------------- which options reach the model

    @Test
    public void aSpellTheHeuristicWantsToCastIsOffered() {
        Assert.assertTrue(LLMFullController.acceptTargetedOption(AiPlayDecision.WillPlay, true));
        // Any remaining targeting happens when the spell is actually played.
        Assert.assertTrue(LLMFullController.acceptTargetedOption(AiPlayDecision.WillPlay, false));
    }

    @Test
    public void aSpellWithNoLegalTargetIsNotOffered() {
        Assert.assertFalse(
                LLMFullController.acceptTargetedOption(AiPlayDecision.TargetingFailed, false));
        // Even if something is somehow still targeted, the heuristic has said
        // targeting failed, and an untargetable spell cannot be cast at all.
        Assert.assertFalse(
                LLMFullController.acceptTargetedOption(AiPlayDecision.TargetingFailed, true));
    }

    @Test
    public void aSpellTheHeuristicMerelyDislikesStaysOnTheMenu() {
        // "I would rather wait" and "this is a bad idea" are opinions. The model
        // is allowed to disagree with them, so the option survives — as long as
        // the spell really is pointed at something.
        for (AiPlayDecision refusal : new AiPlayDecision[] {
                AiPlayDecision.WaitForMain2,
                AiPlayDecision.WaitForCombat,
                AiPlayDecision.AnotherTime,
                AiPlayDecision.BadEtbEffects,
                AiPlayDecision.DoesntImpactGame,
        }) {
            Assert.assertTrue(LLMFullController.acceptTargetedOption(refusal, true),
                    refusal + " should stay in the list when the spell is targeted");
        }
    }

    @Test
    public void aRefusedSpellWithNothingTargetedIsDropped() {
        // This is the case that used to slip through. The verdict is a refusal
        // and the heuristic pointed the spell at nothing this time; before the
        // targets were cleared first, an answer left over from an earlier
        // priority pass could still make isTargetNumberValid() true and put the
        // spell in front of the model aimed at a creature that had since died.
        for (AiPlayDecision refusal : new AiPlayDecision[] {
                AiPlayDecision.WaitForMain2,
                AiPlayDecision.BadEtbEffects,
                AiPlayDecision.CantPlayAi,
                AiPlayDecision.LifeInDanger,
        }) {
            Assert.assertFalse(LLMFullController.acceptTargetedOption(refusal, false),
                    refusal + " with nothing targeted must not be offered");
        }
    }

    // ------------------------------------------------------- the feasibility guard

    @BeforeMethod
    public void resetGuard() {
        // The counter is per-thread and TestNG reuses threads.
        while (LLMFullController.isCheckingFeasibility()) {
            LLMFullController.exitFeasibility();
        }
    }

    @Test
    public void theGuardIsOffUntilAFeasibilityCheckStarts() {
        Assert.assertFalse(LLMFullController.isCheckingFeasibility());
    }

    @Test
    public void theGuardIsOnForTheLengthOfACheck() {
        LLMFullController.enterFeasibility();
        Assert.assertTrue(LLMFullController.isCheckingFeasibility());
        LLMFullController.exitFeasibility();
        Assert.assertFalse(LLMFullController.isCheckingFeasibility());
    }

    @Test
    public void anInnerCheckEndingDoesNotUnguardTheOuterOne() {
        // The bug this replaces: the guard was a boolean that each check set to
        // false when it finished. A feasibility check nested inside another one
        // therefore turned the guard off for the rest of the outer check, and
        // every question asked from there on spent an LLM call on a spell that
        // was only being priced.
        LLMFullController.enterFeasibility();
        LLMFullController.enterFeasibility();
        LLMFullController.exitFeasibility();
        Assert.assertTrue(LLMFullController.isCheckingFeasibility(),
                "the outer check is still running");
        LLMFullController.exitFeasibility();
        Assert.assertFalse(LLMFullController.isCheckingFeasibility());
    }

    @Test
    public void anUnbalancedExitCannotDriveTheCounterNegative() {
        // A stuck-off counter would be as bad as a stuck-on flag: it would take
        // the guard away from every later check on the same thread.
        LLMFullController.exitFeasibility();
        LLMFullController.exitFeasibility();
        LLMFullController.enterFeasibility();
        Assert.assertTrue(LLMFullController.isCheckingFeasibility());
        LLMFullController.exitFeasibility();
        Assert.assertFalse(LLMFullController.isCheckingFeasibility());
    }
}
