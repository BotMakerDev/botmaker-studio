package com.botmaker.studio.sharing;

import com.botmaker.studio.project.BotIcon;
import com.botmaker.studio.sharing.ProgramEntries.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgramEntriesTest {

    private static final Entry FIRESTONE = new Entry("liqiyedev-firestone", "Firestone", "LiQiyeDev/Firestone",
            Optional.empty());

    @Test
    void theLinuxEntryRunsTheBotByItsRepository() {
        var linux = new ProgramEntries.Linux(Path.of("/home/u/.local/share/applications"),
                Path.of("/home/u/.local/share/botmaker/menu-icons"), "/opt/botmaker-studio/bin/BotMaker Studio");
        assertEquals("""
                [Desktop Entry]
                Type=Application
                Name=Firestone
                Comment=Run Firestone with BotMaker
                Exec="/opt/botmaker-studio/bin/BotMaker Studio" --run=LiQiyeDev/Firestone
                Icon=/home/u/.local/share/botmaker/menu-icons/liqiyedev-firestone.png
                Terminal=false
                Categories=Utility;
                """, linux.desktop(FIRESTONE, Path.of("/home/u/.local/share/botmaker/menu-icons/liqiyedev-firestone.png")));
        assertEquals(List.of(Path.of("/home/u/.local/share/applications/botmaker-bot-liqiyedev-firestone.desktop"),
                Path.of("/home/u/.local/share/botmaker/menu-icons/liqiyedev-firestone.png")),
                linux.files(FIRESTONE, "Firestone"));
    }

    @Test
    void anExecArgumentIsQuotedAndItsSpecialCharactersEscaped() {
        assertEquals("--run=a/b", ProgramEntries.Linux.execArg("--run=a/b"));
        assertEquals("\"--run=/home/u/My \\\"bot\\\" \\$x\"",ProgramEntries.Linux.execArg("--run=/home/u/My \"bot\" $x"));
        assertEquals("100%%", ProgramEntries.Linux.execArg("100%"));
        // The string value then doubles each backslash, as the Desktop Entry Specification reads it back.
        assertEquals("a\\\\b\\nc", ProgramEntries.Linux.value("a\\b\nc"));
    }

    @Test
    void aBotOfTheUsersOwnRunsByItsFolder() {
        Entry own = Entry.ofFolder(Path.of("/home/u/BotMakerProjects/My Bot"), "My Bot");
        assertEquals("/home/u/BotMakerProjects/My Bot", own.run());
        assertTrue(own.id().matches("local-my-bot-[0-9a-f]+"), own.id());
        String id = Entry.ofRepo("LiQiyeDev", "Firestone", "Firestone", Path.of("/x")).id();
        assertTrue(id.matches("liqiyedev-firestone-[0-9a-f]+"), id);
        // Two repositories idOf alone would spell alike get two ids.
        assertFalse(Entry.ofRepo("a", "b-c", "x", Path.of("/x")).id()
                .equals(Entry.ofRepo("a-b", "c", "x", Path.of("/x")).id()));
    }

    @Test
    void aTitleWindowsCannotNameGetsOneItCan() {
        var names = ProgramEntries.fileNames(List.of(new Entry("a", "CON", "a/b", Optional.empty()),
                new Entry("b", "Done...", "a/c", Optional.empty())));
        assertEquals(List.of("CON bot", "Done"), List.copyOf(names.values()));
    }

    @Test
    void twoBotsOfOneNameGetTwoFileNames() {
        Entry other = new Entry("someone-firestone", "Firestone", "someone/firestone", Optional.empty());
        Entry odd = new Entry("a-b", "a/b: c?", "a/b", Optional.empty());
        var names = ProgramEntries.fileNames(List.of(FIRESTONE, other, odd));
        assertEquals("Firestone", names.get(FIRESTONE));
        assertEquals("Firestone (someone-firestone)", names.get(other));
        assertEquals("a b  c", names.get(odd));
    }

    @Test
    void theWindowsAndMacPlacesAreUnderTheGivenHome() {
        Path appData = Path.of("/home/u/AppData/Roaming");
        var windows = new ProgramEntries.Windows(ProgramEntries.Windows.startMenu(appData), Path.of("/icons"),
                "C:/Program Files/BotMaker Studio/BotMaker Studio.exe");
        assertEquals(List.of(appData.resolve("Microsoft/Windows/Start Menu/Programs/BotMaker bots/Firestone.lnk"),
                Path.of("/icons/liqiyedev-firestone.ico"), Path.of("/icons/liqiyedev-firestone.lnk-made")),
                windows.files(FIRESTONE, "Firestone"));
        assertEquals("--run=LiQiyeDev/Firestone", ProgramEntries.Windows.arguments(FIRESTONE));
        assertEquals("\"--run=C:\\Users\\u\\My Bot\"", ProgramEntries.Windows.arguments(
                new Entry("x", "My Bot", "C:\\Users\\u\\My Bot", Optional.empty())));

        var mac = new ProgramEntries.Mac(Path.of("/Users/u/Applications/BotMaker bots"), Path.of("/icons"));
        assertEquals(List.of(Path.of("/Users/u/Applications/BotMaker bots/Firestone.app")),
                mac.files(FIRESTONE, "Firestone"));
        assertEquals("#!/bin/sh\nexec /usr/bin/open -n -b com.botmaker.studio --args '--run=it'\\''s'\n",
                ProgramEntries.Mac.script(new Entry("x", "x", "it's", Optional.empty())));
        assertTrue(ProgramEntries.Mac.infoPlist(new Entry("x", "A & B", "a/b", Optional.empty()))
                .contains("<key>CFBundleName</key><string>A &amp; B</string>"));
    }

    @Test
    void theIcoHoldsOne256PixelPng() throws Exception {
        byte[] png = ProgramEntries.png(FIRESTONE, BotIcon.SIZE);
        byte[] ico = ProgramEntries.ico(png);
        assertArrayEquals(new byte[] {0, 0, 1, 0, 1, 0}, Arrays.copyOf(ico, 6));
        ByteBuffer entry = ByteBuffer.wrap(ico, 6, 16).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, entry.get());                    // 256 wide
        assertEquals(0, entry.get());                    // 256 high
        entry.position(entry.position() + 2);
        assertEquals(1, entry.getShort());
        assertEquals(32, entry.getShort());
        assertEquals(png.length, entry.getInt());
        assertEquals(22, entry.getInt());
        assertArrayEquals(png, Arrays.copyOfRange(ico, 22, ico.length));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(256, image.getWidth());
    }

    @Test
    void theIcnsHoldsOneIc08Png() throws Exception {
        byte[] png = ProgramEntries.png(FIRESTONE, BotIcon.SIZE);
        byte[] icns = ProgramEntries.icns(png);
        assertEquals("icns", new String(icns, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(icns.length, ByteBuffer.wrap(icns, 4, 4).getInt());
        assertEquals("ic08", new String(icns, 8, 4, StandardCharsets.US_ASCII));
        assertEquals(8 + png.length, ByteBuffer.wrap(icns, 12, 4).getInt());
        assertArrayEquals(png, Arrays.copyOfRange(icns, 16, icns.length));
    }

    @Test
    void syncWritesTheWantedAndRemovesTheRestOfOursOnly(@TempDir Path home) throws Exception {
        Path apps = home.resolve("applications");
        Path icons = home.resolve("icons");
        Files.createDirectories(apps);
        Files.writeString(apps.resolve("botmaker-studio-BotMaker_Studio.desktop"), "Studio's own");
        Files.writeString(apps.resolve("botmaker-bot-gone.desktop"), "a bot removed since");
        Path bot = Files.createDirectories(home.resolve("bot"));
        BotIcon.write(bot, new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB));
        var linux = new ProgramEntries.Linux(apps, icons, "/opt/b/bin/studio");

        Entry entry = Entry.ofRepo("LiQiyeDev", "Firestone", "Firestone", bot);
        linux.sync(List.of(entry));

        assertTrue(Files.isRegularFile(apps.resolve("botmaker-bot-" + entry.id() + ".desktop")));
        assertTrue(Files.isRegularFile(icons.resolve(entry.id() + ".png")));
        assertFalse(Files.exists(apps.resolve("botmaker-bot-gone.desktop")));
        assertEquals("Studio's own", Files.readString(apps.resolve("botmaker-studio-BotMaker_Studio.desktop")));

        linux.sync(List.of());
        assertFalse(Files.exists(apps.resolve("botmaker-bot-" + entry.id() + ".desktop")));
        assertFalse(Files.exists(icons.resolve(entry.id() + ".png")));
    }

    @Test
    void aMacEntryIsAnAppWithAnExecutableScript(@TempDir Path home) throws Exception {
        var mac = new ProgramEntries.Mac(home.resolve("BotMaker bots"), home.resolve("icons"));
        mac.sync(List.of(FIRESTONE));
        Path app = home.resolve("BotMaker bots/Firestone.app/Contents");
        assertTrue(Files.isExecutable(app.resolve("MacOS/run")));
        assertTrue(Files.isRegularFile(app.resolve("Resources/icon.icns")));
        assertTrue(Files.readString(app.resolve("Info.plist")).contains("com.botmaker.bot.liqiyedev-firestone"));
        mac.sync(List.of());
        assertFalse(Files.exists(home.resolve("BotMaker bots/Firestone.app")));

        // A title changed only in case is the same folder on a Mac's disk: the bundle written is not removed.
        Files.createDirectories(home.resolve("BotMaker bots/Firestone.app"));
        var sameFolder = new ProgramEntries.Mac(home.resolve("BotMaker bots"), home.resolve("icons"));
        sameFolder.sync(List.of(new Entry("liqiyedev-firestone", "firestone", "LiQiyeDev/Firestone",
                Optional.empty())));
        assertTrue(Files.exists(home.resolve("BotMaker bots/Firestone.app")));
    }

    @Test
    void aDoubleClickedFileIsTheBmkToOpen() {
        assertEquals(Optional.of(Path.of("/d/x.bmk")), BmkOpen.requested(Optional.of("/d/x.bmk"), List.of()));
        assertEquals(Optional.of(Path.of("/d/my bot.bmk")),
                BmkOpen.requested(Optional.empty(), List.of("-v", "/d/my bot.bmk")));
        assertEquals(Optional.of(Path.of("/d/my bot.bmk")),
                BmkOpen.requested(Optional.empty(), List.of("file:///d/my%20bot.bmk")));
        assertEquals(Optional.empty(), BmkOpen.requested(Optional.empty(), List.of("/d/readme.txt")));
    }
}
