package com.botmaker.studio.assist;

import com.botmaker.studio.events.EventBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
        RunLog log = new RunLog(bus, Clock.systemUTC(), () -> false);
        try {
            List<SyncToolSpecification> tools = McpTools.studio(new JacksonMcpJsonMapper(JSON), stub(LiveBot.class),
                    stub(StudioDriver.class), log, () -> { });
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

    /**
     * {@code type}, answering nothing: listing the tools calls none of their handlers. A list is empty, an optional
     * empty, a default method its own body.
     */
    private static <T> T stub(Class<T> type) {
        InvocationHandler none = (proxy, method, args) -> {
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            Class<?> returns = method.getReturnType();
            if (returns == List.class) return List.of();
            if (returns == Optional.class) return Optional.empty();
            if (returns == boolean.class) return false;
            if (returns == int.class) return 0;
            if (returns == long.class) return 0L;
            if (returns == String.class) return "";
            return null;
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, none));
    }
}
