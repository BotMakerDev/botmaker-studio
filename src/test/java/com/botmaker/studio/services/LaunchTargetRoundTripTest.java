package com.botmaker.studio.services;

import com.botmaker.shared.launch.LaunchKind;
import com.botmaker.shared.launch.LaunchSpec;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.StudioProjectSettings;
import com.botmaker.studio.runtime.BotJvm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Studio services MISSING 6 — the launch target survives a write / read / parse / describe round trip.</b>
 * Gates <b>SV10</b> (parse {@code launch.target} through {@link LaunchKind} instead of re-splitting the string)
 * and, through it, SU6.
 *
 * <p>The spec is the one value that crosses every boundary this project has: the SDK plugin writes it as this
 * checkout's run property ({@code .botmaker/settings.json} since 2026-09-27), the file survives on disk between
 * sessions, every run starts with it as {@code -Dbotmaker.launch.target}, and the plugin reads it back. Nothing tested any leg of that. The property that has
 * to hold is not "parse works" but <b>write(read(x)) == x for every kind</b> — because a spec that survives
 * three legs and is mangled on the fourth is a bot that launches the wrong thing, or nothing, with no error.
 *
 * <p>The unknown-kind case is the one worth stating out loud: a spec written by a newer Studio must still
 * round-trip through an older one unchanged, which is why {@link LaunchSpec#parse} keeps the original text for
 * {@link LaunchKind#UNKNOWN} rather than dropping it.
 */
class LaunchTargetRoundTripTest {

    /** One realistic token per kind — every value in the closed set, so a new kind fails here until listed. */
    private static List<String> everyKindsSpec() {
        return List.of(
                "steam:570",
                "epic:Fortnite",
                "heroic:Corvette",
                "faugus:3f2a11",
                "cli:/usr/bin/wine game.exe --windowed",
                "exe:/opt/game/game.x86_64",
                "emu-app:com.example.game@MuMuPlayer-12.0-1");
    }

    /** The run property the SDK plugin sets and the SDK's {@code Target} reads. */
    private static final String KEY = "botmaker.launch.target";

    /**
     * Writes {@code spec} as this checkout's run property into a fresh {@code .botmaker} dir and reads it straight
     * back — the legs since 2026-09-27, when it left {@code botmaker-project.properties}.
     */
    private static String writeThenRead(Path studioDir, String spec) throws IOException {
        StudioProjectSettings.read(studioDir).withRunProperty(KEY, spec).write(studioDir);
        return StudioProjectSettings.read(studioDir).runProperties().get(KEY);
    }

    // ---- The round trip ----

    @Test
    void everyKindSurvivesWriteReadParseAndReWrite(@TempDir Path dir) {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (String spec : everyKindsSpec()) {
            checks.add(() -> {
                Path resources = dir.resolve(spec.substring(0, spec.indexOf(':')));
                String read = writeThenRead(resources, spec);
                assertEquals(spec, read, "the file did not give back what was written");

                LaunchSpec parsed = LaunchSpec.parse(read);
                assertNotNull(parsed, spec + " did not parse");
                assertEquals(spec, parsed.spec(),
                        "re-encoding a parsed spec must reproduce it exactly, or the next save corrupts it");
            });
        }
        assertAll(checks);
    }

    /** Every kind but {@code UNKNOWN} must be reachable from a written spec — the id is the wire format. */
    @Test
    void everyKindIsReachableFromItsWrittenId() {
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (LaunchKind kind : LaunchKind.values()) {
            if (kind == LaunchKind.UNKNOWN) continue;
            checks.add(() -> {
                LaunchSpec parsed = LaunchSpec.parse(kind.id() + ":token");
                assertNotNull(parsed, kind + " did not parse");
                assertEquals(kind, parsed.kind(),
                        kind + "'s persisted id no longer parses back to it");
            });
        }
        assertAll(checks);
    }

    // ---- Describing it ----

    /**
     * The label the dialogs and the run checklist print. It is asserted per kind rather than as "not blank"
     * because the failure this replaces was a kind that launched fine and described as nothing.
     */
    @Test
    void eachKindDescribesItself() {
        assertAll(
                () -> assertEquals("Steam game 570", LaunchSpec.describe("steam:570")),
                () -> assertEquals("Epic game Fortnite", LaunchSpec.describe("epic:Fortnite")),
                // exe: describes by file name, not by the whole path — a path is unreadable in a button.
                () -> assertEquals("Executable game.x86_64", LaunchSpec.describe("exe:/opt/game/game.x86_64")),
                () -> assertEquals("(none)", LaunchSpec.describe(null)),
                () -> assertEquals("(none)", LaunchSpec.describe("   ")));
    }

    // ---- Clearing it ----

    /**
     * A null spec removes the property rather than writing an empty one, and the rest of the settings survive —
     * the file is the editor's whole state for this checkout, so "clear the target" must not clear anything
     * else in it.
     */
    @Test
    void clearingTheTargetRemovesThePropertyAndLeavesTheSettingsAlone(@TempDir Path dir) throws IOException {
        StudioProjectSettings.empty().withTemplate(ProjectTemplate.GAME_BOT).write(dir);
        assertEquals("steam:570", writeThenRead(dir, "steam:570"));

        assertNull(writeThenRead(dir, null), "the property must be gone, not blank");

        assertEquals(ProjectTemplate.GAME_BOT, StudioProjectSettings.read(dir).template());
        assertFalse(Files.readString(dir.resolve(StudioProjectSettings.FILE_NAME)).contains(KEY),
                "an empty property left behind reads as a configured-but-blank target");
    }

    /** No file yet is "no target configured", not a crash — {@code QuickLaunch} asks before anything exists. */
    @Test
    void readingBeforeAnythingIsWrittenIsNotAnError(@TempDir Path dir) {
        assertNull(StudioProjectSettings.read(dir.resolve("never-created")).runProperties().get(KEY));
    }

    /**
     * The last leg: every run of the bot starts with it as a system property, one argument, whole — a
     * {@code cli:} spec has spaces in it.
     */
    @Test
    void theRunStartsWithItAsASystemProperty() {
        String spec = "cli:/usr/bin/wine game.exe --windowed";
        List<String> options = BotJvm.options(StudioProjectSettings.empty().withRunProperty(KEY, spec));
        assertTrue(options.contains("-D" + KEY + "=" + spec), options.toString());
        assertTrue(options.containsAll(BotJvm.OPTIONS), "the JVM's own options still come first");
    }

    // ---- The unreadable spec ----

    /**
     * A hand-edited or newer-Studio spec must survive untouched. If parsing dropped it, opening the project in
     * an older Studio and saving anything would silently delete a working launch target.
     */
    @Test
    void anUnknownKindRoundTripsRatherThanBeingDropped(@TempDir Path dir) throws IOException {
        String fromTheFuture = "flatpak:org.example.Game";

        String read = writeThenRead(dir, fromTheFuture);
        LaunchSpec parsed = LaunchSpec.parse(read);

        assertNotNull(parsed, "an unknown kind must still parse — dropping it deletes the user's setting");
        assertEquals(LaunchKind.UNKNOWN, parsed.kind());
        assertEquals(fromTheFuture, parsed.spec(), "re-writing it must not rewrite it");
        assertEquals(fromTheFuture, parsed.describe(), "and it must still be printable");
    }

    /** Genuinely unparseable text yields null rather than throwing: the file is user-editable. */
    @Test
    void nothingToParseIsNullAndNeverAnException() {
        assertAll(
                () -> assertNull(LaunchSpec.parse(null)),
                () -> assertNull(LaunchSpec.parse("")),
                () -> assertNull(LaunchSpec.parse("no-colon-at-all")),
                () -> assertNull(LaunchSpec.parse("steam:"), "an empty token is not a target"),
                () -> assertNull(LaunchSpec.parse(":570"), "a missing kind is not a target"));
    }

    // ---- What the launcher actually looks for ----

    /**
     * {@code runningToken()} is what the skip-if-running probe matches against a live process command line.
     * It is deliberately <em>not</em> {@link LaunchSpec#spec()}, and the Steam case says why: the bare appId
     * is a short number that matches an unrelated command line by accident.
     */
    @Test
    void theRunningTokenIsTheLaunchersIdentityNotOurs() {
        assertAll(
                () -> assertEquals("AppId=570", LaunchSpec.parse("steam:570").runningToken()),
                () -> assertEquals("Fortnite", LaunchSpec.parse("epic:Fortnite").runningToken()),
                () -> assertEquals("game.x86_64", LaunchSpec.parse("exe:/opt/game/game.x86_64").runningToken()),
                () -> assertEquals("wine", LaunchSpec.parse("cli:/usr/bin/wine game.exe").runningToken(),
                        "a command line is identified by its executable, not its arguments"),
                () -> assertNull(LaunchSpec.parse("emu-app:com.example.game@Inst").runningToken(),
                        "an app inside an emulator is on no host process table"));
    }

    // theCaptureSourceSurvivesAWriteReadRoundTrip stood here until 2026-09-27, over the capture.source key of
    // botmaker-project.properties. The capture source is the expression Sdk.captureSource() returns, and that
    // file is read by nothing.

    /** The {@code @} split keeps package dots and takes the <em>last</em> separator. */
    @Test
    void anEmulatorAppSplitsIntoPackageAndInstance() {
        LaunchSpec spec = LaunchSpec.parse("emu-app:com.example.game@MuMuPlayer-12.0-1");

        assertEquals("com.example.game", spec.emulatorPackage());
        assertEquals("MuMuPlayer-12.0-1", spec.emulatorInstance());
        assertTrue(LaunchSpec.parse("steam:570").emulatorPackage() == null,
                "only emu-app has these halves");
    }
}
