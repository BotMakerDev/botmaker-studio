package com.botmaker.studio.assist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bot's {@code .claude/settings.json}: the guard's rules added, the user's own kept, a foreign file left alone. */
class BotGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path bot;

    private List<String> deny() throws IOException {
        List<String> rules = new ArrayList<>();
        JSON.readTree(bot.resolve(BotGuard.FILE).toFile()).path("permissions").path("deny")
                .forEach(r -> rules.add(r.asText()));
        return rules;
    }

    @Test
    void aBotWithNoSettingsGetsEveryRule() throws IOException {
        assertEquals(BotGuard.Result.WRITTEN, BotGuard.ensure(bot));
        assertEquals(BotGuard.DENY, deny());
        assertTrue(deny().contains("Edit(./src/**)"));
        assertTrue(deny().contains("Bash(sed -i *)"));
        assertEquals(BotGuard.Result.ALREADY, BotGuard.ensure(bot), "a second open writes nothing");
    }

    @Test
    void theUsersOwnSettingsAndRulesStay() throws IOException {
        Files.createDirectories(bot.resolve(".claude"));
        Files.writeString(bot.resolve(BotGuard.FILE), """
                {"model": "opus", "permissions": {"allow": ["Bash(git *)"], "deny": ["WebFetch", "Write(./src/**)"]}}
                """);
        assertEquals(BotGuard.Result.WRITTEN, BotGuard.ensure(bot));
        JsonNode root = JSON.readTree(bot.resolve(BotGuard.FILE).toFile());
        assertEquals("opus", root.path("model").asText());
        assertEquals("Bash(git *)", root.path("permissions").path("allow").get(0).asText());
        List<String> rules = deny();
        assertEquals("WebFetch", rules.get(0));
        assertEquals(1, rules.stream().filter("Write(./src/**)"::equals).count(), "a rule present is not doubled");
        assertTrue(rules.containsAll(BotGuard.DENY));
    }

    @Test
    void aFileThatIsNotSettingsIsLeftAlone() throws IOException {
        Files.createDirectories(bot.resolve(".claude"));
        Files.writeString(bot.resolve(BotGuard.FILE), "not json {");
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.ensure(bot));
        assertEquals("not json {", Files.readString(bot.resolve(BotGuard.FILE)));
        Files.writeString(bot.resolve(BotGuard.FILE), "{\"permissions\": {\"deny\": \"Edit\"}}");
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.ensure(bot));
    }

    @Test
    void aLinkedSettingsFileIsNotFollowed(@TempDir Path dotfiles) throws IOException {
        Path shared = Files.writeString(dotfiles.resolve("settings.json"), "{}");
        Files.createDirectories(bot.resolve(".claude"));
        Files.createSymbolicLink(bot.resolve(BotGuard.FILE), shared);
        assertEquals(BotGuard.Result.LEFT_ALONE, BotGuard.ensure(bot));
        assertEquals("{}", Files.readString(shared));
    }
}
