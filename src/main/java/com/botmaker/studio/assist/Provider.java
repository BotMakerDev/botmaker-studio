package com.botmaker.studio.assist;

/**
 * Who answers the assistant pane. A closed set, because each one is a LangChain4j module Studio ships with;
 * the model inside it is free text the user types.
 *
 * <p>A key is read from the environment and nowhere else — {@link #keyVariable} names the variable — so
 * Studio never writes one to disk and never shows one on screen.
 */
public enum Provider {
    OLLAMA("ollama", "Ollama (local)", null, "http://localhost:11434", "qwen3"),
    OPENAI("openai", "OpenAI", "OPENAI_API_KEY", null, ""),
    /** Any server that speaks OpenAI's chat API: LM Studio, vLLM, OpenRouter, a company gateway. */
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-compatible", "OPENAI_API_KEY", "http://localhost:1234/v1", ""),
    ANTHROPIC("anthropic", "Anthropic", "ANTHROPIC_API_KEY", null, "claude-opus-5"),
    GEMINI("gemini", "Google Gemini", "GEMINI_API_KEY", null, ""),
    /** A settings file naming a provider this build does not have. Builds no model. */
    UNKNOWN("unknown", "Unknown", null, null, "");

    private final String id;
    private final String displayName;
    private final String keyVariable;
    private final String defaultBaseUrl;
    private final String defaultModel;

    Provider(String id, String displayName, String keyVariable, String defaultBaseUrl, String defaultModel) {
        this.id = id;
        this.displayName = displayName;
        this.keyVariable = keyVariable;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
    }

    /** The stable id a setting is saved under. */
    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The environment variable the key is read from, or {@code null} for a provider that needs none. */
    public String keyVariable() {
        return keyVariable;
    }

    /** Whether the key is required: an OpenAI-compatible local server usually takes none. */
    public boolean requiresKey() {
        return keyVariable != null && this != OPENAI_COMPATIBLE;
    }

    /** Whether the user points this provider at a server: the local ones. */
    public boolean takesBaseUrl() {
        return defaultBaseUrl != null;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl == null ? "" : defaultBaseUrl;
    }

    /** A model to start from, or {@code ""} where naming one would be a guess about someone else's catalogue. */
    public String defaultModel() {
        return defaultModel;
    }

    /** The provider {@code id} names, {@link #UNKNOWN} for anything else. Total. */
    public static Provider fromId(String id) {
        for (Provider provider : values()) {
            if (provider.id.equals(id)) return provider;
        }
        return UNKNOWN;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
