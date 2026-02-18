package forge.ai.llm;

/**
 * Immutable per-player LLM configuration.
 */
public final class LLMConfig {

    // GUI display names for AI profile dropdown
    public static final String LLM_LOCAL_DISPLAY = "LLM (Local)";
    public static final String LLM_OPENROUTER_DISPLAY = "LLM (OpenRouter)";

    // Default models for each provider
    private static final String DEFAULT_LOCAL_MODEL = "llama3";
    private static final String DEFAULT_OPENROUTER_MODEL = "deepseek/deepseek-chat";

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

    /**
     * Parse a profile string like "ollama:llama3" or "openrouter:deepseek/deepseek-chat"
     * into an LLMConfig. Returns null if the string is not an LLM profile.
     */
    public static LLMConfig fromProfileString(String profile, String apiKey,
                                               double temperature, int timeoutMs,
                                               double budgetLimit, boolean debug) {
        if (profile == null) return null;

        String lower = profile.toLowerCase().trim();
        String provider;
        String model;
        String baseUrl;

        if (lower.startsWith("ollama:")) {
            provider = "ollama";
            model = profile.substring("ollama:".length()).trim();
            if (model.isEmpty()) model = "llama3";
            baseUrl = "http://localhost:11434/v1";
        } else if (lower.startsWith("openrouter:")) {
            provider = "openrouter";
            model = profile.substring("openrouter:".length()).trim();
            if (model.isEmpty()) model = "deepseek/deepseek-chat";
            baseUrl = "https://openrouter.ai/api/v1";
        } else {
            return null; // Not an LLM profile
        }

        return new Builder()
                .provider(provider)
                .apiBaseUrl(baseUrl)
                .apiKey(apiKey)
                .model(model)
                .temperature(temperature)
                .timeoutMs(timeoutMs)
                .budgetLimit(budgetLimit)
                .debug(debug)
                .build();
    }

    public static boolean isLlmProfile(String profile) {
        if (profile == null) return false;
        String lower = profile.toLowerCase().trim();
        return lower.startsWith("ollama:") || lower.startsWith("openrouter:");
    }

    /** Returns true if the profile string is an LLM GUI display name. */
    public static boolean isLlmDisplayProfile(String profile) {
        return LLM_LOCAL_DISPLAY.equals(profile) || LLM_OPENROUTER_DISPLAY.equals(profile);
    }

    /** Maps a GUI display name to the internal profile string (e.g. "ollama:llama3"). */
    public static String toProfileString(String displayName) {
        if (LLM_LOCAL_DISPLAY.equals(displayName)) {
            return "ollama:" + DEFAULT_LOCAL_MODEL;
        } else if (LLM_OPENROUTER_DISPLAY.equals(displayName)) {
            return "openrouter:" + DEFAULT_OPENROUTER_MODEL;
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
        private String model = "llama3";
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
