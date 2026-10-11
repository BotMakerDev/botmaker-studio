package com.botmaker.studio.assist;

import com.botmaker.shared.config.CacheDirs;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;

/**
 * Whether the MCP endpoint is on, the port it listens on and its token — {@code mcp.json} beside
 * {@code credentials.json}, readable by the user only. The port and token are kept so a client configured
 * once keeps working across restarts.
 *
 * @param port {@link #DEFAULT_PORT} unless the user moved it
 */
public record McpConfig(boolean enabled, int port, String token) {

    public static final int DEFAULT_PORT = 7431;
    private static final Path FILE = CacheDirs.cacheRoot().resolve("mcp.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The saved configuration, or a fresh one — off, default port, new token — when there is none. */
    public static McpConfig load() {
        return read(FILE);
    }

    static McpConfig read(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                McpConfig saved = JSON.readValue(file.toFile(), McpConfig.class);
                if (saved.token() != null && !saved.token().isBlank()) return saved;
            }
        } catch (IOException e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return new McpConfig(false, DEFAULT_PORT, newToken());
    }

    public McpConfig withEnabled(boolean on) {
        return new McpConfig(on, port, token);
    }

    public void save() {
        write(FILE);
    }

    void write(Path file) {
        try {
            Files.createDirectories(file.getParent());
            JSON.writeValue(file.toFile(), this);
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX filesystem: the file stays under the user's own profile directory.
        } catch (IOException e) {
            System.err.println("Could not write " + file + ": " + e.getMessage());
        }
    }

    /** Where {@link #claudeCodeCommand} keeps the endpoint's Claude Code config, beside this file. */
    public static final Path CLAUDE_FILE = FILE.resolveSibling("claude-mcp.json");

    /**
     * The command that starts Claude Code on this endpoint, for a terminal in the bot's folder, as the Assistant
     * tab starts it: the MCP tools allowed, every file-editing tool denied, and the in-place shell edits the bot's
     * {@code .claude/settings.json} denies too ({@link BotGuard}). The endpoint and its token are written to
     * {@code configFile}, readable by the user only, so the token never sits in the command, shell history or a
     * process listing. Other clients take the same URL and header in their own config format.
     */
    public String claudeCodeCommand(Path configFile) throws IOException {
        Path config = AiTool.writeClaudeConfig(configFile, "http://127.0.0.1:" + port + McpEndpoint.PATH, token);
        StringBuilder command = new StringBuilder("claude");
        for (String arg : AiTool.claudeArguments(config, BotGuard.SHELL)) {
            command.append(' ').append(arg.matches("[A-Za-z0-9_./:=-]+") ? arg : '"' + arg + '"');
        }
        return command.toString();
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
