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

    // Shared rate gate across all LLMClient instances (per JVM). Serialises calls
    // that target the same upstream provider so parallel games don't burst past RPM limits.
    private static final Object THROTTLE_LOCK = new Object();
    private static long lastCallEndNanos = 0L;

    // Thread-safe aggregate counters
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger totalFallbacks = new AtomicInteger(0);
    private final AtomicInteger totalInputTokens = new AtomicInteger(0);
    private final AtomicInteger totalOutputTokens = new AtomicInteger(0);
    private final AtomicInteger totalCacheReadTokens = new AtomicInteger(0);
    private final AtomicInteger totalCacheCreationTokens = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    /** If the first caching request is rejected, disable for the rest of the session. */
    private volatile boolean cachingDisabledForSession = false;
    /** If the first structured-output request is rejected, disable for the rest of the session. */
    private volatile boolean structuredOutputDisabledForSession = false;

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
        return chatCompletion(systemPrompt, userPrompt, callLabel, playerName, null);
    }

    /**
     * Send a chat completion request with an optional response schema. When a
     * schema is provided, structured output is requested via {@code response_format}
     * (OpenAI-style) or {@code format} (Ollama). The returned content is the
     * assistant message — typically valid JSON conforming to the schema, or the
     * legacy plain-text format if structured output was rejected for the session.
     */
    public String chatCompletion(String systemPrompt, String userPrompt,
                                  String callLabel, String playerName,
                                  LLMResponseSchema schema) throws LLMException {
        return chatCompletion(systemPrompt, null, userPrompt, callLabel, playerName, schema);
    }

    /**
     * D2: Send a chat completion request with the user prompt split into a
     * byte-stable prefix and a volatile tail. When prompt caching is enabled
     * (and not session-disabled) and {@code stablePrefix} is non-empty, the
     * user message is sent as a content-block array: block 1 carries the
     * stable prefix (per-decision doctrine + append-only action history) with
     * {@code cache_control: ephemeral}; block 2 carries the volatile tail
     * (current game state + options) uncached. Otherwise the two parts are
     * concatenated into the legacy plain-string user message. A provider
     * 400/422 flips the same session-level guard as the system-message block
     * format, so subsequent calls fall back to plain strings.
     */
    public String chatCompletion(String systemPrompt, String stablePrefix, String volatileTail,
                                  String callLabel, String playerName,
                                  LLMResponseSchema schema) throws LLMException {
        int callNum = totalCalls.incrementAndGet();

        // Build request JSON
        JsonObject body = new JsonObject();
        body.addProperty("model", config.getModel());
        body.addProperty("temperature", config.getTemperature());
        // Set max_tokens to bound token usage. Responses include a short
        // reasoning string + the structured payload, plus thinking-model CoT.
        int maxTokens = config.isThinkingModel() ? 16384 : 1024;
        body.addProperty("max_tokens", maxTokens);

        // Inject structured output (json_schema for cloud / format for ollama)
        // unless the provider has rejected it earlier this session.
        if (schema != null && !structuredOutputDisabledForSession) {
            JsonObject jsonSchema = schema.toJsonSchema(!config.omitReasoning());
            if ("ollama".equals(config.getProvider())) {
                // Ollama's OpenAI-compatible endpoint accepts response_format too,
                // but raw schema in `format` is documented and works on older builds.
                body.add("format", jsonSchema);
            } else {
                JsonObject responseFormat = new JsonObject();
                responseFormat.addProperty("type", "json_schema");
                JsonObject jsonSchemaWrapper = new JsonObject();
                jsonSchemaWrapper.addProperty("name", "decision_" + schema.fieldName());
                jsonSchemaWrapper.add("schema", jsonSchema);
                jsonSchemaWrapper.addProperty("strict", true);
                responseFormat.add("json_schema", jsonSchemaWrapper);
                body.add("response_format", responseFormat);
            }
        }

        boolean cachingActive = config.isPromptCachingEnabled() && !cachingDisabledForSession;
        // Single-string view of the user prompt (non-caching path + debug output).
        String fullUserPrompt = (stablePrefix == null || stablePrefix.isEmpty())
                ? volatileTail : stablePrefix + volatileTail;

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        if (cachingActive) {
            // D2: Anthropic-style content-block format with cache_control:ephemeral
            // on the (stable) system prompt so the provider caches it for subsequent calls.
            JsonArray contentArr = new JsonArray();
            JsonObject stablePart = new JsonObject();
            stablePart.addProperty("type", "text");
            stablePart.addProperty("text", systemPrompt);
            JsonObject cacheControl = new JsonObject();
            cacheControl.addProperty("type", "ephemeral");
            stablePart.add("cache_control", cacheControl);
            contentArr.add(stablePart);
            sysMsg.add("content", contentArr);
        } else {
            sysMsg.addProperty("content", systemPrompt);
        }
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        if (cachingActive && stablePrefix != null && !stablePrefix.isEmpty()) {
            // D2: split user message — block 1 = byte-stable prefix (doctrine +
            // append-only action history) with cache_control:ephemeral; block 2 =
            // volatile tail (current game state + options), uncached. Goes through
            // the same 400/422 session fallback as the system block format above.
            JsonArray userContent = new JsonArray();
            JsonObject stableBlock = new JsonObject();
            stableBlock.addProperty("type", "text");
            stableBlock.addProperty("text", stablePrefix);
            JsonObject userCacheControl = new JsonObject();
            userCacheControl.addProperty("type", "ephemeral");
            stableBlock.add("cache_control", userCacheControl);
            userContent.add(stableBlock);
            JsonObject volatileBlock = new JsonObject();
            volatileBlock.addProperty("type", "text");
            volatileBlock.addProperty("text", volatileTail);
            userContent.add(volatileBlock);
            userMsg.add("content", userContent);
        } else {
            userMsg.addProperty("content", fullUserPrompt);
        }
        messages.add(userMsg);

        body.add("messages", messages);

        // Inject upstream provider for relay routing (equivalent to relay-fetch body.provider injection)
        if ("relay".equals(config.getProvider())) {
            String relayProvider = config.getRelayProvider();
            if (relayProvider != null && !relayProvider.isEmpty()) {
                body.addProperty("provider", relayProvider);
            }
        }

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
        // Forward BYO key to relay as X-User-Api-Key (takes priority over relay-managed keys)
        if (config.getUserApiKey() != null && !config.getUserApiKey().isEmpty()) {
            reqBuilder.header("X-User-Api-Key", config.getUserApiKey());
        }
        // Forward custom upstream URL to the relay as X-Custom-Url (server-side
        // proxy to a self-hosted OpenAI-compatible endpoint, e.g. LM Studio).
        if (config.getCustomUrl() != null && !config.getCustomUrl().isEmpty()) {
            reqBuilder.header("X-Custom-Url", config.getCustomUrl());
        }
        // Tier override for relay-managed keys — without this, the relay caps
        // requests at the free-tier model whitelist and 403s on paid models
        // (e.g. "Model 'openai/gpt-4.1-mini' is not available on the 'free' tier").
        // Set FORGE_LLM_KEY_TIER=all in env to unlock the relay's full catalog.
        // Default: unset → relay-side default (free tier).
        if ("relay".equals(config.getProvider())) {
            String keyTier = LLMConfig.loadProviderApiKey("FORGE_LLM_KEY_TIER");
            if (keyTier != null && !keyTier.isEmpty()) {
                reqBuilder.header("X-User-Key-Tier", keyTier);
            }
        }

        HttpRequest request = reqBuilder.build();

        // Execute with throttle + retry (exponential backoff on rate-limit / overload).
        long startTime = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            throttleBeforeCall();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Up to 3 retries on transient errors; longer backoff for 429/503.
            int attempt = 0;
            final int maxRetries = 3;
            long backoffMs = 2000L;
            while (response.statusCode() >= 400 && attempt < maxRetries) {
                int status = response.statusCode();
                // Relay quota errors (source:"relay") are non-retriable — throw immediately.
                if (status == 429 && isRelayRateLimit(response.body())) {
                    throw new LLMException("Relay quota exceeded: "
                            + response.body().substring(0, Math.min(200, response.body().length())));
                }
                boolean rateLimited = (status == 429 || status == 503);
                long sleepMs = rateLimited ? backoffMs : 1000L;
                Thread.sleep(sleepMs);
                if (rateLimited) backoffMs *= 2;
                attempt++;
                throttleBeforeCall();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
        } catch (LLMException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LLMException("LLM request interrupted", e);
        } catch (Exception e) {
            throw new LLMException("LLM request failed: " + e.getMessage(), e);
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        totalLatencyMs.addAndGet(latencyMs);

        if (response.statusCode() >= 400) {
            String errBody = response.body();
            String errSnippet = errBody.substring(0, Math.min(400, errBody.length()));
            // Disable caching for the session if the provider rejected the
            // content-block format. Next call will use the legacy string format.
            if (config.isPromptCachingEnabled() && !cachingDisabledForSession
                    && (response.statusCode() == 400 || response.statusCode() == 422)) {
                cachingDisabledForSession = true;
                if (config.isDebug()) {
                    System.err.println("[LLM] Prompt caching rejected by provider, disabling for session. Body: "
                            + errSnippet);
                }
            }
            // Disable structured output for the session if the provider rejected
            // the response_format / format field. Next call will use plain text.
            String lower = errSnippet.toLowerCase();
            boolean schemaRejected = schema != null && !structuredOutputDisabledForSession
                    && (response.statusCode() == 400 || response.statusCode() == 422)
                    && (lower.contains("response_format") || lower.contains("json_schema")
                        || lower.contains("\"format\"") || lower.contains("schema"));
            if (schemaRejected) {
                structuredOutputDisabledForSession = true;
                if (config.isDebug()) {
                    System.err.println("[LLM] Structured output rejected by provider, disabling for session. Body: "
                            + errSnippet);
                }
            }
            throw new LLMException("LLM API error " + response.statusCode() + ": " + errSnippet);
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
        int cacheRead = 0;
        int cacheCreation = 0;
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
                // D2: cache usage fields (Anthropic via OpenRouter / Cerebras).
                if (usage.has("cache_read_input_tokens")) {
                    cacheRead = usage.get("cache_read_input_tokens").getAsInt();
                    totalCacheReadTokens.addAndGet(cacheRead);
                } else if (usage.has("prompt_tokens_details")
                        && usage.get("prompt_tokens_details").isJsonObject()) {
                    JsonObject details = usage.getAsJsonObject("prompt_tokens_details");
                    if (details.has("cached_tokens")) {
                        cacheRead = details.get("cached_tokens").getAsInt();
                        totalCacheReadTokens.addAndGet(cacheRead);
                    }
                }
                if (usage.has("cache_creation_input_tokens")) {
                    cacheCreation = usage.get("cache_creation_input_tokens").getAsInt();
                    totalCacheCreationTokens.addAndGet(cacheCreation);
                }
            }
        } catch (Exception e) {
            // Non-fatal: usage tracking is best-effort
        }

        // Debug output
        if (config.isDebug()) {
            printDebug(callNum, playerName, callLabel, systemPrompt, fullUserPrompt,
                    content, latencyMs, inputTokens, outputTokens);
        }

        return content;
    }

    /**
     * Enforces the configured minimum gap between calls. Uses a JVM-wide lock so that
     * parallel games sharing the same upstream provider don't burst past its RPM limit.
     */
    private void throttleBeforeCall() throws InterruptedException {
        int minIntervalMs = config.getMinIntervalMs();
        if (minIntervalMs <= 0) return;
        long sleepMs;
        synchronized (THROTTLE_LOCK) {
            long nowNanos = System.nanoTime();
            long elapsedMs = (nowNanos - lastCallEndNanos) / 1_000_000L;
            sleepMs = minIntervalMs - elapsedMs;
            // Reserve the slot BEFORE sleeping so concurrent callers queue up cleanly.
            lastCallEndNanos = nowNanos + Math.max(0L, sleepMs) * 1_000_000L;
        }
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }
    }

    /** Returns true if the 429 body indicates a relay quota error (non-retriable). */
    private boolean isRelayRateLimit(String body) {
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("source") && "relay".equals(obj.get("source").getAsString())) return true;
            if (obj.has("error") && obj.get("error").isJsonObject()) {
                JsonObject err = obj.getAsJsonObject("error");
                if (err.has("source") && "relay".equals(err.get("source").getAsString())) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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
    public int getTotalCacheReadTokens() { return totalCacheReadTokens.get(); }
    public int getTotalCacheCreationTokens() { return totalCacheCreationTokens.get(); }
    public long getTotalLatencyMs() { return totalLatencyMs.get(); }
    public boolean isDebug() { return config.isDebug(); }
    /** Whether prompt caching is configured on (ignores the session-level fallback). */
    public boolean isPromptCachingEnabled() { return config.isPromptCachingEnabled(); }

    /** Record that a fallback to heuristic occurred, without naming the seat or the failure. */
    public void recordFallback() { recordFallback(null, null); }

    /**
     * Record that an LLM call fell back to the heuristic AI.
     *
     * <p>Every fallback path funnels through here, so this is also where
     * {@link LLMStrictMode} gets its chance to end the run rather than let a
     * heuristic decision pass as a model decision.
     *
     * @param playerName the seat whose call failed
     * @param reason     what went wrong (timeout, HTTP status, unusable answer)
     */
    public void recordFallback(String playerName, String reason) {
        int fallbacks = totalFallbacks.incrementAndGet();
        if (LLMStrictMode.isEnabled()) {
            LLMStrictMode.onFallback(playerName, describeEndpoint(), reason,
                    totalCalls.get(), fallbacks);
        }
    }

    /** Human-readable routing for this client, e.g. {@code relay→cerebras:qwen-3}. */
    public String describeEndpoint() {
        String upstream = (config.getRelayProvider() != null && !config.getRelayProvider().isEmpty())
                ? "→" + config.getRelayProvider() : "";
        return config.getProvider() + upstream + ":" + config.getModel();
    }

    // Config delegate getters for JSON output
    public String getModel() { return config.getModel(); }
    public String getProvider() { return config.getProvider(); }
}
