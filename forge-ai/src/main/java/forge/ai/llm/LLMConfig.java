package forge.ai.llm;

/**
 * Immutable per-player LLM configuration.
 */
public final class LLMConfig {
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
