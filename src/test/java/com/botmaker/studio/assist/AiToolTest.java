package com.botmaker.studio.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How each AI tool is started: pointed at Studio's endpoint, denied direct edits in its own vocabulary, and
 * never with the token on its command line.
 */
class AiToolTest {

    private static final String TOKEN = "s3cr3t-token";
    private static final String URL = "http://127.0.0.1:7431/mcp";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;

    private AiTool.Connection connection() {
        return new AiTool.Connection(URL, TOKEN, dir);
    }

    private AiTool.Launch launch(AiTool tool, boolean allowShell) throws IOException {
        AiTool.Launch launch = tool.launch(Path.of("/opt/" + tool.executable()), connection(), allowShell);
        assertEquals("/opt/" + tool.executable(), launch.command().getFirst());
        assertTrue(launch.command().stream().noneMatch(a -> a.contains(TOKEN)),
                tool + " put the token on its command line: " + launch.command());
        return launch;
    }

    private JsonNode privateJson(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " was not written");
        if (!System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "the file holds the token and must be the user's only");
        }
        return JSON.readTree(file.toFile());
    }

    @Test
    void idsAreStableAndTheParseIsTotal() {
        Set<String> ids = new HashSet<>();
        for (AiTool tool : AiTool.values()) {
            assertTrue(ids.add(tool.id()), "duplicate id " + tool.id());
            assertEquals(tool, AiTool.fromId(tool.id()));
        }
        assertEquals(AiTool.UNKNOWN, AiTool.fromId("cursor"));
        assertEquals(AiTool.UNKNOWN, AiTool.fromId(null));
        assertFalse(AiTool.offered().contains(AiTool.UNKNOWN));
    }

    @Test
    void claudeCodeGetsTheEndpointAndLosesItsEditTools() throws IOException {
        List<String> command = launch(AiTool.CLAUDE_CODE, false).command();
        int deny = command.indexOf("--disallowedTools");
        List<String> denied = command.subList(deny + 1, command.indexOf("--append-system-prompt"));
        assertTrue(denied.containsAll(List.of("Edit", "MultiEdit", "Write", "NotebookEdit", "Bash")), denied.toString());
        assertTrue(command.contains("mcp__" + AiTool.SERVER), "the MCP tools are allowed without a prompt each");

        JsonNode server = privateJson(Path.of(command.get(command.indexOf("--mcp-config") + 1)))
                .path("mcpServers").path(AiTool.SERVER);
        assertEquals("http", server.path("type").asText());
        assertEquals(URL, server.path("url").asText());
        assertEquals("Bearer " + TOKEN, server.path("headers").path("Authorization").asText());
    }

    @Test
    void claudeCodeKeepsBashWhenShellCommandsAreAllowed() throws IOException {
        List<String> command = launch(AiTool.CLAUDE_CODE, true).command();
        assertFalse(command.contains("Bash"));
        assertTrue(command.contains("Write"));
    }

    @Test
    void codexRunsReadOnlyAndReadsTheTokenFromItsEnvironment() throws IOException {
        AiTool.Launch launch = launch(AiTool.CODEX, true);
        List<String> command = launch.command();
        assertEquals("read-only", command.get(command.indexOf("--sandbox") + 1), "shell or not, it writes nothing");
        assertTrue(command.contains("mcp_servers." + AiTool.SERVER + ".url=\"" + URL + "\""), command.toString());
        assertTrue(command.contains("mcp_servers." + AiTool.SERVER + ".bearer_token_env_var=\"" + AiTool.TOKEN_ENV + "\""));
        assertEquals(TOKEN, launch.env().get(AiTool.TOKEN_ENV));
    }

    @Test
    void opencodeDeniesEditsInTheConfigItIsGiven() throws IOException {
        AiTool.Launch launch = launch(AiTool.OPENCODE, false);
        JsonNode config = privateJson(Path.of(launch.env().get("OPENCODE_CONFIG")));
        assertEquals("deny", config.path("permission").path("edit").asText());
        assertEquals("deny", config.path("permission").path("bash").asText());
        JsonNode server = config.path("mcp").path(AiTool.SERVER);
        assertEquals("remote", server.path("type").asText());
        assertEquals(URL, server.path("url").asText());

        JsonNode asking = privateJson(Path.of(launch(AiTool.OPENCODE, true).env().get("OPENCODE_CONFIG")));
        assertEquals("ask", asking.path("permission").path("bash").asText());
    }

    @Test
    void geminiExcludesItsWritingTools() throws IOException {
        AiTool.Launch launch = launch(AiTool.GEMINI, false);
        JsonNode settings = privateJson(Path.of(launch.env().get("GEMINI_CLI_SYSTEM_SETTINGS_PATH")));
        List<String> excluded = JSON.convertValue(settings.path("tools").path("exclude"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        assertTrue(excluded.containsAll(List.of("write_file", "replace", "run_shell_command")), excluded.toString());
        assertEquals(URL, settings.path("mcpServers").path(AiTool.SERVER).path("httpUrl").asText());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aToolIsStartedByAPosixLoginShell() {
        List<String> command = List.of("/opt/claude", "--flag");
        assertEquals(List.of("/bin/zsh", "-lc", "exec \"$0\" \"$@\"", "/opt/claude", "--flag"),
                AiTool.viaLoginShell("/bin/zsh", command));
        assertEquals("/bin/bash", AiTool.viaLoginShell("/usr/bin/fish", command).getFirst(),
                "fish does not speak \"$0\" \"$@\"");
        assertEquals("/bin/bash", AiTool.viaLoginShell(null, command).getFirst());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void locateFindsAnExecutableOnThePathAndSkipsAPlainFile() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path plain = Files.writeString(bin.resolve("codex"), "not executable");
        Path home = Files.createDirectories(dir.resolve("home"));
        assertEquals(Optional.empty(), AiTool.CODEX.locate(bin.toString(), home));

        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rwx------"));
        assertEquals(Optional.of(plain), AiTool.CODEX.locate("/nonexistent:" + bin, home));

        Path local = Files.createDirectories(home.resolve(".local/bin"));
        Path claude = Files.writeString(local.resolve("claude"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwx------"));
        assertEquals(Optional.of(claude), AiTool.CLAUDE_CODE.locate("", home),
                "a desktop-launched Studio's PATH often lacks ~/.local/bin");
    }
}
