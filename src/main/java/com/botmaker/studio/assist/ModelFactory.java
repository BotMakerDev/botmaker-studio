package com.botmaker.studio.assist;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicModelCatalog;
import dev.langchain4j.model.catalog.ModelCatalog;
import dev.langchain4j.model.catalog.ModelDescription;
import dev.langchain4j.model.catalog.ModelType;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiModelCatalog;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaModel;
import dev.langchain4j.model.ollama.OllamaModels;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiModelCatalog;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Builds the LangChain4j {@link ChatModel} {@link AssistantSettings} describe, or says in one sentence why it
 * cannot, and lists the models a provider offers. The only class that names a provider module; everything
 * above it sees a {@code ChatModel} or a list of ids.
 */
public final class ModelFactory {

    /** Long: a local model on a laptop may take minutes over a whole file, and a timeout mid-turn wastes it. */
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_TOKENS = 16_000;
    /** Short: this runs while the user waits for a dropdown, and a server that is down should say so quickly. */
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Name fragments of models that do not chat, for catalogues that say no type (OpenAI's, Anthropic's, and
     * most servers copying OpenAI's API): offering {@code text-embedding-3-small} is a guaranteed failed Send.
     */
    private static final List<String> NOT_CHAT = List.of(
            "embed", "tts", "whisper", "dall-e", "moderation", "transcribe", "image", "davinci", "babbage");

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

    /** The models a provider offers, or the reason they could not be listed. */
    public sealed interface Listing {
        record Models(List<Choice> models) implements Listing {}
        record Problem(String reason) implements Listing {}
    }

    /** One model to offer: the id a request names, and what to show when the provider has a nicer name. */
    public record Choice(String id, String label) {}

    /**
     * What {@code provider} serves at {@code baseUrl} (blank: its default), chat models only, newest first.
     * Asks the provider, so it blocks for up to {@link #LIST_TIMEOUT}; never throws. Without the key a
     * provider requires, nothing is sent.
     */
    public static Listing models(Provider provider, String baseUrl, Function<String, String> env) {
        if (provider == Provider.UNKNOWN) return new Listing.Problem("This Studio does not know that provider.");
        String key = provider.keyVariable() == null ? null : env.apply(provider.keyVariable());
        boolean hasKey = key != null && !key.isBlank();
        if (provider.requiresKey() && !hasKey) {
            return new Listing.Problem("Set " + provider.keyVariable() + " before starting Studio to list models.");
        }
        String url = baseUrl == null || baseUrl.isBlank() ? provider.defaultBaseUrl() : baseUrl.strip();
        try {
            List<Choice> choices = switch (provider) {
                case OLLAMA -> ollama(url);
                case OPENAI -> described(OpenAiModelCatalog.builder().apiKey(key)
                        .connectTimeout(LIST_TIMEOUT).readTimeout(LIST_TIMEOUT).build());
                case OPENAI_COMPATIBLE -> described(OpenAiModelCatalog.builder().baseUrl(url)
                        .apiKey(hasKey ? key : "none").connectTimeout(LIST_TIMEOUT).readTimeout(LIST_TIMEOUT).build());
                case ANTHROPIC -> described(AnthropicModelCatalog.builder().apiKey(key).timeout(LIST_TIMEOUT).build());
                case GEMINI -> described(GoogleAiGeminiModelCatalog.builder().apiKey(key).timeout(LIST_TIMEOUT).build());
                case UNKNOWN -> throw new IllegalStateException("unreachable");
            };
            return new Listing.Models(choices);
        } catch (RuntimeException e) {
            return new Listing.Problem("Could not list " + provider.displayName() + " models"
                    + (url.isBlank() ? "" : " at " + url) + ": " + firstLine(e));
        }
    }

    private static List<Choice> ollama(String url) {
        List<OllamaModel> installed = OllamaModels.builder().baseUrl(url).timeout(LIST_TIMEOUT).maxRetries(0)
                .build().availableModels().content();
        return installed.stream()
                .sorted(Comparator.comparing(OllamaModel::getModifiedAt,
                        Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder())))
                .map(m -> new Choice(m.getName(), m.getName()))
                .toList();
    }

    private static List<Choice> described(ModelCatalog catalog) {
        return chatModels(catalog.listModels());
    }

    /** Chat models, newest first where the catalogue dates them, then by name. Package-private for tests. */
    static List<Choice> chatModels(List<ModelDescription> described) {
        return described.stream()
                .filter(ModelFactory::chats)
                .sorted(Comparator.comparing(ModelDescription::createdAt,
                                Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                        .thenComparing(ModelDescription::name))
                .map(d -> new Choice(d.name(),
                        d.displayName() == null || d.displayName().isBlank() ? d.name() : d.displayName()))
                .distinct()
                .toList();
    }

    private static boolean chats(ModelDescription d) {
        if (d.name() == null || d.name().isBlank()) return false;
        if (d.type() != null) return d.type() == ModelType.CHAT;
        String name = d.name().toLowerCase(Locale.ROOT);
        return NOT_CHAT.stream().noneMatch(name::contains);
    }

    private static String firstLine(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
