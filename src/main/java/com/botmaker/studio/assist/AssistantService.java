package com.botmaker.studio.assist;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One conversation with the model the user chose, driving {@link AssistTools}. LangChain4j runs the tool loop;
 * this class decides what a turn is — one user message, one {@link AssistTurn} — and hands the turn back
 * uncommitted, so the caller commits it on the FX thread against the live source.
 *
 * <p>The conversation survives a change of model: the memory is this object's, and only the
 * {@code AiServices} proxy is rebuilt when the settings differ from the last message's.
 */
public final class AssistantService {

    static final String SYSTEM_PROMPT = """
            You edit a bot in BotMaker Studio, a block editor over Java. You work only through the tools.

            - Read the file with readTree before editing, and again after edits: ids below an insertion shift.
            - Insert only what listPalette offers, by its id. You cannot write Java directly.
            - A slot takes a value of its type: a literal, a constant, or a factory call of that type.
            - Every edit is compiled. A REFUSED answer says why; fix the cause and try again, or explain.
            - Nothing you do reaches the user's file until your reply ends, and then all of it lands as one
              step they can undo.
            - Keep replies short: say what you changed, or what you could not do and why.
            """;

    private static final int MEMORY_MESSAGES = 60;

    /** What LangChain4j implements. */
    interface Assistant {
        Result<String> chat(String message);
    }

    /** The model's answer, the tool calls it made, and the turn holding its edits — not yet committed. */
    public record Reply(String text, List<String> toolLog, boolean calledTools, AssistTurn turn) {}

    private final Function<AssistantSettings, ModelFactory.Built> models;
    private final MessageWindowChatMemory memory = MessageWindowChatMemory.withMaxMessages(MEMORY_MESSAGES);
    private final AssistTools tools = new AssistTools();
    private AssistantSettings builtFor;
    private Assistant assistant;

    /** Keys read through {@code env} — {@code System::getenv} in the running app. */
    public static AssistantService withEnvironment(Function<String, String> env) {
        return new AssistantService(settings -> ModelFactory.build(settings, env));
    }

    /** Over {@code models} — a test scripts the model here. */
    static AssistantService over(Function<AssistantSettings, ModelFactory.Built> models) {
        return new AssistantService(models);
    }

    private AssistantService(Function<AssistantSettings, ModelFactory.Built> models) {
        this.models = Objects.requireNonNull(models);
    }

    /**
     * Sends {@code message} and runs the model's tool calls against {@code turn}. Blocking, and slow — call it
     * off the FX thread.
     *
     * @throws AssistantException when no model can be built or the model call fails
     */
    public synchronized Reply ask(AssistantSettings settings, AssistTurn turn, String message) {
        Assistant current = assistantFor(settings);
        tools.begin(turn);
        Result<String> result;
        try {
            result = current.chat(message);
        } catch (RuntimeException e) {
            throw new AssistantException(describe(e), e);
        }
        return new Reply(result.content() == null ? "" : result.content(), tools.log(),
                !result.toolExecutions().isEmpty(), turn);
    }

    /** Forgets the conversation. */
    public synchronized void reset() {
        memory.clear();
    }

    private Assistant assistantFor(AssistantSettings settings) {
        if (assistant != null && settings.equals(builtFor)) return assistant;
        ChatModel model = switch (models.apply(settings)) {
            case ModelFactory.Built.Model m -> m.model();
            case ModelFactory.Built.Problem p -> throw new AssistantException(p.reason(), null);
        };
        assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemory(memory)
                .systemMessageProvider(id -> SYSTEM_PROMPT)
                .tools(tools)
                .build();
        builtFor = settings;
        return assistant;
    }

    /** The innermost message, which is where a provider's HTTP error says what was wrong. */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    /** No model, or the model call failed. The message is a sentence for the pane. */
    public static final class AssistantException extends RuntimeException {
        AssistantException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
