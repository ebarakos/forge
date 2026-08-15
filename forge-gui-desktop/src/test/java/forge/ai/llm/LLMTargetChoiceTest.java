package forge.ai.llm;

import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests the two halves of giving an LLM seat target choice that can be checked
 * without a running game: what the model is shown, and what is made of its
 * answer.
 *
 * <p>Neither half touches a provider. The prompt side takes candidates already
 * rendered to text, so the layout can be asserted directly; the answer side is
 * a pure function from response text to a list of option indices.
 */
public class LLMTargetChoiceTest {

    private static final List<String> CANDIDATES = Arrays.asList(
            "Opponent — 12 life (opponent)",
            "Kor Skyfisher (2/3) (opponent's)",
            "Ornithopter (0/2) (yours)");

    // ---- what the model is shown ------------------------------------------

    @Test
    public void everyCandidateIsNumberedFromZero() {
        String text = OptionSerializer.serializeTargetOptions(
                "Lightning Bolt — deals 3 damage to any target", CANDIDATES, 1, null);
        Assert.assertTrue(text.contains("\n0: Opponent — 12 life (opponent)\n"), text);
        Assert.assertTrue(text.contains("\n1: Kor Skyfisher (2/3) (opponent's)\n"), text);
        Assert.assertTrue(text.contains("\n2: Ornithopter (0/2) (yours)\n"), text);
    }

    @Test
    public void headerNamesTheSpellAndHowManyTargetsAreWanted() {
        String one = OptionSerializer.serializeTargetOptions(
                "Lightning Bolt — deals 3 damage to any target", CANDIDATES, 1, null);
        Assert.assertTrue(one.startsWith("CHOOSE TARGETS for Lightning Bolt — deals 3 damage to any target\n"), one);
        Assert.assertTrue(one.contains("Name exactly 1 target from the list below."), one);

        String two = OptionSerializer.serializeTargetOptions("Arc Trail", CANDIDATES, 2, null);
        Assert.assertTrue(two.contains("Name exactly 2 targets from the list below."), two);
    }

    @Test
    public void heuristicBaselineIsShownWhenThereIsOne() {
        Set<Integer> picks = new LinkedHashSet<>(Arrays.asList(1));
        String text = OptionSerializer.serializeTargetOptions("Lightning Bolt", CANDIDATES, 1, picks);
        Assert.assertTrue(text.contains("Heuristic baseline: 1. Diverge from this only with reason."), text);
        Assert.assertTrue(text.contains("1: Kor Skyfisher (2/3) (opponent's)  [heur: TARGET]"), text);
        // The options the heuristic passed over carry no marker at all.
        Assert.assertTrue(text.contains("\n0: Opponent — 12 life (opponent)\n"), text);
    }

    @Test
    public void noBaselineAnnotationWhenNoneIsSupplied() {
        String nullSet = OptionSerializer.serializeTargetOptions("Lightning Bolt", CANDIDATES, 1, null);
        String emptySet = OptionSerializer.serializeTargetOptions(
                "Lightning Bolt", CANDIDATES, 1, new LinkedHashSet<>());
        Assert.assertFalse(nullSet.contains("Heuristic baseline"), nullSet);
        Assert.assertFalse(nullSet.contains("[heur:"), nullSet);
        Assert.assertEquals(emptySet, nullSet);
    }

    @Test
    public void targetPromptCarriesDoctrineHistoryStateAndOptions() {
        String options = OptionSerializer.serializeTargetOptions("Lightning Bolt", CANDIDATES, 1, null);
        PromptTemplates.PromptParts parts =
                PromptTemplates.chooseTargets("\nRECENT ACTIONS:\n- Turn 1 MAIN1: Cast Mountain\n",
                        "YOUR LIFE: 20", options);
        // The doctrine and the history are the cacheable half...
        Assert.assertTrue(parts.stablePrefix.startsWith("TARGET DOCTRINE"), parts.stablePrefix);
        Assert.assertTrue(parts.stablePrefix.contains("RECENT ACTIONS"), parts.stablePrefix);
        Assert.assertFalse(parts.stablePrefix.contains("CHOOSE TARGETS for"), parts.stablePrefix);
        // ...the board and the candidate list are the half that must stay fresh.
        Assert.assertEquals(parts.volatileTail, "\nYOUR LIFE: 20\n" + options);
        // Whole prompt, in the order the model reads it.
        Assert.assertEquals(parts.concatenated(), parts.stablePrefix + parts.volatileTail);
    }

    /** A missing action history must not put a literal "null" in the prompt. */
    @Test
    public void absentActionHistoryAddsNothing() {
        String options = OptionSerializer.serializeTargetOptions("Lightning Bolt", CANDIDATES, 1, null);
        PromptTemplates.PromptParts none = PromptTemplates.chooseTargets(null, "YOUR LIFE: 20", options);
        PromptTemplates.PromptParts empty = PromptTemplates.chooseTargets("", "YOUR LIFE: 20", options);
        Assert.assertEquals(none.concatenated(), empty.concatenated());
        Assert.assertFalse(none.concatenated().contains("null"), none.concatenated());
    }

    /**
     * The doctrine must not tell the model which way to point a burn spell.
     * Pushing burn at the opponent's face unconditionally was tried on the
     * heuristic side and lost considerably more games than it won, so the text
     * has to pose the question rather than answer it.
     */
    @Test
    public void doctrineDoesNotPreferFaceOverCreatures() {
        String doctrine = PromptTemplates.chooseTargets(null, "", "").stablePrefix;
        Assert.assertTrue(doctrine.contains("Take the creature when"), doctrine);
        Assert.assertTrue(doctrine.contains("take the opponent when"), doctrine);
    }

    // ---- what is made of the answer ---------------------------------------

    @Test
    public void readsTheTargetsFieldOfASchemaAnswer() {
        Assert.assertEquals(
                ResponseParser.parseTargetIndices("{\"reasoning\":\"race\",\"targets\":[0]}", 3),
                Arrays.asList(0));
        Assert.assertEquals(
                ResponseParser.parseTargetIndices("{\"targets\":[2,1]}", 3),
                Arrays.asList(2, 1));
    }

    @Test
    public void keepsTheOrderTheModelAsked() {
        Assert.assertEquals(
                ResponseParser.parseTargetIndices("{\"targets\":[2,0,1]}", 3),
                Arrays.asList(2, 0, 1));
    }

    @Test
    public void dropsRepeatsAndIndicesThatNameNoCandidate() {
        // 7 is not on the list and 1 is named twice; the rest of the answer stands.
        Assert.assertEquals(
                ResponseParser.parseTargetIndices("{\"targets\":[1,7,1,0,-2]}", 3),
                Arrays.asList(1, 0));
    }

    @Test
    public void emptyOrUnusableAnswersProduceNoTargets() {
        Assert.assertTrue(ResponseParser.parseTargetIndices("{\"targets\":[]}", 3).isEmpty());
        Assert.assertTrue(ResponseParser.parseTargetIndices("", 3).isEmpty());
        Assert.assertTrue(ResponseParser.parseTargetIndices(null, 3).isEmpty());
        Assert.assertTrue(ResponseParser.parseTargetIndices("{\"targets\":[9]}", 3).isEmpty());
        // No candidates offered means no answer can be usable.
        Assert.assertTrue(ResponseParser.parseTargetIndices("{\"targets\":[0]}", 0).isEmpty());
    }

    @Test
    public void fallsBackToReadingNumbersOutOfProse() {
        // A model that ignores the schema still gets read, in the order written.
        Assert.assertEquals(ResponseParser.parseTargetIndices("I target 2, then 0.", 3),
                Arrays.asList(2, 0));
        Assert.assertEquals(ResponseParser.parseTargetIndices("target 1", 3), Arrays.asList(1));
    }

    @Test
    public void codeFencedJsonIsStillJson() {
        Assert.assertEquals(
                ResponseParser.parseTargetIndices("```json\n{\"targets\":[1]}\n```", 3),
                Arrays.asList(1));
    }

    // ---- the schema sent to the provider ----------------------------------

    @Test
    public void targetSchemaAsksForAnIntegerArrayCalledTargets() {
        JsonObject schema = LLMResponseSchema.TARGETS.toJsonSchema();
        JsonObject props = schema.getAsJsonObject("properties");
        Assert.assertTrue(props.has("targets"), schema.toString());
        JsonObject targets = props.getAsJsonObject("targets");
        Assert.assertEquals(targets.get("type").getAsString(), "array");
        Assert.assertEquals(targets.getAsJsonObject("items").get("type").getAsString(), "integer");
        Assert.assertTrue(targets.get("description").getAsString().contains("target"));
        Assert.assertEquals(schema.getAsJsonArray("required").size(), 2); // reasoning + targets
    }

    /**
     * Adding the targets schema must not have altered any of the schemas that
     * were already in use — a changed description changes the prompt bytes and
     * therefore every provider prompt-cache hit.
     */
    @Test
    public void theOlderSchemasAreUnchanged() {
        Assert.assertEquals(descriptionOf(LLMResponseSchema.CHOICE),
                "The chosen option index from OPTIONS.");
        Assert.assertEquals(descriptionOf(LLMResponseSchema.INDICES),
                "Ordered indices of options to take. Empty array means none / PASS.");
        Assert.assertEquals(descriptionOf(LLMResponseSchema.PLAN),
                "Ordered indices of options to take. Empty array means none / PASS.");
        Assert.assertEquals(descriptionOf(LLMResponseSchema.BLOCKS),
                "Block assignments: each entry maps an attacker index to a list of blocker indices."
                        + " Empty array means no blocks.");
    }

    private static String descriptionOf(LLMResponseSchema schema) {
        return schema.toJsonSchema().getAsJsonObject("properties")
                .getAsJsonObject(schema.fieldName()).get("description").getAsString();
    }
}
