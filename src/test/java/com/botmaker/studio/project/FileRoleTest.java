package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the file-role rules — the single source of "may the user change this?".
 *
 * <p>Between 2026-08-29 and 2026-09-20 there were two rules, because nothing generated a project's Java and
 * a lock over files nobody rewrites protects nothing. A plugin's model is compiled code again
 * ({@code docs/refactor/33-plugin-java.md}), so {@code GENERATED} is back — and the assertions below say
 * exactly how much of it came back. The old constant covered everything BotMaker wrote into a user's tree:
 * the game-bot entry point, {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver},
 * {@code Templates}. Those are the user's and stay the user's. What is locked is one thing: a file in a
 * plugin's own package, which the host rewrites whole every time that model is saved.
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
     * A plugin's model, and only that. One package below {@code plugins} is the host's; anything else a
     * user chose to put under a package of that name is theirs, which is what keeps the rule narrow enough
     * to be safe.
     */
    @Test
    void aPluginsOwnPackageIsGenerated() {
        Path model = inMainPackage("plugins/sdk/Flow.java");
        assertEquals(FileRole.GENERATED, FileRole.of(model));

        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/Helpers.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/helpers/deep/Util.java")));
        assertEquals(FileRole.EDITABLE, FileRole.of(inMainPackage("plugins/sdk/notes.txt")));
    }

    @Test
    void aGeneratedFileIsLockedAndSaysWhy() {
        FileRole generated = FileRole.GENERATED;
        assertTrue(generated.isReadOnly());
        assertTrue(generated.suppressesInteraction());
        assertEquals("Generated - Read Only", generated.badge());
        assertNotNull(generated.reason());
        assertTrue(generated.reason().contains("overwritten"), generated.reason());
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
