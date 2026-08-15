package forge.ai.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Tests for {@link LLMStrictMode} and its wiring into {@link LLMClient}.
 *
 * <p>No LLM is involved: the fallback counter and the strict-mode decision are
 * both reachable without making a call. The abort action is swapped for one that
 * records the message, so the test JVM survives.
 */
public class LLMStrictModeTest {

    private final List<String> aborts = new ArrayList<>();
    private Consumer<String> previousAborter;

    @BeforeMethod
    public void installRecordingAborter() {
        aborts.clear();
        previousAborter = LLMStrictMode.setAborterForTesting(aborts::add);
    }

    @AfterMethod
    public void restoreDefaults() {
        LLMStrictMode.setAborterForTesting(previousAborter);
        LLMStrictMode.setEnabledForTesting(null);
    }

    private static LLMClient clientFor(String provider, String relayProvider, String model) {
        return new LLMClient(new LLMConfig.Builder()
                .provider(provider)
                .relayProvider(relayProvider)
                .model(model)
                .build());
    }

    // ---- the truthiness rule ----------------------------------------------

    @Test
    public void unsetOrFalsyValuesLeaveStrictModeOff() {
        Assert.assertFalse(LLMStrictMode.isTruthy(null), "unset means off");
        Assert.assertFalse(LLMStrictMode.isTruthy(""), "empty means off");
        Assert.assertFalse(LLMStrictMode.isTruthy("0"), "0 means off");
        Assert.assertFalse(LLMStrictMode.isTruthy("false"), "false means off");
        Assert.assertFalse(LLMStrictMode.isTruthy("FALSE"), "case does not matter");
    }

    @Test
    public void anyOtherValueTurnsStrictModeOn() {
        Assert.assertTrue(LLMStrictMode.isTruthy("1"));
        Assert.assertTrue(LLMStrictMode.isTruthy("true"));
        Assert.assertTrue(LLMStrictMode.isTruthy("yes"));
    }

    // ---- the fallback decision --------------------------------------------

    @Test
    public void fallbackIsIgnoredWhenStrictModeIsOff() {
        LLMStrictMode.setEnabledForTesting(false);
        LLMStrictMode.onFallback("Ai(1)-Burn", "relay→cerebras:qwen", "timeout", 7, 1);
        Assert.assertTrue(aborts.isEmpty(), "strict mode off must not stop the run");
    }

    @Test
    public void fallbackStopsTheRunWhenStrictModeIsOn() {
        LLMStrictMode.setEnabledForTesting(true);
        LLMStrictMode.onFallback("Ai(1)-Burn", "relay→cerebras:qwen",
                "chooseSpellAbilityToPlay: HttpTimeoutException - request timed out", 7, 1);

        Assert.assertEquals(aborts.size(), 1, "the first fallback must stop the run");
        String message = aborts.get(0);
        Assert.assertTrue(message.contains("Ai(1)-Burn"), "names the seat: " + message);
        Assert.assertTrue(message.contains("relay→cerebras:qwen"), "names the endpoint: " + message);
        Assert.assertTrue(message.contains("HttpTimeoutException"), "names the failure: " + message);
        Assert.assertTrue(message.contains("LLM calls so far: 7"), "names the call count: " + message);
        Assert.assertTrue(message.contains("fallbacks so far: 1"), "names the fallback count: " + message);
        Assert.assertTrue(message.contains(LLMStrictMode.ENV_VAR), "says which switch did this: " + message);
    }

    @Test
    public void missingFieldsStillProduceAReadableMessage() {
        String message = LLMStrictMode.fallbackMessage(null, null, null, 0, 1);
        Assert.assertTrue(message.contains("<unnamed>"), message);
        Assert.assertTrue(message.contains("<unknown endpoint>"), message);
        Assert.assertTrue(message.contains("<no reason recorded>"), message);
    }

    // ---- wiring through LLMClient -----------------------------------------

    @Test
    public void clientCountsFallbacksAndLeavesTheRunAloneByDefault() {
        LLMStrictMode.setEnabledForTesting(false);
        LLMClient client = clientFor("cerebras", null, "qwen-3");

        client.recordFallback();
        client.recordFallback("Ai(2)-Faeries", "parse failed");

        Assert.assertEquals(client.getTotalFallbacks(), 2, "both fallbacks counted");
        Assert.assertTrue(aborts.isEmpty(), "default behaviour is unchanged");
    }

    @Test
    public void clientFallbackStopsTheRunUnderStrictModeAndNamesTheRouting() {
        LLMStrictMode.setEnabledForTesting(true);
        LLMClient client = clientFor("relay", "cerebras", "qwen-3-235b");

        client.recordFallback("Ai(2)-Faeries", "declareBlockers: unusable answer");

        Assert.assertEquals(client.getTotalFallbacks(), 1, "the fallback is still counted");
        Assert.assertEquals(aborts.size(), 1, "and it stops the run");
        String message = aborts.get(0);
        Assert.assertTrue(message.contains("Ai(2)-Faeries"), message);
        Assert.assertTrue(message.contains("relay→cerebras:qwen-3-235b"), message);
        Assert.assertTrue(message.contains("declareBlockers"), message);
    }

    @Test
    public void endpointDescriptionOmitsTheArrowForDirectProviders() {
        Assert.assertEquals(clientFor("cerebras", null, "qwen-3").describeEndpoint(),
                "cerebras:qwen-3");
        Assert.assertEquals(clientFor("relay", "openrouter", "some/model").describeEndpoint(),
                "relay→openrouter:some/model");
    }

    // ---- the two startup / end-of-run messages ----------------------------

    @Test
    public void seatThatCouldNotBuildAClientIsNamedWithItsCause() {
        String message = LLMStrictMode.silentHeuristicSeatMessage(
                "player 1", "openai-compat:gpt-oss-20b", "OPENAI_COMPAT_URL is not set");
        Assert.assertTrue(message.contains("player 1"), message);
        Assert.assertTrue(message.contains("openai-compat:gpt-oss-20b"), message);
        Assert.assertTrue(message.contains("OPENAI_COMPAT_URL is not set"), message);
        Assert.assertTrue(message.contains("heuristic"), "says what would have happened: " + message);
    }

    @Test
    public void seatWithAClientButNoCallsIsNamedToo() {
        String message = LLMStrictMode.zeroCallSeatMessage("player 2", "relay→cerebras:qwen-3");
        Assert.assertTrue(message.contains("player 2"), message);
        Assert.assertTrue(message.contains("relay→cerebras:qwen-3"), message);
        Assert.assertTrue(message.contains("0 LLM calls"), message);
    }

    @Test
    public void everyStrictMessageCarriesTheExitStatus() {
        String exit = String.valueOf(LLMStrictMode.EXIT_CODE);
        Assert.assertTrue(LLMStrictMode.fallbackMessage("s", "e", "r", 1, 1).contains(exit));
        Assert.assertTrue(LLMStrictMode.silentHeuristicSeatMessage("s", "p", "c").contains(exit));
        Assert.assertTrue(LLMStrictMode.zeroCallSeatMessage("s", "e").contains(exit));
    }
}
