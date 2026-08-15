package forge.ai.llm;

/**
 * Immutable per-player LLM configuration.
 */
public final class LLMConfig {

    /**
     * Single GUI dropdown entry that opens the {@code LlmConfigDialog} for
     * per-seat provider/model selection. Replaces the four legacy
     * {@code "LLM (Provider)"} entries removed in Phase 2.
     */
    public static final String LLM_DIALOG_DISPLAY = "LLM…"; // "LLM…"

    // Default models for each provider
    private static final String DEFAULT_LOCAL_MODEL = "llama3";
    // Defaults track the curated model catalog used by the relay gateway.
    // Both upstream defaults are free-tier and were the only accepted models
    // on those upstreams when these defaults were last updated.
    private static final String DEFAULT_OPENROUTER_MODEL = "inclusionai/ling-2.6-1t:free";
    private static final String DEFAULT_CEREBRAS_MODEL = "qwen-3-235b-a22b-instruct-2507";
    // OpenAI-compatible endpoint default — points at the model name; the URL
    // itself comes from OPENAI_COMPAT_URL (no default endpoint URL).
    private static final String DEFAULT_CUSTOM_MODEL = "openai/gpt-oss-20b";

    // Default timeouts per provider (local models need longer for cold starts / model loading)
    private static final int DEFAULT_LOCAL_TIMEOUT_MS = 300_000;   // 5 minutes (reasoning models are verbose)
    private static final int DEFAULT_CLOUD_TIMEOUT_MS = 30_000;    // 30 seconds

    // Minimum inter-call gap to stay under provider RPM limits.
    // Cerebras free tier = 30 RPM → 2100ms per call (with 5% safety margin).
    private static final int CEREBRAS_MIN_INTERVAL_MS = 2100;

    // Provider canonical names used in the profile grammar `<provider>:<model>`.
    private static final java.util.Set<String> SUPPORTED_PROVIDERS = new java.util.HashSet<>(java.util.Arrays.asList(
            "cerebras", "openrouter", "groq", "openai", "anthropic", "ollama", "openai-compat"));

    // Print each one-shot deprecation warning at most once per JVM run.
    private static final java.util.Set<String> WARNED_DEPRECATIONS = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final String apiBaseUrl;
    private final String apiKey;          // nullable for local Ollama
    private final String model;
    private final String provider;        // "ollama", "openrouter", "cerebras", "groq", "openai", "anthropic", "openai-compat", or "relay"
    private final String relayProvider;   // upstream provider when using relay (e.g. "cerebras", "custom")
    private final String userApiKey;      // BYO key forwarded as X-User-Api-Key when using relay
    private final String customUrl;       // upstream URL when relayProvider == "custom" (forwarded as X-Custom-Url)
    private final double temperature;
    private final int timeoutMs;
    private final int minIntervalMs;      // minimum gap between calls to respect provider RPM limits
    private final boolean debug;
    /** Anthropic-style prompt caching (cache_control: ephemeral). */
    private final boolean promptCaching;

    private LLMConfig(Builder b) {
        this.apiBaseUrl = b.apiBaseUrl;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.provider = b.provider;
        this.relayProvider = b.relayProvider;
        this.userApiKey = b.userApiKey;
        this.customUrl = b.customUrl;
        this.temperature = b.temperature;
        this.timeoutMs = b.timeoutMs;
        this.minIntervalMs = b.minIntervalMs;
        this.debug = b.debug;
        this.promptCaching = b.promptCaching;
    }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    /** Upstream provider the relay should route to (e.g. "cerebras"). Null for non-relay providers. */
    public String getRelayProvider() { return relayProvider; }
    /** BYO key forwarded as X-User-Api-Key to the relay. Null if not set. */
    public String getUserApiKey() { return userApiKey; }
    /** Upstream URL forwarded as X-Custom-Url when relayProvider == "custom". Null otherwise. */
    public String getCustomUrl() { return customUrl; }
    public double getTemperature() { return temperature; }
    public int getTimeoutMs() { return timeoutMs; }
    /** Minimum milliseconds between LLM calls (client-side throttle). 0 = no throttle. */
    public int getMinIntervalMs() { return minIntervalMs; }
    public boolean isDebug() { return debug; }
    public boolean isPromptCachingEnabled() { return promptCaching; }

    /** Returns true if this model is a thinking/reasoning model that produces chain-of-thought tokens. */
    public boolean isThinkingModel() {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.contains("-r1") || lower.contains("thinking") || lower.contains("reasoning") || lower.contains("gemini-2.5");
    }

    /**
     * Small/cheap models (e.g. local gpt-oss-20b, llama-3.1-8b) whose
     * "reasoning" output is mostly truncated filler ("The?…..??………………") and
     * actively harms decision quality. For these we drop the reasoning field
     * from the JSON schema and force the model to commit to a choice directly.
     * Override via {@code FORGE_LLM_OMIT_REASONING=1|0} env var.
     */
    public boolean omitReasoning() {
        String override = System.getenv("FORGE_LLM_OMIT_REASONING");
        if (override != null && !override.isEmpty()) {
            return !"0".equals(override) && !"false".equalsIgnoreCase(override);
        }
        if (isThinkingModel()) return false;  // thinking models benefit from CoT
        if (model == null) return false;
        String lower = model.toLowerCase();
        // Known small/weak models where the reasoning field hurts more than it helps.
        return lower.contains("gpt-oss-20b") || lower.contains("llama-3.1-8b")
                || lower.contains("llama3.1-8b") || lower.contains("gemma-3-4b")
                || lower.contains("phi-3-mini") || lower.contains("phi3-mini");
    }

    /**
     * Whether LLM traffic should default to going through the configured relay
     * gateway. Reads {@code LLM_VIA_RELAY} (preferred) then {@code RELAY} from
     * env / .env, defaulting to {@code true} when unset. Any value other than
     * {@code 0} / {@code false} (case-insensitive) is treated as truthy.
     */
    public static boolean isRelayDefault() {
        String v = loadProviderApiKey("LLM_VIA_RELAY");
        if (v == null || v.isEmpty()) {
            v = loadProviderApiKey("RELAY");
        }
        if (v == null || v.isEmpty()) return true;
        String t = v.trim();
        return !"0".equals(t) && !"false".equalsIgnoreCase(t);
    }

    /**
     * Build the canonical (unprefixed) profile string for a provider/model
     * pair. Routing is decided at parse time by {@link #isRelayDefault()}; the
     * caller can prepend {@code relay:} or {@code direct:} to force a specific
     * path.
     */
    public static String canonicalProfileString(String provider, String model) {
        if (provider == null || provider.isEmpty()) return null;
        if (model == null) model = "";
        return provider + ":" + model;
    }

    /**
     * Parse a profile string into an LLMConfig. Returns null if the string is
     * not an LLM profile or required env vars are missing.
     *
     * <p>Grammar (Phase 1):
     * <ul>
     *   <li>{@code <provider>:<model>} — routing decided by {@link #isRelayDefault()}</li>
     *   <li>{@code relay:<provider>:<model>} — force relay routing</li>
     *   <li>{@code direct:<provider>:<model>} — force direct upstream call</li>
     *   <li>Backwards-compat aliases (deprecated, one cycle): {@code relay-<provider>:<model>},
     *       {@code relay:<model>} (env-driven upstream).</li>
     * </ul>
     *
     * @param free if true and provider is openrouter, auto-append ":free" to model name
     */
    public static LLMConfig fromProfileString(String profile, String apiKey,
                                               double temperature, int timeoutMs,
                                               boolean debug, boolean free) {
        if (profile == null) return null;
        String trimmed = modelPart(profile).trim();
        if (trimmed.isEmpty()) return null;
        String lower = trimmed.toLowerCase();

        // 1. Backwards-compat aliases: relay-<provider>:<model>.
        //    relay-custom:* maps to the new openai-compat name, but routes via
        //    the relay's "custom" upstream (X-Custom-Url) under the hood.
        if (lower.startsWith("relay-")) {
            int firstColon = trimmed.indexOf(':');
            if (firstColon > 0) {
                String upstream = trimmed.substring("relay-".length(), firstColon).toLowerCase();
                String rest = trimmed.substring(firstColon + 1);
                String newProviderName = "custom".equals(upstream) ? "openai-compat" : upstream;
                warnOnce("relay-" + upstream,
                        "[LLM] DEPRECATED: 'relay-" + upstream + ":' → use 'relay:" + newProviderName
                                + ":' or '" + newProviderName + ":' (with LLM_VIA_RELAY=true)");
                return fromProfileString("relay:" + newProviderName + ":" + rest,
                        apiKey, temperature, timeoutMs, debug, free);
            }
        }

        // 2. Bare relay:<model> — env-driven upstream. Deprecated; route via
        //    triple-segment form using RELAY_PROVIDER (default "cerebras").
        if (lower.startsWith("relay:") && trimmed.indexOf(':', "relay:".length()) < 0) {
            String model = trimmed.substring("relay:".length()).trim();
            String upstream = loadProviderApiKey("RELAY_PROVIDER");
            if (upstream == null || upstream.isEmpty()) upstream = "cerebras";
            // Map the relay's "custom" upstream to the new provider name.
            String newProviderName = "custom".equalsIgnoreCase(upstream) ? "openai-compat" : upstream.toLowerCase();
            warnOnce("relay-bare",
                    "[LLM] DEPRECATED: bare 'relay:<model>' → use 'relay:" + newProviderName
                            + ":<model>' or '" + newProviderName + ":<model>' (with LLM_VIA_RELAY=true)");
            if (model.isEmpty()) {
                model = loadProviderApiKey("RELAY_MODEL");
                if (model == null || model.isEmpty()) model = DEFAULT_CEREBRAS_MODEL;
            }
            return fromProfileString("relay:" + newProviderName + ":" + model,
                    apiKey, temperature, timeoutMs, debug, free);
        }

        // 3. Triple-segment override forms: relay:<provider>:<model> / direct:<provider>:<model>.
        boolean forceRelay = lower.startsWith("relay:");
        boolean forceDirect = lower.startsWith("direct:");
        String providerName;
        String model;
        boolean useRelay;
        if (forceRelay || forceDirect) {
            String prefix = forceRelay ? "relay:" : "direct:";
            String rest = trimmed.substring(prefix.length());
            int colon = rest.indexOf(':');
            if (colon < 0) {
                System.err.println("[LLM] Invalid profile '" + profile + "': expected '"
                        + prefix + "<provider>:<model>'");
                return null;
            }
            providerName = rest.substring(0, colon).toLowerCase().trim();
            model = rest.substring(colon + 1).trim();
            useRelay = forceRelay;
        } else {
            // 4. Two-segment canonical form <provider>:<model>.
            int colon = trimmed.indexOf(':');
            if (colon <= 0) return null;
            providerName = trimmed.substring(0, colon).toLowerCase().trim();
            model = trimmed.substring(colon + 1).trim();
            if (!SUPPORTED_PROVIDERS.contains(providerName)) {
                return null;
            }
            useRelay = isRelayDefault();
        }

        if (!SUPPORTED_PROVIDERS.contains(providerName)) {
            System.err.println("[LLM] Unknown provider '" + providerName + "' in profile '" + profile
                    + "'. Supported: " + SUPPORTED_PROVIDERS);
            return null;
        }

        // Ollama is local-only; relay path is meaningless. Warn and fall back to direct.
        if ("ollama".equals(providerName) && useRelay) {
            warnOnce("ollama-relay",
                    "[LLM] 'ollama' is local-only; ignoring relay routing and calling http://localhost:11434/v1 directly");
            useRelay = false;
        }

        // Anthropic direct path is unsupported in Phase 1 — LLMClient only
        // speaks the OpenAI-compatible variant (Authorization: Bearer), and
        // Anthropic's native API uses the x-api-key header + a different
        // request schema. Routing direct would 401 silently. Either route via
        // relay (relay translates the schema) or return null with a clear
        // diagnostic.
        if ("anthropic".equals(providerName) && !useRelay) {
            System.err.println("[LLM] 'anthropic' direct path is not implemented yet "
                    + "(LLMClient only speaks OpenAI-compatible chat/completions). "
                    + "Use 'relay:anthropic:<model>' or set LLM_VIA_RELAY=true.");
            return null;
        }

        int defaultTimeout = "ollama".equals(providerName) ? DEFAULT_LOCAL_TIMEOUT_MS : DEFAULT_CLOUD_TIMEOUT_MS;
        int effectiveTimeout = (timeoutMs > 0) ? timeoutMs : defaultTimeout;

        // Apply provider-specific defaults / model fallbacks.
        if (model.isEmpty()) {
            switch (providerName) {
                case "ollama":         model = DEFAULT_LOCAL_MODEL; break;
                case "openrouter":     model = DEFAULT_OPENROUTER_MODEL; break;
                case "cerebras":       model = DEFAULT_CEREBRAS_MODEL; break;
                case "openai-compat":  model = DEFAULT_CUSTOM_MODEL; break;
                default:               // openai / anthropic / groq have no sensible default
                    System.err.println("[LLM] Profile '" + profile + "' is missing the model name");
                    return null;
            }
        }
        if ("openrouter".equals(providerName) && free && !model.endsWith(":free")) {
            model = model + ":free";
        }

        // Build config based on routing.
        if (useRelay) {
            String baseUrl = loadProviderApiKey("RELAY_BASE_URL");
            if (baseUrl == null || baseUrl.isEmpty()) baseUrl = "http://localhost:8787/v1";

            // Relay upstream uses "custom" for openai-compat (X-Custom-Url path).
            String relayProvider = "openai-compat".equals(providerName) ? "custom" : providerName;
            String userApiKey = loadProviderApiKey(providerApiKeyEnvVar(relayProvider));
            String customUrl = null;
            if ("custom".equals(relayProvider)) {
                customUrl = loadProviderApiKey("OPENAI_COMPAT_URL");
                if (customUrl == null || customUrl.isEmpty()) {
                    // Back-compat: legacy RELAY_CUSTOM_URL still honoured.
                    customUrl = loadProviderApiKey("RELAY_CUSTOM_URL");
                }
                if (customUrl == null || customUrl.isEmpty()) {
                    System.err.println("[LLM] '" + providerName + "' (via relay) requires OPENAI_COMPAT_URL "
                            + "(URL of an OpenAI-compatible endpoint). Falling back to heuristic AI for this seat.");
                    return null;
                }
            }

            int minInterval = "cerebras".equals(relayProvider) ? CEREBRAS_MIN_INTERVAL_MS : 0;
            // Anthropic-style cache_control is supported by the cloud upstreams
            // we proxy, but not by openai-compat (LM Studio / vLLM speak plain
            // OpenAI without content-block cache hints).
            boolean cachingOn = !"custom".equals(relayProvider);

            return new Builder()
                    .provider("relay")
                    .relayProvider(relayProvider)
                    .userApiKey(userApiKey)
                    .customUrl(customUrl)
                    .apiBaseUrl(baseUrl)
                    .apiKey(apiKey)
                    .model(model)
                    .temperature(temperature)
                    .timeoutMs(effectiveTimeout)
                    .minIntervalMs(minInterval)
                    .debug(debug)
                    .promptCaching(cachingOn)
                    .build();
        }

        // Direct path — provider-specific base URL + key.
        String baseUrl;
        String directKey = apiKey;
        switch (providerName) {
            case "ollama":
                baseUrl = "http://localhost:11434/v1";
                directKey = null; // local, no auth
                break;
            case "openrouter":
                baseUrl = "https://openrouter.ai/api/v1";
                String orKey = loadProviderApiKey("OPENROUTER_API_KEY");
                if (orKey != null && !orKey.isEmpty()) directKey = orKey;
                break;
            case "cerebras":
                baseUrl = "https://api.cerebras.ai/v1";
                String cbKey = loadProviderApiKey("CEREBRAS_API_KEY");
                if (cbKey != null && !cbKey.isEmpty()) directKey = cbKey;
                break;
            case "groq":
                baseUrl = "https://api.groq.com/openai/v1";
                String gqKey = loadProviderApiKey("GROQ_API_KEY");
                if (gqKey != null && !gqKey.isEmpty()) directKey = gqKey;
                break;
            case "openai":
                baseUrl = "https://api.openai.com/v1";
                String oaKey = loadProviderApiKey("OPENAI_API_KEY");
                if (oaKey != null && !oaKey.isEmpty()) directKey = oaKey;
                break;
            case "openai-compat":
                baseUrl = loadProviderApiKey("OPENAI_COMPAT_URL");
                if (baseUrl == null || baseUrl.isEmpty()) {
                    System.err.println("[LLM] 'openai-compat' (direct) requires OPENAI_COMPAT_URL "
                            + "(e.g. http://localhost:1234/v1). Falling back to heuristic AI for this seat.");
                    return null;
                }
                String compatKey = loadProviderApiKey("OPENAI_COMPAT_API_KEY");
                if (compatKey != null && !compatKey.isEmpty()) directKey = compatKey;
                break;
            default:
                // anthropic already short-circuited above; nothing else to handle.
                System.err.println("[LLM] Unsupported direct provider: " + providerName);
                return null;
        }

        int minInterval = "cerebras".equals(providerName) ? CEREBRAS_MIN_INTERVAL_MS : 0;
        // Direct calls: caching off for ollama / openai-compat (local OpenAI-clone
        // backends typically don't honour Anthropic-style cache_control), on for
        // hosted providers that do (cerebras/openrouter/openai).
        boolean cachingOn = !"ollama".equals(providerName) && !"openai-compat".equals(providerName);

        return new Builder()
                .provider(providerName)
                .relayProvider(null)
                .userApiKey(null)
                .customUrl(null)
                .apiBaseUrl(baseUrl)
                .apiKey(directKey)
                .model(model)
                .temperature(temperature)
                .timeoutMs(effectiveTimeout)
                .minIntervalMs(minInterval)
                .debug(debug)
                .promptCaching(cachingOn)
                .build();
    }

    /** Backward-compatible overload without free flag. */
    public static LLMConfig fromProfileString(String profile, String apiKey,
                                               double temperature, int timeoutMs,
                                               boolean debug) {
        return fromProfileString(profile, apiKey, temperature, timeoutMs, debug, false);
    }

    /**
     * Separator between the model an LLM seat talks to and the AI profile it
     * plays under, as in {@code "cerebras:gpt-oss-120b@Cautious"}.
     *
     * <p>An LLM seat is still a Forge AI seat. The heuristic AI underneath it
     * decides everything the model is not asked about, and reads its dials —
     * aggression, mulligan threshold, how much life it will spend — from an
     * {@code .ai} profile in {@code res/ai}. That profile was fixed at
     * {@code Default} for every LLM seat, so an LLM seat could not be compared
     * against, or combined with, any of the tuned profiles a heuristic seat can
     * use. Naming one after {@code '@'} sets it. Left out, the profile stays
     * {@code Default} and nothing changes.
     *
     * <p>{@code '@'} is safe as a separator because it appears in no provider
     * name and in no model id.
     */
    public static final char AI_PROFILE_SEPARATOR = '@';

    /**
     * The provider-and-model part of a seat profile — everything before
     * {@link #AI_PROFILE_SEPARATOR}. Unchanged when there is no separator.
     */
    public static String modelPart(String profile) {
        if (profile == null) return null;
        int at = profile.indexOf(AI_PROFILE_SEPARATOR);
        return at < 0 ? profile : profile.substring(0, at);
    }

    /**
     * The {@code .ai} profile named after {@link #AI_PROFILE_SEPARATOR}, or an
     * empty string when the seat profile names none.
     */
    public static String aiProfilePart(String profile) {
        if (profile == null) return "";
        int at = profile.indexOf(AI_PROFILE_SEPARATOR);
        return at < 0 ? "" : profile.substring(at + 1).trim();
    }

    public static boolean isLlmProfile(String profile) {
        if (profile == null) return false;
        String trimmed = modelPart(profile).trim();
        if (trimmed.isEmpty()) return false;
        String lower = trimmed.toLowerCase();

        // Triple-segment overrides.
        if (lower.startsWith("relay:") || lower.startsWith("direct:")) {
            int firstColon = trimmed.indexOf(':');
            String rest = trimmed.substring(firstColon + 1);
            int secondColon = rest.indexOf(':');
            if (secondColon > 0) {
                String upstream = rest.substring(0, secondColon).toLowerCase();
                if (SUPPORTED_PROVIDERS.contains(upstream)) return true;
            }
            // Bare relay:<model> — accept as deprecated form.
            return lower.startsWith("relay:");
        }

        // Backwards-compat relay-<provider>: prefixes.
        if (lower.startsWith("relay-")) {
            int colon = trimmed.indexOf(':');
            if (colon > "relay-".length()) {
                String upstream = trimmed.substring("relay-".length(), colon).toLowerCase();
                return SUPPORTED_PROVIDERS.contains(upstream)
                        || "custom".equals(upstream); // legacy alias for openai-compat
            }
            return false;
        }

        // Two-segment <provider>:<model>.
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return false;
        String provider = trimmed.substring(0, colon).toLowerCase();
        return SUPPORTED_PROVIDERS.contains(provider);
    }

    /** Loads API key from environment variables, then falls back to .env file. */
    public static String loadApiKeyFromEnv() {
        String key = System.getenv("FORGE_LLM_API_KEY");
        if (key != null && !key.isEmpty()) return key;

        key = System.getenv("OPENROUTER_API_KEY");
        if (key != null && !key.isEmpty()) return key;

        // Try .env file in working directory
        String fromDotEnv = loadDotEnvValue("FORGE_LLM_API_KEY");
        if (fromDotEnv != null) return fromDotEnv;
        fromDotEnv = loadDotEnvValue("OPENROUTER_API_KEY");
        return fromDotEnv;
    }

    /**
     * Maps a relay upstream provider name to its API key env var.
     * Covers every managed upstream supported by the relay integration.
     */
    public static String providerApiKeyEnvVar(String provider) {
        if (provider == null) return null;
        switch (provider.toLowerCase()) {
            case "cerebras":   return "CEREBRAS_API_KEY";
            case "openrouter": return "OPENROUTER_API_KEY";
            case "groq":       return "GROQ_API_KEY";
            case "openai":     return "OPENAI_API_KEY";
            case "anthropic":  return "ANTHROPIC_API_KEY";
            default:           return null;
        }
    }

    /**
     * Loads a value (typically an API key, but also routing flags) from env
     * var, then falls back to a {@code .env} file in the working directory and
     * each parent up to the filesystem root.
     */
    public static String loadProviderApiKey(String envVarName) {
        if (envVarName == null || envVarName.isEmpty()) return null;
        // System-property override channel ("forge.llm.env.<VAR>"): GUI dialogs
        // cannot modify process env, so this is how in-app configuration (e.g.
        // LlmConfigDialog's openai-compat URL) takes effect for the session.
        String prop = System.getProperty("forge.llm.env." + envVarName);
        if (prop != null && !prop.isEmpty()) return prop;
        String key = System.getenv(envVarName);
        if (key != null && !key.isEmpty()) return key;
        return loadDotEnvValue(envVarName);
    }

    /** Returns true if LLM debug is enabled via env var or .env file. */
    public static boolean isDebugEnabled() {
        String env = System.getenv("FORGE_LLM_DEBUG");
        if (env != null && !env.isEmpty() && !"false".equalsIgnoreCase(env) && !"0".equals(env)) {
            return true;
        }
        String dotEnv = loadDotEnvValue("FORGE_LLM_DEBUG");
        return dotEnv != null && !dotEnv.isEmpty() && !"false".equalsIgnoreCase(dotEnv) && !"0".equals(dotEnv);
    }

    /**
     * Read a single value from a {@code .env} file, walking up from the
     * working directory through each parent. Returns the first match.
     */
    private static String loadDotEnvValue(String key) {
        String prefix = key + "=";
        java.io.File dir = new java.io.File("").getAbsoluteFile();
        while (dir != null) {
            java.io.File envFile = new java.io.File(dir, ".env");
            if (envFile.exists()) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(envFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("#") || line.isEmpty()) continue;
                        if (line.startsWith(prefix)) {
                            return line.substring(prefix.length()).trim();
                        }
                    }
                } catch (java.io.IOException e) {
                    // Ignore — fall through to parent dir.
                }
            }
            dir = dir.getParentFile();
        }
        return null;
    }

    /** Print a deprecation/info warning at most once per JVM run. */
    private static void warnOnce(String tag, String msg) {
        if (WARNED_DEPRECATIONS.add(tag)) {
            System.err.println(msg);
        }
    }

    public static class Builder {
        private String apiBaseUrl = "http://localhost:11434/v1";
        private String apiKey;
        private String model = DEFAULT_LOCAL_MODEL;
        private String provider = "ollama";
        private String relayProvider;
        private String userApiKey;
        private String customUrl;
        private double temperature = 0.2;
        private int timeoutMs = 30000;
        private int minIntervalMs = 0;
        private boolean debug = false;
        private boolean promptCaching = false;

        public Builder apiBaseUrl(String url) { this.apiBaseUrl = url; return this; }
        public Builder apiKey(String key) { this.apiKey = key; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder relayProvider(String rp) { this.relayProvider = rp; return this; }
        public Builder userApiKey(String k) { this.userApiKey = k; return this; }
        public Builder customUrl(String url) { this.customUrl = url; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }
        public Builder timeoutMs(int ms) { this.timeoutMs = ms; return this; }
        public Builder minIntervalMs(int ms) { this.minIntervalMs = ms; return this; }
        public Builder debug(boolean d) { this.debug = d; return this; }
        public Builder promptCaching(boolean p) { this.promptCaching = p; return this; }

        public LLMConfig build() { return new LLMConfig(this); }
    }
}
