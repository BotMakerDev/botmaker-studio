package com.botmaker.studio.assist;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The AI command-line tools the Assistant tab can run, and how each is told about Studio.
 *
 * <p>Each one runs in a terminal inside Studio, logged in however the user logged it in (a Claude
 * subscription included, which no API client can use). Each is given Studio's MCP endpoint and <b>denied
 * direct file edits</b> in its own vocabulary, so a change to the bot goes through the MCP tools: palette
 * ids, values the grammar reads, a compile after every edit, one undo step each ({@code 38-llm-edits.md}).
 *
 * <p><b>The token never goes on a command line</b>, where {@code ps} shows it to every user: it is in a
 * config file only the user can read, in a directory Studio deletes when the tab goes, or in the tool's
 * environment.
 */
public enum AiTool {
    CLAUDE_CODE("claude-code", "Claude Code", "claude", "npm install -g @anthropic-ai/claude-code"),
    CODEX("codex", "Codex", "codex", "npm install -g @openai/codex"),
    GEMINI("gemini", "Gemini CLI", "gemini", "npm install -g @google/gemini-cli"),
    OPENCODE("opencode", "opencode", "opencode", "curl -fsSL https://opencode.ai/install | bash"),
    UNKNOWN("unknown", "Unknown tool", "", "");

    /** The name the endpoint is registered under in every tool, which is also Claude Code's tool prefix. */
    public static final String SERVER = "botmaker-studio";
    /** The environment variable Codex reads the bearer token from. */
    static final String TOKEN_ENV = "BOTMAKER_MCP_TOKEN";

    /** Claude Code's file-writing tools. Its Bash can write files too, so it is denied unless allowed. */
    static final List<String> CLAUDE_EDIT_TOOLS = List.of("Edit", "MultiEdit", "Write", "NotebookEdit");

    static final String CLAUDE_PROMPT = "You are running inside BotMaker Studio. Change the user's bot only "
            + "through the " + SERVER + " MCP tools (read_tree, list_palette, insert_block, set_slot, "
            + "delete_block, list_errors); direct file edits are disabled in this session.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String id;
    private final String displayName;
    private final String executable;
    private final String installHint;

    AiTool(String id, String displayName, String executable, String installHint) {
        this.id = id;
        this.displayName = displayName;
        this.executable = executable;
        this.installHint = installHint;
    }

    /** Where the endpoint is, its token, and a private directory this session's config files may go in. */
    public record Connection(String url, String token, Path configDir) {}

    /** The program's arguments (the executable first) and what to add to its environment. */
    public record Launch(List<String> command, Map<String, String> env) {
        public Launch {
            command = List.copyOf(command);
            env = Map.copyOf(env);
        }
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String executable() {
        return executable;
    }

    public String installHint() {
        return installHint;
    }

    /** The tools a user can pick, in menu order. */
    public static List<AiTool> offered() {
        return List.of(CLAUDE_CODE, CODEX, GEMINI, OPENCODE);
    }

    /** The tool {@code id} names, or {@link #UNKNOWN}. */
    public static AiTool fromId(String id) {
        for (AiTool tool : values()) {
            if (tool.id.equals(id)) return tool;
        }
        return UNKNOWN;
    }

    /** One line saying how this tool is kept from writing files, for the tab's status. */
    public String guard(boolean allowShell) {
        return switch (this) {
            case CLAUDE_CODE -> allowShell ? "Edit and Write denied; shell commands allowed"
                    : "Edit, Write and shell commands denied";
            case CODEX -> "read-only sandbox: it can read and run commands, not write files";
            case GEMINI -> allowShell ? "write_file and replace excluded; shell commands allowed"
                    : "write_file, replace and shell commands excluded";
            case OPENCODE -> allowShell ? "edits denied; shell commands ask first" : "edits and shell commands denied";
            case UNKNOWN -> "";
        };
    }

    /**
     * How to start this tool at {@code executable} against {@code connection}. Writes the tool's config file
     * into {@code connection.configDir()}, readable by the user only, when the tool takes one.
     */
    public Launch launch(Path executable, Connection connection, boolean allowShell) throws IOException {
        String exe = executable.toString();
        String bearer = "Bearer " + connection.token();
        return switch (this) {
            case CLAUDE_CODE -> {
                Path config = writePrivate(connection.configDir().resolve("claude-mcp.json"), Map.of("mcpServers",
                        Map.of(SERVER, Map.of("type", "http", "url", connection.url(),
                                "headers", Map.of("Authorization", bearer)))));
                List<String> command = new ArrayList<>(List.of(exe, "--mcp-config", config.toString(),
                        "--allowedTools", "mcp__" + SERVER, "--disallowedTools"));
                command.addAll(CLAUDE_EDIT_TOOLS);
                if (!allowShell) command.add("Bash");
                command.addAll(List.of("--append-system-prompt", CLAUDE_PROMPT));
                yield new Launch(command, Map.of());
            }
            case CODEX -> new Launch(List.of(exe, "--sandbox", "read-only",
                    "-c", "mcp_servers." + SERVER + ".url=\"" + connection.url() + "\"",
                    "-c", "mcp_servers." + SERVER + ".bearer_token_env_var=\"" + TOKEN_ENV + "\""),
                    Map.of(TOKEN_ENV, connection.token()));
            case GEMINI -> {
                List<String> excluded = new ArrayList<>(List.of("write_file", "replace"));
                if (!allowShell) excluded.add("run_shell_command");
                Path settings = writePrivate(connection.configDir().resolve("gemini-settings.json"), Map.of(
                        "mcpServers", Map.of(SERVER, Map.of("httpUrl", connection.url(),
                                "headers", Map.of("Authorization", bearer), "trust", true)),
                        "tools", Map.of("exclude", excluded)));
                yield new Launch(List.of(exe), Map.of("GEMINI_CLI_SYSTEM_SETTINGS_PATH", settings.toString()));
            }
            case OPENCODE -> {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("$schema", "https://opencode.ai/config.json");
                config.put("mcp", Map.of(SERVER, Map.of("type", "remote", "url", connection.url(),
                        "headers", Map.of("Authorization", bearer), "enabled", true)));
                config.put("permission", Map.of("edit", "deny", "bash", allowShell ? "ask" : "deny"));
                Path file = writePrivate(connection.configDir().resolve("opencode.json"), config);
                yield new Launch(List.of(exe), Map.of("OPENCODE_CONFIG", file.toString()));
            }
            case UNKNOWN -> throw new IllegalStateException("no tool to launch");
        };
    }

    /**
     * Where this tool is installed: {@code path} (a {@code PATH} value) first, then the usual per-user install
     * directories, which a desktop-launched Studio's {@code PATH} often lacks.
     */
    public Optional<Path> locate(String path, Path home) {
        if (this == UNKNOWN) return Optional.empty();
        List<Path> dirs = new ArrayList<>();
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (!dir.isBlank()) dirs.add(Path.of(dir));
            }
        }
        for (String extra : List.of(".local/bin", ".opencode/bin", ".npm-global/bin", ".bun/bin", "bin")) {
            dirs.add(home.resolve(extra));
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> names = windows ? List.of(executable + ".exe", executable + ".cmd") : List.of(executable);
        for (Path dir : dirs) {
            for (String name : names) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Asks the user's login shell where this tool is, for a tool installed through a version manager that only
     * a login shell puts on {@code PATH}. Blocks for up to three seconds; never call it on the FX thread.
     */
    public Optional<Path> locateByLoginShell(String shell) {
        if (this == UNKNOWN) return Optional.empty();
        try {
            Process probe = new ProcessBuilder(posixShell(shell), "-lc", "command -v " + executable)
                    .redirectErrorStream(true).start();
            if (!probe.waitFor(3, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return Optional.empty();
            }
            String out = new String(probe.getInputStream().readAllBytes()).strip();
            if (probe.exitValue() != 0 || out.isEmpty()) return Optional.empty();
            Path found = Path.of(out.lines().reduce((a, b) -> b).orElse(out));
            return Files.isExecutable(found) ? Optional.of(found) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * {@code command} run by the user's login shell, which puts on {@code PATH} what a tool installed through
     * {@code nvm}, {@code mise} or similar needs to start ({@code node}, mostly) and a desktop-launched Studio
     * lacks. The shell {@code exec}s the tool, so the terminal's process is the tool. Unchanged on Windows.
     */
    public static List<String> viaLoginShell(String shell, List<String> command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows) return command;
        List<String> wrapped = new ArrayList<>(List.of(posixShell(shell), "-lc", "exec \"$0\" \"$@\""));
        wrapped.addAll(command);
        return wrapped;
    }

    /** {@code shell} when it speaks POSIX {@code sh}; fish and nu do not, so they hand over to bash. */
    static String posixShell(String shell) {
        String name = shell == null || shell.isBlank() ? "" : Path.of(shell).getFileName().toString();
        return List.of("bash", "zsh", "sh", "ksh", "dash").contains(name) ? shell : "/bin/bash";
    }

    /** Writes {@code value} as JSON to {@code file}, readable and writable by the user only where the OS says so. */
    private static Path writePrivate(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file);
        }
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        return file;
    }
}
