package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deb, rpm and dmg register the {@code botmaker://} scheme and keep the {@code .bmk} type (2026-10-25). */
class PackagingSchemeTest {

    private static final Path PACKAGING = Path.of("packaging");

    @Test
    void theLinuxMenuEntryTakesALinkAndAFile() throws IOException {
        List<String> lines = Files.readAllLines(PACKAGING.resolve("linux/BotMaker Studio.desktop"));
        assertTrue(lines.contains("Exec=APPLICATION_LAUNCHER %u"), "a link, or a file as a path or a file: URI");
        List<String> mimes = lines.stream().filter(l -> l.startsWith("MimeType=")).toList();
        assertEquals(List.of("MimeType=application/x-botmaker-bot;x-scheme-handler/botmaker;"), mimes,
                "one MimeType line, the .bmk type of bmk.properties beside the scheme");
        assertTrue(Files.readString(PACKAGING.resolve("bmk.properties"))
                .contains("mime-type=application/x-botmaker-bot"));
        assertTrue(lines.stream().noneMatch(l -> l.equals("DESKTOP_MIMES")), "jpackage's line would be a second one");
    }

    @Test
    void everyLineOfTheLinuxMenuEntryIsAGroupAnEntryOrAComment() throws IOException {
        List<String> bad = Files.readAllLines(PACKAGING.resolve("linux/BotMaker Studio.desktop")).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#") && !l.startsWith("[") && !l.contains("="))
                .toList();
        assertEquals(List.of(), bad, "a bare placeholder line makes GLib and KDE refuse the whole entry");
    }

    @Test
    void theMacBundleDeclaresTheSchemeAndKeepsItsFileTypes() throws IOException {
        String plist = Files.readString(PACKAGING.resolve("macos/Info.plist"));
        assertTrue(plist.contains("<key>CFBundleURLTypes</key>"));
        assertTrue(plist.contains("<key>CFBundleURLSchemes</key>\n    <array>\n     <string>botmaker</string>"));
        assertTrue(plist.contains("DEPLOY_FILE_ASSOCIATIONS"), "the .bmk type stays jpackage's");
        assertTrue(plist.contains("<string>DEPLOY_BUNDLE_IDENTIFIER</string>"));
    }
}
