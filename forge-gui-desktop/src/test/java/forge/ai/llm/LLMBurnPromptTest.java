package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The two prompt defects that made an LLM-piloted Burn deck refuse to deal
 * damage, both taken from the recorded Burn-versus-Bogles run.
 *
 * <p><b>Hexproof read as protecting the player.</b> A legal spell aimed at the
 * opposing player appeared in 17 spell-choice calls and Burn passed in 11; seven
 * of those explanations blamed Hexproof. The smallest case: game 1, opponent end
 * step, both players at 20, Burn holding two Bolts with one untapped Mountain,
 * the opponent controlling a 1/1 hexproof Gladecover Scout. The option list said
 * {@code Lightning Bolt -> targeting Ai(2)-Bogles} — the player, not the Scout —
 * and the model passed, saying neither was a legal target. The reverse seating
 * repeated it in game 8 with {@code Lightning Bolt -> targeting Ai(1)-Bogles}.
 * Hexproof is a property of the permanent, and the engine had already checked
 * the target; the prompt now says both.
 *
 * <p><b>A pass rule written for a control deck.</b> At instant speed the prompt
 * named counters, removal and combat tricks as the only reasons to act. Those
 * are a control deck's reasons. Burn's reason to act is the opponent's life
 * total, and it was not on the list.
 */
public class LLMBurnPromptTest {

    @Test
    public void hexproofIsStatedToProtectOnlyThePermanent() {
        String prompt = PromptTemplates.SYSTEM_PROMPT;
        Assert.assertTrue(prompt.contains("Hexproof and Shroud protect only the permanent"),
                "the prompt must say hexproof protects the permanent, not its controller");
        Assert.assertTrue(prompt.contains("never protect"),
                "the prompt must say so in the negative too");
        Assert.assertTrue(prompt.contains("aimed at the opposing PLAYER is legal"),
                "the prompt must state the case that was actually misread");
    }

    @Test
    public void listedOptionsAreStatedToBeLegalAlready() {
        String prompt = PromptTemplates.SYSTEM_PROMPT;
        Assert.assertTrue(prompt.contains("already been checked by the game engine"),
                "the model must not re-derive legality it cannot see");
        Assert.assertTrue(prompt.contains("Never"),
                "and must be told not to pass on a legality doubt");
    }

    @Test
    public void instantSpeedNamesDamageToTheOpponentAsAReasonToAct() {
        String prompt = PromptTemplates.spellSelection("STATE", "OPTIONS", false);
        Assert.assertTrue(prompt.contains("push damage at the opponent's life total"),
                "the instant-speed pass rule must not be control-only");
        Assert.assertTrue(prompt.contains("a clock, not a reaction"),
                "and must say why burn is not held like removal");
        // The three original reasons stay.
        Assert.assertTrue(prompt.contains("counter, remove, set up a combat"));
    }

    @Test
    public void mainPhaseStillPrefersActing() {
        String prompt = PromptTemplates.spellSelection("STATE", "OPTIONS", true);
        Assert.assertTrue(prompt.contains("prefer acting over passing"),
                "the main-phase push must be untouched by the instant-speed fix");
        Assert.assertFalse(prompt.contains("Passing is usually correct"),
                "the instant-speed pass rule must not leak into main phases");
    }
}
