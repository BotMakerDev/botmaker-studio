package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the file-role rules — the single source of "may the user change this?".
 *
 * <p><b>One rule, about bundled code, and nothing about the project.</b> {@code GENERATED} covered
 * everything BotMaker once wrote into a user's tree — the game-bot entry point, {@code Activities},
 * {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates} — and went on 2026-08-29 because nothing
 * generated a project's Java any more. It came back for one day on 2026-09-20, for a design where the host
 * wrote a plugin's model whole, and went again with it: the plugin ships the file and the host rewrites one
 * expression inside it ({@code docs/refactor/33-plugin-java.md}). The assertions below say so — a file in a
 * plugin's own package is the user's, because that is exactly what a plugin handed them.
 *
 * <p>The other read-only case — a bot installed from the gallery and opened for reading — is a property of
 * the checkout rather than of a file, and lives in {@link ProjectMode} and {@link LockResolver}.
 */
class FileRoleTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    @Test
    void librarySourceIsLibrary() {
        Path lib = Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");
        assertEquals(FileRole.LIBRARY, FileRole.of(lib));
    }

    /** The files BotMaker used to write and keep rewriting are ordinary user files, and stayed that way. */
    @Test
    void whatTheOldGeneratedRoleCoveredIsStillTheUsers() {
        for (Path file : List.of(CONFIG.mainSourceFile(),
                inMainPackage("Activities.java"),
                inMainPackage("ActivityRegistry.java"),
                inMainPackage("FlowDriver.java"),
                inMainPackage("Templates.java"),
                inMainPackage("GoHome.java"),
                inMainPackage("Popups.java"))) {
            assertEquals(FileRole.EDITABLE, FileRole.of(file), file.toString());
        }
    }

    @Test
    void unknownFilesBelongToTheUser() {
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("MyHelper.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(null));
    }

    @Test
    void libraryIsFullyInert() {
        FileRole library = FileRole.LIBRARY;
        assertTrue(library.isReadOnly());
        assertTrue(library.suppressesInteraction(), "library blocks must not offer interaction");
        assertEquals("Library - Read Only", library.badge());
        assertNotNull(library.reason());
    }

    /**
     * The file a plugin hands a bot is the user's. Locking it would fight the reason for handing it over —
     * that a developer with no BotMaker installed can read it, edit it and hand it to a compiler. What is
     * refused is one {@code @Managed} method's body, which is {@link LockResolver}'s and not a file's.
     */
    @Test
    void aPluginsOwnPackageBelongsToTheUser() {
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/sdk/Sdk.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/Helpers.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/helpers/deep/Util.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/sdk/notes.txt")));
    }

    /** Two roles, and a third would have to earn its way back in. */
    @Test
    void thereIsExactlyOneReadOnlyRole() {
        assertEquals(List.of(FileRole.EDITABLE, FileRole.LIBRARY), List.of(FileRole.values()));
    }

    @Test
    void editableIsUnrestricted() {
        FileRole editable = FileRole.EDITABLE;
        assertFalse(editable.isReadOnly());
        assertFalse(editable.suppressesInteraction());
        assertNull(editable.badge());
        assertNull(editable.reason());
    }
}
