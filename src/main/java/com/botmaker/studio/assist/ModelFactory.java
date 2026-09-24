package com.botmaker.studio.assist;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.function.Function;

/**
 * Builds the LangChain4j {@link ChatModel} {@link AssistantSettings} describe, or says in one sentence why it
 * cannot. The only class that names a provider module; everything above it sees a {@code ChatModel}.
 */
public final class ModelFactory {

    /** Long: a local model on a laptop may take minutes over a whole file, and a timeout mid-turn wastes it. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TOKENS = 16_000;

    private ModelFactory() {}

    /** A model, or the reason there is none. */
    public sealed interface Built {
        record Model(ChatModel model) implements Built {}
        record Problem(String reason) implements Built {}
    }

    /** {@code settings} as a model, reading keys through {@code env} ({@code System::getenv} outside a test). */
    public static Built build(AssistantSettings settings, Function<String, String> env) {
        Provider provider = settings.providerKind();
        if (provider == Provider.UNKNOWN) {
            return new Built.Problem("This Studio does not know the provider \"" + settings.provider() + "\".");
        }
        if (settings.model().isBlank()) return new Built.Problem("Choose a model for " + provider.displayName() + ".");
        String key = provider.keyVariable() == null ? null : env.apply(provider.keyVariable());
        if (provider.requiresKey() && (key == null || key.isBlank())) {
            return new Built.Problem("Set " + provider.keyVariable() + " in the environment Studio starts from.");
        }
        String baseUrl = settings.baseUrl().isBlank() ? provider.defaultBaseUrl() : settings.baseUrl();
        try {
            return new Built.Model(switch (provider) {
                case OLLAMA -> OllamaChatModel.builder()
                        .baseUrl(baseUrl).modelName(settings.model()).timeout(TIMEOUT).build();
                case OPENAI -> OpenAiChatModel.builder()
                        .apiKey(key).modelName(settings.model()).timeout(TIMEOUT).build();
                case OPENAI_COMPATIBLE -> OpenAiChatModel.builder()
                        .baseUrl(baseUrl).apiKey(key == null || key.isBlank() ? "none" : key)
                        .modelName(settings.model()).timeout(TIMEOUT).build();
                case ANTHROPIC -> AnthropicChatModel.builder()
                        .apiKey(key).modelName(settings.model()).maxTokens(MAX_TOKENS).timeout(TIMEOUT).build();
                case GEMINI -> GoogleAiGeminiChatModel.builder()
                        .apiKey(key).modelName(settings.model()).timeout(TIMEOUT).build();
                case UNKNOWN -> throw new IllegalStateException("unreachable");
            });
        } catch (RuntimeException e) {
            return new Built.Problem("Could not set up " + provider.displayName() + ": " + e.getMessage());
        }
    }
}
