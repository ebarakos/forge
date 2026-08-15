package forge.ai.llm;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests the seat profile string an LLM player is created from.
 *
 * <p>An LLM seat is a Forge AI seat with a model attached: the heuristic AI
 * underneath still decides everything the model is not asked about, and reads
 * its dials from an {@code .ai} profile. That profile was pinned to
 * {@code Default} for every LLM seat, so an LLM seat could not be run under any
 * of the tuned profiles a heuristic seat can use — which made a matched
 * comparison between the two impossible. A profile may now be named after
 * {@code '@'}, and leaving it out must behave exactly as before.
 */
public class LLMSeatProfileTest {

    @Test
    public void aProfileWithoutASeparatorIsUnchanged() {
        Assert.assertEquals(LLMConfig.modelPart("cerebras:gpt-oss-120b"), "cerebras:gpt-oss-120b");
        Assert.assertEquals(LLMConfig.aiProfilePart("cerebras:gpt-oss-120b"), "",
                "no name after '@' means the seat keeps the default AI profile");
    }

    @Test
    public void theAiProfileIsReadFromAfterTheSeparator() {
        Assert.assertEquals(LLMConfig.modelPart("cerebras:gpt-oss-120b@Cautious"),
                "cerebras:gpt-oss-120b");
        Assert.assertEquals(LLMConfig.aiProfilePart("cerebras:gpt-oss-120b@Cautious"), "Cautious");
    }

    @Test
    public void theSeparatorWorksOnEveryRoutingForm() {
        Assert.assertEquals(LLMConfig.modelPart("relay:openai-compat:my-model@Reckless"),
                "relay:openai-compat:my-model");
        Assert.assertEquals(LLMConfig.aiProfilePart("relay:openai-compat:my-model@Reckless"),
                "Reckless");
        Assert.assertEquals(LLMConfig.modelPart("direct:openai:gpt-4o-mini@Experimental"),
                "direct:openai:gpt-4o-mini");
        Assert.assertEquals(LLMConfig.aiProfilePart("direct:openai:gpt-4o-mini@Experimental"),
                "Experimental");
    }

    @Test
    public void aSeatCarryingAnAiProfileIsStillAnLlmSeat() {
        // The routing check has to look past the '@', or a seat that names a
        // profile would quietly be built as a plain heuristic player.
        Assert.assertTrue(LLMConfig.isLlmProfile("cerebras:gpt-oss-120b"));
        Assert.assertTrue(LLMConfig.isLlmProfile("cerebras:gpt-oss-120b@Cautious"));
        Assert.assertTrue(LLMConfig.isLlmProfile("direct:openai:gpt-4o-mini@Cautious"));
    }

    @Test
    public void anOrdinaryAiProfileIsNotMistakenForAnLlmSeat() {
        Assert.assertFalse(LLMConfig.isLlmProfile("Default"));
        Assert.assertFalse(LLMConfig.isLlmProfile("Cautious"));
        Assert.assertFalse(LLMConfig.isLlmProfile(""));
        Assert.assertFalse(LLMConfig.isLlmProfile(null));
    }

    @Test
    public void surroundingSpaceInTheProfileNameIsIgnored() {
        Assert.assertEquals(LLMConfig.aiProfilePart("cerebras:gpt-oss-120b@  Cautious  "),
                "Cautious");
    }

    @Test
    public void anEmptyNameAfterTheSeparatorMeansTheDefaultProfile() {
        Assert.assertEquals(LLMConfig.aiProfilePart("cerebras:gpt-oss-120b@"), "");
        Assert.assertTrue(LLMConfig.isLlmProfile("cerebras:gpt-oss-120b@"));
    }
}
