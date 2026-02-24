package forge.ai.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HTTP client for OpenAI-compatible chat completions API.
 * Works with OpenRouter, Ollama, OpenAI, or any compatible provider.
 * Thread-safe for parallel game execution.
 */
public class LLMClient {

    private final HttpClient httpClient;
    private final LLMConfig config;
    private final Gson gson;

    // Thread-safe aggregate counters
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger totalFallbacks = new AtomicInteger(0);
    private final AtomicInteger totalInputTokens = new AtomicInteger(0);
    private final AtomicInteger totalOutputTokens = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private volatile double estimatedCost = 0.0;
    private final Object costLock = new Object();

    public LLMClient(LLMConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getTimeoutMs()))
                .build();
    }

    /**
     * Send a chat completion request and return the assistant's response text.
     *
     * @param systemPrompt system message (MTG strategy context)
     * @param userPrompt   user message (game state + options)
     * @param callLabel    label for debug output (e.g., "chooseSpellAbilityToPlay")
     * @param playerName   player name for debug output
     * @return assistant's text response
     * @throws LLMException on error, timeout, or budget exceeded
     */
    public String chatCompletion(String systemPrompt, String userPrompt,
                                  String callLabel, String playerName) throws LLMException {
        // Check budget before making the call
        if (config.getBudgetLimit() > 0) {
            synchronized (costLock) {
                if (estimatedCost >= config.getBudgetLimit()) {
                    throw new BudgetExceededException(estimatedCost, config.getBudgetLimit());
                }
            }
        }

        int callNum = totalCalls.incrementAndGet();

        // Build request JSON
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("temperature", config.getTemperature());
        // Set max_tokens to bound token usage. Responses are short (numbers/indices),
        // but thinking models need room for chain-of-thought reasoning.
        int maxTokens = config.isThinkingModel() ? 16384 : 512;
        body.addProperty("max_tokens", maxTokens);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", systemPrompt);
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messages.add(userMsg);

        body.add("messages", messages);

        String url = config.getApiBaseUrl() + "/chat/completions";
        String requestBody = gson.toJson(body);

        // Build HTTP request
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody));

        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + config.getApiKey());
        }

        HttpRequest request = reqBuilder.build();

        // Execute with one retry
        long startTime = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // One retry with backoff
                Thread.sleep(1000);
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("LLM request interrupted", e);
        } catch (Exception e) {
            throw new LLMException("LLM request failed: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        totalLatencyMs.addAndGet(latencyMs);

        if (response.statusCode() >= 400) {
            throw new LLMException("LLM API error " + response.statusCode()
                    + ": " + response.body().substring(0, Math.min(200, response.body().length())));
        }

        // Parse response
        JsonObject respJson;
        try {
            respJson = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (Exception e) {
            throw new LLMException("Failed to parse LLM response JSON: " + e.getMessage(), e);
        }

        String content;
        try {
            content = respJson.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();
        } catch (Exception e) {
            throw new LLMException("Failed to extract content from LLM response: " + response.body(), e);
        }

        // Strip <think>...</think> tags from thinking models (some providers embed reasoning in content)
        content = ResponseParser.stripThinkingTags(content);

        // Extract token usage if available (some providers omit or null-out usage)
        int inputTokens = 0;
        int outputTokens = 0;
        try {
            if (respJson.has("usage") && respJson.get("usage") != null
                    && respJson.get("usage").isJsonObject()) {
                JsonObject usage = respJson.getAsJsonObject("usage");
                if (usage.has("prompt_tokens")) {
                    inputTokens = usage.get("prompt_tokens").getAsInt();
                    totalInputTokens.addAndGet(inputTokens);
                }
                if (usage.has("completion_tokens")) {
                    outputTokens = usage.get("completion_tokens").getAsInt();
                    totalOutputTokens.addAndGet(outputTokens);
                }
            }
        } catch (Exception e) {
            // Non-fatal: usage tracking is best-effort
        }

        // Estimate cost (conservative: $10/MTok input, $30/MTok output)
        if (config.getBudgetLimit() > 0) {
            double callCost = (inputTokens * 10.0 + outputTokens * 30.0) / 1_000_000.0;
            synchronized (costLock) {
                estimatedCost += callCost;
            }
        }

        // Debug output
        if (config.isDebug()) {
            printDebug(callNum, playerName, callLabel, systemPrompt, userPrompt,
                    content, latencyMs, inputTokens, outputTokens);
        }

        return content;
    }

    private void printDebug(int callNum, String playerName, String callLabel,
                             String systemPrompt, String userPrompt,
                             String response, long latencyMs,
                             int inputTokens, int outputTokens) {
        PrintStream err = System.err;
        // Use ORIGINAL_ERR if available, but System.err works for now
        err.println();
        err.printf("═══ LLM CALL #%d [%s: %s via %s] ═══%n",
                callNum, playerName, config.getModel(), config.getProvider());
        if (callNum == 1) {
            err.println("─── SYSTEM PROMPT ───");
            err.println(systemPrompt);
        }
        err.printf("─── USER PROMPT (%s) ──-%n", callLabel);
        err.println(userPrompt);
        err.printf("─── RESPONSE (%dms, %d+%d tokens) ──-%n",
                latencyMs, inputTokens, outputTokens);
        err.println(response);
        err.println("═══════════════════════════════════════════════");
    }

    // Stats getters
    public int getTotalCalls() { return totalCalls.get(); }
    public int getTotalFallbacks() { return totalFallbacks.get(); }
    public int getTotalInputTokens() { return totalInputTokens.get(); }
    public int getTotalOutputTokens() { return totalOutputTokens.get(); }
    public long getTotalLatencyMs() { return totalLatencyMs.get(); }
    public boolean isDebug() { return config.isDebug(); }
    public double getEstimatedCost() {
        synchronized (costLock) {
            return estimatedCost;
        }
    }

    /** Record that a fallback to heuristic occurred. */
    public void recordFallback() { totalFallbacks.incrementAndGet(); }

    // Config delegate getters for JSON output
    public LLMMode getMode() { return config.getMode(); }
    public String getModel() { return config.getModel(); }
    public String getProvider() { return config.getProvider(); }
}
