package forge.ai.llm;

/**
 * Immutable per-player LLM configuration.
 */
public final class LLMConfig {

    // GUI display names for AI profile dropdown
    public static final String LLM_LOCAL_DISPLAY = "LLM (Local)";
    public static final String LLM_OPENROUTER_DISPLAY = "LLM (OpenRouter Free)";
    public static final String LLM_CEREBRAS_DISPLAY = "LLM (Cerebras)";

    // Default models for each provider
    private static final String DEFAULT_LOCAL_MODEL = "llama3";
    private static final String DEFAULT_OPENROUTER_MODEL = "google/gemini-2.5-flash:free";
    private static final String DEFAULT_CEREBRAS_MODEL = "llama3.1-8b";

    // Default timeouts per provider (local models need longer for cold starts / model loading)
    private static final int DEFAULT_LOCAL_TIMEOUT_MS = 300_000;   // 5 minutes (reasoning models are verbose)
    private static final int DEFAULT_CLOUD_TIMEOUT_MS = 30_000;    // 30 seconds

    private final String apiBaseUrl;
    private final String apiKey;       // nullable for local Ollama
    private final String model;
    private final String provider;     // "ollama" or "openrouter"
    private final double temperature;
    private final int timeoutMs;
    private final boolean debug;

    // Shared across all LLM players
    private final double budgetLimit;  // 0 = unlimited

    private LLMConfig(Builder b) {
        this.apiBaseUrl = b.apiBaseUrl;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.provider = b.provider;
        this.temperature = b.temperature;
        this.timeoutMs = b.timeoutMs;
        this.debug = b.debug;
        this.budgetLimit = b.budgetLimit;
    }

    public String getApiBaseUrl() { return apiBaseUrl; }
    public String getApiKey() { return apiKey; }
    public String getModel() { return model; }
    public String getProvider() { return provider; }
    public double getTemperature() { return temperature; }
    public int getTimeoutMs() { return timeoutMs; }
    public boolean isDebug() { return debug; }
    public double getBudgetLimit() { return budgetLimit; }

    /** Returns true if this model is a thinking/reasoning model that produces chain-of-thought tokens. */
    public boolean isThinkingModel() {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.contains("-r1") || lower.contains("thinking") || lower.contains("reasoning") || lower.contains("gemini-2.5");
    }

    /**
     * Parse a profile string like "ollama:llama3" or "openrouter:deepseek/deepseek-chat"
     * into an LLMConfig. Returns null if the string is not an LLM profile.
     *
     * @param free if true and provider is openrouter, auto-append ":free" to model name
     */
    public static LLMConfig fromProfileString(String profile, String apiKey,
                                               double temperature, int timeoutMs,
                                               double budgetLimit, boolean debug,
                                               boolean free) {
        if (profile == null) return null;

        String lower = profile.toLowerCase().trim();
        String provider;
        String model;
        String baseUrl;

        int defaultTimeout;
        if (lower.startsWith("ollama:")) {
            provider = "ollama";
            model = profile.substring("ollama:".length()).trim();
            if (model.isEmpty()) model = DEFAULT_LOCAL_MODEL;
            baseUrl = "http://localhost:11434/v1";
            defaultTimeout = DEFAULT_LOCAL_TIMEOUT_MS;
        } else if (lower.startsWith("openrouter:")) {
            provider = "openrouter";
            model = profile.substring("openrouter:".length()).trim();
            if (model.isEmpty()) model = DEFAULT_OPENROUTER_MODEL;
            baseUrl = "https://openrouter.ai/api/v1";
            defaultTimeout = DEFAULT_CLOUD_TIMEOUT_MS;
            // Auto-append :free when --llm-free is set
            if (free && !model.endsWith(":free")) {
                model = model + ":free";
            }
        } else if (lower.startsWith("cerebras:")) {
            provider = "cerebras";
            model = profile.substring("cerebras:".length()).trim();
            if (model.isEmpty()) model = DEFAULT_CEREBRAS_MODEL;
            baseUrl = "https://api.cerebras.ai/v1";
            defaultTimeout = DEFAULT_CLOUD_TIMEOUT_MS;
            // Always prefer Cerebras-specific key (generic key from another provider won't work)
            String cerebrasKey = loadProviderApiKey("CEREBRAS_API_KEY");
            if (cerebrasKey != null && !cerebrasKey.isEmpty()) {
                apiKey = cerebrasKey;
            }
        } else {
            return null; // Not an LLM profile
        }

        int effectiveTimeout = (timeoutMs > 0) ? timeoutMs : defaultTimeout;

        return new Builder()
                .provider(provider)
                .apiBaseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                .timeoutMs(effectiveTimeout)
                .budgetLimit(budgetLimit)
                .debug(debug)
                .build();
    }

    /** Backward-compatible overload without free flag. */
    public static LLMConfig fromProfileString(String profile, String apiKey,
                                               double temperature, int timeoutMs,
                                               double budgetLimit, boolean debug) {
        return fromProfileString(profile, apiKey, temperature, timeoutMs, budgetLimit, debug, false);
    }

    public static boolean isLlmProfile(String profile) {
        if (profile == null) return false;
        String lower = profile.toLowerCase().trim();
        return lower.startsWith("ollama:") || lower.startsWith("openrouter:") || lower.startsWith("cerebras:");
    }

    /** Returns true if the profile string is an LLM GUI display name. */
    public static boolean isLlmDisplayProfile(String profile) {
        return LLM_LOCAL_DISPLAY.equals(profile) || LLM_OPENROUTER_DISPLAY.equals(profile)
                || LLM_CEREBRAS_DISPLAY.equals(profile);
    }

    /** Maps a GUI display name to the internal profile string (e.g. "ollama:llama3"). */
    public static String toProfileString(String displayName) {
        if (LLM_LOCAL_DISPLAY.equals(displayName)) {
            return "ollama:" + DEFAULT_LOCAL_MODEL;
        } else if (LLM_OPENROUTER_DISPLAY.equals(displayName)) {
            return "openrouter:" + DEFAULT_OPENROUTER_MODEL;
        } else if (LLM_CEREBRAS_DISPLAY.equals(displayName)) {
            return "cerebras:" + DEFAULT_CEREBRAS_MODEL;
        }
        return null;
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

    /** Loads a provider-specific API key from env var or .env file. */
    public static String loadProviderApiKey(String envVarName) {
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

    /** Read a single value from .env file in the working directory. */
    private static String loadDotEnvValue(String key) {
        java.io.File envFile = new java.io.File(".env");
        if (!envFile.exists()) return null;
        String prefix = key + "=";
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
            // Ignore
        }
        return null;
    }

    public static class Builder {
        private String apiBaseUrl = "http://localhost:11434/v1";
        private String apiKey;
        private String model = DEFAULT_LOCAL_MODEL;
        private String provider = "ollama";
        private double temperature = 0.2;
        private int timeoutMs = 30000;
        private boolean debug = false;
        private double budgetLimit = 0;

        public Builder apiBaseUrl(String url) { this.apiBaseUrl = url; return this; }
        public Builder apiKey(String key) { this.apiKey = key; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder temperature(double t) { this.temperature = t; return this; }
        public Builder timeoutMs(int ms) { this.timeoutMs = ms; return this; }
        public Builder debug(boolean d) { this.debug = d; return this; }
        public Builder budgetLimit(double limit) { this.budgetLimit = limit; return this; }

        public LLMConfig build() { return new LLMConfig(this); }
    }
}
