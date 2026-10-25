package com.botmaker.studio.ui.app.home;

import com.botmaker.studio.ui.app.home.MyBots.CompileState;
import com.botmaker.studio.ui.app.home.MyBots.Facts;
import com.botmaker.studio.ui.app.home.MyBots.MyBot;
import com.botmaker.studio.ui.app.home.MyBots.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

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
        return new Facts(Optional.ofNullable(originalUrl), Optional.ofNullable(version), true, MONDAY,
                Optional.empty());
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

    /** A card shown again is not compiled again; a changed source is, and a failed check is not kept. */
    @Test
    void aBotIsCompiledOncePerChange() throws Exception {
        Path dir = project("Compiled");
        Path main = Files.writeString(dir.resolve("src/main/java/Bot.java"), "class Bot {}");
        AtomicInteger checks = new AtomicInteger();
        Function<Path, CompileState> check = d -> {
            checks.incrementAndGet();
            return CompileState.ERRORS;
        };

        assertEquals(CompileState.ERRORS, MyBots.compileState(dir, check).get(5, TimeUnit.SECONDS));
        assertEquals(CompileState.ERRORS, MyBots.compileState(dir, check).get(5, TimeUnit.SECONDS));
        assertEquals(1, checks.get(), "the second show is answered from the first check");

        Files.setLastModifiedTime(main, FileTime.fromMillis(Files.getLastModifiedTime(main).toMillis() + 5_000));
        assertEquals(CompileState.OK, MyBots.compileState(dir, d -> CompileState.OK).get(5, TimeUnit.SECONDS));

        Path other = project("Unread");
        Function<Path, CompileState> throwing = d -> {
            throw new IllegalStateException("no javac");
        };
        assertEquals(CompileState.UNKNOWN, MyBots.compileState(other, throwing).get(5, TimeUnit.SECONDS));
        assertEquals(CompileState.OK, MyBots.compileState(other, d -> CompileState.OK).get(5, TimeUnit.SECONDS));
    }

    /**
     * A bot opened from a .bmk is a card by its author, with no update badge (it has no history to merge into);
     * once Edit in Studio cloned its repository, the clone's card takes its place.
     */
    @Test
    void anUnpackedBotGivesWayToItsClone() throws IOException {
        Path unpacked = project("v1.0.0");
        Path clone = project("Firestone");
        Function<Path, Facts> facts = d -> d.equals(unpacked)
                ? new Facts(Optional.of("https://github.com/LiQiyeDev/Firestone.git"), Optional.of("v1.0.0"), true,
                MONDAY, Optional.of("Firestone"), true)
                : facts("https://github.com/LiQiyeDev/Firestone.git", "v1.0.0");
        Map<String, String> newer = Map.of(MyBots.key("LiQiyeDev", "Firestone"), "v1.1.0");

        MyBot alone = only(MyBots.scan(List.of(unpacked), facts, newer));
        assertEquals("by LiQiyeDev · v1.0.0", RunHome.who(alone));
        assertEquals(Optional.empty(), alone.available());
        assertEquals(true, alone.unpacked());

        MyBot both = only(MyBots.scan(List.of(unpacked, clone), facts, newer));
        assertEquals(clone, both.dir());
        assertEquals(Optional.of("v1.1.0"), both.available());
    }
}
