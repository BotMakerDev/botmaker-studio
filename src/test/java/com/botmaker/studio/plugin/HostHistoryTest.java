package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.History;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.services.EditorHistory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's writes on the editor's own undo: a batch is one step under the plugin's label, a lone write one step
 * under Studio's, and ↶/↷ through the contract put the files back as they were and as they became.
 */
class HostHistoryTest {

    @AfterEach
    void noProject() {
        HostHistory.clear();
    }

    @Test
    void aBatchIsOneStepAndUndoPutsEveryFileBack(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("histbot", root);
        Path a = write(config, "A.java", "class A { int x = 1; }");
        Path b = write(config, "B.java", "class B { int y = 1; }");
        EventBus bus = new EventBus(false);
        new EditorHistory(new ProjectState(), bus);
        HostHistory.install(bus);
        History history = HostHistory.live();
        int[] heard = {0};
        history.onChanged(() -> heard[0]++);

        assertFalse(history.canUndo());
        history.batch("Move 2 cards", () -> {
            replace(config, "x = 1", "x = 2");
            replace(config, "y = 1", "y = 2");
            history.batch("an inner batch is the outer one's", () -> replace(config, "x = 2", "x = 3"));
        });
        assertTrue(history.canUndo());
        assertEquals("Move 2 cards", history.undoLabel());
        assertEquals(1, heard[0], "one step, heard once");

        WriteSteps.step("Change a plugin's value", () -> {
            replace(config, "y = 2", "y = 4");
            return null;
        }, bus::publish);
        assertEquals("Change a plugin's value", history.undoLabel(), "a lone write is a step of its own");

        history.batch("Nothing", () -> replace(config, "no such text", "anything"));
        assertEquals("Change a plugin's value", history.undoLabel(), "a batch that changed nothing is no step");

        history.undo();
        assertEquals("class B { int y = 2; }", Files.readString(b));
        assertEquals("Move 2 cards", history.undoLabel());
        assertEquals("Change a plugin's value", history.redoLabel());
        history.undo();
        assertEquals("class A { int x = 1; }", Files.readString(a), "a file written twice goes back to its first");
        assertEquals("class B { int y = 1; }", Files.readString(b));
        assertFalse(history.canUndo());
        assertEquals("", history.undoLabel());

        history.redo();
        assertEquals("class A { int x = 3; }", Files.readString(a), "and forward to its last");
        assertEquals("class B { int y = 2; }", Files.readString(b));
        assertEquals("Change a plugin's value", history.redoLabel());
    }

    @Test
    void betweenProjectsThereIsNoHistory() {
        HostHistory.install(new EventBus(false));
        HostHistory.clear();
        assertSame(History.NONE, HostHistory.live());
    }

    private static void replace(ProjectConfig config, String from, String to) {
        BotSources.forEach(config, null, (file, source) -> source.contains(from) ? source.replace(from, to) : null);
    }

    private static Path write(ProjectConfig config, String name, String source) throws IOException {
        Path file = config.sourceRoot().resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file.toAbsolutePath().normalize();
    }
}
