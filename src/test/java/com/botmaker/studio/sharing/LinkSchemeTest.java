package com.botmaker.studio.sharing;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code botmaker://} scheme on Windows, and on the other systems the installers' own files (2026-10-25): what
 * each registers is read off the file as it ships.
 */
class LinkSchemeTest {

    private static final String LAUNCHER = "C:\\Program Files\\BotMaker Studio\\BotMaker Studio.exe";

    @Test
    void windowsRunsTheLauncherWithTheLink() {
        assertEquals("\"" + LAUNCHER + "\" \"%1\"", LinkScheme.command(LAUNCHER));
        assertEquals(LinkScheme.command(LAUNCHER), LinkScheme.values(LAUNCHER).get("BMK_COMMAND"));
        assertEquals(LAUNCHER + ",0", LinkScheme.values(LAUNCHER).get("BMK_ICON"));
    }

    @Test
    void theScriptWritesTheUsersOwnClassesAndSplicesNoValue() {
        String script = LinkScheme.script();
        assertTrue(script.contains("'HKCU:\\Software\\Classes\\botmaker'"));
        assertTrue(script.contains("'URL Protocol'"), "what makes it a scheme");
        assertTrue(script.contains("$env:BMK_COMMAND") && script.contains("$env:BMK_ICON"));
        assertFalse(script.contains("Program Files"));
        assertFalse(script.contains("\""), "no double quote for Windows' command line to lose");
        assertEquals(script, new String(Base64.getDecoder().decode(LinkScheme.encoded()), StandardCharsets.UTF_16LE));
    }
}
