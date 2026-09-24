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

    /**
     * The command that adds this endpoint to Claude Code. Other clients take the same URL and header in their
     * own config format.
     */
    public String claudeCodeCommand() {
        return "claude mcp add --transport http botmaker-studio http://127.0.0.1:" + port + McpEndpoint.PATH
                + " --header \"Authorization: Bearer " + token + "\"";
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
