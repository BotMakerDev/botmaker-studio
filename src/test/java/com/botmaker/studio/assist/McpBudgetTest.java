package com.botmaker.studio.assist;

import com.botmaker.core.assist.StudioDriver;
import com.botmaker.core.assist.LiveBot;
import com.botmaker.core.assist.RunLog;
import com.botmaker.core.assist.McpTools;
import com.botmaker.core.assist.Stubs;
import com.botmaker.core.events.EventBus;
import com.botmaker.studio.services.RunLogOnBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Studio's own tools cost an assistant before it calls one: every tool's name, description and input schema as
 * {@code tools/list} sends them, and the instructions said at connect. A client that does not defer tools (Codex,
 * Gemini and opencode in the Assistant tab) pays all of it on every request. The sizes are written to
 * {@code target/mcp-budget.tsv}, biggest first, and the total may not pass {@link #CEILING}: a tool that grows its
 * words past it says so here, not in a client's bill. The plugins' tools are measured by their own builds (the
 * SDK's {@code SdkToolBudgetTest}).
 */
class McpBudgetTest {

    /**
     * Characters, Studio's tools and the instructions together; about four to a token. 30,913 on 2026-10-15, before
     * the tools were typed and merged; 31,650 once typed (each action's fields said, the method parameters
     * described); 23,847 once merged into tools of several actions and trimmed, 37 tools where there were 58. The ceiling leaves
     * about a tenth over that.
     */
    static final int CEILING = 26_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void studiosToolsStayWithinTheirBudget() throws Exception {
        EventBus bus = new EventBus(false);
        RunLog log = RunLogOnBus.listen(bus, new RunLog(Clock.systemUTC(), () -> false));
        try {
            List<SyncToolSpecification> tools = McpTools.studio(new JacksonMcpJsonMapper(JSON), Stubs.of(LiveBot.class),
                    Stubs.of(StudioDriver.class), log, StudioToolGroup.INSTANCE, () -> { });
            List<Cost> costs = tools.stream().map(t -> cost(t.tool()))
                    .sorted(Comparator.comparingInt(Cost::chars).reversed()).toList();
            int instructions = McpTools.INSTRUCTIONS.length();
            int total = instructions + costs.stream().mapToInt(Cost::chars).sum();

            StringBuilder report = new StringBuilder("tool\tchars\tdescription\tschema\n");
            report.append("(instructions)\t").append(instructions).append("\t\t\n");
            costs.forEach(c -> report.append(c.name()).append('\t').append(c.chars()).append('\t')
                    .append(c.description()).append('\t').append(c.schema()).append('\n'));
            report.append("(total, ").append(costs.size()).append(" tools)\t").append(total).append("\t\t\n");
            Path out = Path.of("target", "mcp-budget.tsv");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);

            assertTrue(total <= CEILING, "Studio's tools and instructions are " + total + " characters, past the "
                    + CEILING + " budget: shorten what grew (" + out.toAbsolutePath() + ").");
        } finally {
            log.close();
        }
    }

    /** One tool's size as {@code tools/list} sends it. */
    private record Cost(String name, int description, int schema) {
        int chars() {
            return name.length() + description + schema;
        }
    }

    private static Cost cost(Tool tool) {
        try {
            return new Cost(tool.name(), tool.description() == null ? 0 : tool.description().length(),
                    JSON.writeValueAsString(tool.inputSchema()).length());
        } catch (Exception e) {
            throw new IllegalStateException(tool.name(), e);
        }
    }
}
