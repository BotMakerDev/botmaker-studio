package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.ui.app.home.MyBots.Facts;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The home's My bots cards (2026-10-25): who a bot is by, whether a newer tag is listed, a gone folder. */
class MyBotsTest {

    @TempDir
    Path root;

    private static final LocalDateTime MONDAY = LocalDateTime.of(2026, 10, 19, 9, 0);

    private Path project(String name) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir.resolve("src/main/java"));
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    private static Facts facts(String originalUrl, String version) {
        return new Facts(Optional.ofNullable(originalUrl), Optional.ofNullable(version), true, MONDAY);
    }

    private static MyBot only(List<MyBot> bots) {
        assertEquals(1, bots.size());
        return bots.getFirst();
    }

    @Test
    void aClonedBotIsByItsOwner() throws IOException {
        Path dir = project("Firestone");
        MyBot bot = only(MyBots.scan(List.of(dir),
                d -> facts("https://github.com/LiQiyeDev/Firestone.git", "1.0.0"), Map.of()));
        assertEquals(Optional.of("LiQiyeDev"), bot.owner());
        assertEquals("by LiQiyeDev · 1.0.0", RunHome.who(bot));
        assertEquals(State.READY, bot.state());
    }

    @Test
    void ownBotIsMine() throws IOException {
        MyBot bot = only(MyBots.scan(List.of(project("PushTest")), d -> facts(null, null), Map.of()));
        assertEquals(Optional.empty(), bot.owner());
        assertEquals("mine", RunHome.who(bot));
    }

    @Test
    void anUpdateIsAvailable() throws IOException {
        Path dir = project("Firestone");
        MyBot bot = only(MyBots.scan(List.of(dir),
                d -> facts("git@github.com:LiQiyeDev/Firestone.git", "1.0.0"),
                Map.of(MyBots.key("LiQiyeDev", "Firestone"), "1.1.0")));
        assertEquals(Optional.of("1.1.0"), bot.available());

        MyBot current = only(MyBots.scan(List.of(dir),
                d -> facts("git@github.com:LiQiyeDev/Firestone.git", "1.1.0"),
                Map.of(MyBots.key("LiQiyeDev", "Firestone"), "1.1.0")));
        assertEquals(Optional.empty(), current.available());
    }

    @Test
    void aMissingFolderSaysSo() throws IOException {
        Path gone = root.resolve("Gone");
        List<MyBot> bots = MyBots.scan(List.of(gone, project("Here")), d -> {
            assertFalse(d.equals(gone), "a gone folder is not read");
            return facts(null, null);
        }, Map.of());
        assertEquals(List.of("Here", "Gone"), bots.stream().map(MyBot::name).toList(), "gone ones last");
        assertEquals(State.GONE, bots.get(1).state());
        assertFalse(bots.get(1).runnable());
    }
}
