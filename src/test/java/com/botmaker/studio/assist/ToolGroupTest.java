package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP's own tools with no screen attached, and a client's {@link ToolGroup} beside them (plan phase 9). */
class ToolGroupTest {

    private static final String LOOK = "look_here";
    private final McpJsonMapper json = new JacksonMcpJsonMapper(new ObjectMapper());
    private final LiveBot bot = McpBudgetTest.stub(LiveBot.class);
    private final StudioDriver driver = McpBudgetTest.stub(StudioDriver.class);
    private final RunLog log = new RunLog(Clock.systemUTC(), () -> false);

    /** A screen with one tool of its own, asking {@code question} when it is not blank. */
    private static ToolGroup screen(String question, List<String> news) {
        return new ToolGroup() {
            @Override
            public List<AssistantTool<?>> tools() {
                return List.of(McpTools.said(LOOK, "Look at the test screen.", () -> "seen"));
            }

            @Override
            public Set<String> answering() {
                return Set.of(LOOK);
            }

            @Override
            public String busy() {
                return question;
            }

            @Override
            public boolean asking() {
                return !question.isBlank();
            }

            @Override
            public List<String> news() {
                return news;
            }
        };
    }

    private List<SyncToolSpecification> tools(ToolGroup group) {
        return McpTools.studio(json, bot, driver, log, group, () -> { });
    }

    @Test
    void noScreenServesTheOwnToolsAndAGroupAddsItsOwn() {
        Set<String> own = McpTools.names(tools(ToolGroup.NONE));
        Set<String> with = McpTools.names(tools(screen("", List.of())));

        assertFalse(own.contains("ui"));
        assertFalse(own.contains(LOOK));
        Set<String> expected = new HashSet<>(own);
        expected.add(LOOK);
        assertEquals(expected, with);
    }

    @Test
    void aQuestionOnScreenRefusesTheOwnToolsButNotTheOneThatAnswersIt() {
        Map<String, SyncToolSpecification> byName = tools(screen("A question is on screen.", List.of())).stream()
                .collect(Collectors.toMap(t -> t.tool().name(), t -> t));

        String refused = text(call(byName.get("list_files")));
        String answered = text(call(byName.get(LOOK)));

        assertTrue(refused.startsWith("REFUSED: A question is on screen."), refused);
        assertEquals("seen", answered);
    }

    @Test
    void whatTheScreenSaysEndedFollowsTheAnswer() {
        SyncToolSpecification look = tools(screen("", List.of("The question on screen ended. Done."))).stream()
                .filter(t -> t.tool().name().equals(LOOK)).findFirst().orElseThrow();

        assertEquals("seen\nThe question on screen ended. Done.", text(call(look)));
    }

    private static CallToolResult call(SyncToolSpecification tool) {
        return tool.callHandler().apply(null, new CallToolRequest(tool.tool().name(), Map.of()));
    }

    private static String text(CallToolResult result) {
        return result.content().stream().map(c -> ((TextContent) c).text()).collect(Collectors.joining("\n"));
    }
}
