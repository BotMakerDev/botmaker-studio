package com.botmaker.studio.assist;

import com.botmaker.studio.parser.guard.RefusalJournal;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectTemplate;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tool loop end to end with a scripted model in place of a real one: LangChain4j really dispatches the
 * {@code @Tool} methods, and the edits really land in the turn. What no test here can say is how well any real
 * model uses the tools.
 */
class AssistantServiceTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Subject {
                public void run() {
                    int count = 3;
                }
            }
            """;

    private static final AssistantSettings SETTINGS = AssistantSettings.defaults();

    private static AssistTurn turn() {
        AssistWorkspace workspace = new AssistWorkspace(
                ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects")),
                Paths.get("Subject.java").toAbsolutePath(),
                List.of(System.getProperty("java.class.path").split(File.pathSeparator)),
                Paths.get("src", "main", "java").toAbsolutePath(),
                ProjectTemplate.GAME_BOT, null, null, null,
                RefusalJournal.in(Path.of(System.getProperty("java.io.tmpdir"), "botmaker-test-refusals")),
                ValueGrammar.empty());
        return new AssistTurn(workspace, SOURCE);
    }

    /**
     * Reads the tree, inserts a print at the top of {@code run()} using the body id it read, then answers.
     * Each step keys off the last tool result, as a real model's would.
     */
    private static final class Scripted implements ChatModel {
        final List<String> toolResults = new ArrayList<>();

        @Override
        public ChatResponse doChat(ChatRequest request) {
            List<ChatMessage> messages = request.messages();
            ChatMessage last = messages.getLast();
            if (!(last instanceof ToolExecutionResultMessage result)) {
                return call("readTree", "{}");
            }
            toolResults.add(result.text());
            if (result.toolName().equals("readTree")) {
                Matcher body = Pattern.compile("\"name\":\"run\".*?\"body\":\\{\"id\":\"([^\"]+)\"").matcher(result.text());
                assertTrue(body.find(), result.text());
                return call("insertBlock", "{\"bodyId\":\"" + body.group(1) + "\",\"index\":0,\"paletteId\":\"block:PRINT\"}");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("Added a print.")).build();
        }

        private static ChatResponse call(String tool, String arguments) {
            return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                    .id(tool + "-1").name(tool).arguments(arguments).build())).build();
        }
    }

    @Test
    void theModelsToolCallsLandInTheTurn() {
        Scripted model = new Scripted();
        AssistantService service = AssistantService.over(s -> new ModelFactory.Built.Model(model));
        AssistTurn turn = turn();

        AssistantService.Reply reply = service.ask(SETTINGS, turn, "print something first");

        assertEquals("Added a print.", reply.text());
        assertTrue(reply.calledTools());
        assertEquals(1, turn.acceptedEdits());
        assertTrue(model.toolResults.getLast().startsWith("OK:"), model.toolResults.getLast());
        assertEquals(2, reply.toolLog().size(), reply.toolLog().toString());
        assertTrue(turn.commit(SOURCE, "Assistant").isPresent());
    }

    @Test
    void noModelIsASentenceNotACrash() {
        AssistantService service = AssistantService.over(s -> new ModelFactory.Built.Problem("Choose a model."));
        AssistantService.AssistantException e = assertThrows(AssistantService.AssistantException.class,
                () -> service.ask(SETTINGS, turn(), "hi"));
        assertEquals("Choose a model.", e.getMessage());
    }

    @Test
    void aHostedProviderWithoutItsKeyNamesTheVariable() {
        ModelFactory.Built built = ModelFactory.build(
                new AssistantSettings(Provider.ANTHROPIC.id(), "claude-sonnet-5", ""), name -> null);
        ModelFactory.Built.Problem problem = assertInstanceOf(ModelFactory.Built.Problem.class, built);
        assertTrue(problem.reason().contains("ANTHROPIC_API_KEY"), problem.reason());
    }

    @Test
    void ollamaNeedsNoKey() {
        assertInstanceOf(ModelFactory.Built.Model.class, ModelFactory.build(
                new AssistantSettings(Provider.OLLAMA.id(), "qwen3", ""), name -> null));
    }

    @Test
    void aFreshProviderHasNoModelUntilOneIsChosen() {
        AssistantSettings fresh = AssistantSettings.forProvider(Provider.ANTHROPIC);
        assertEquals("", fresh.model());
        ModelFactory.Built.Problem problem =
                assertInstanceOf(ModelFactory.Built.Problem.class, ModelFactory.build(fresh, name -> "key"));
        assertTrue(problem.reason().startsWith("Choose a model"), problem.reason());
    }

    @Test
    void anUnknownProviderIsReadButBuildsNothing() {
        AssistantSettings settings = new AssistantSettings("tomorrows-provider", "m", "");
        assertEquals(Provider.UNKNOWN, settings.providerKind());
        assertFalse(ModelFactory.build(settings, name -> "key") instanceof ModelFactory.Built.Model);
    }
}
