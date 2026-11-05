package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeToolsTest {

    /** A shell that lists {@code bots} and records what it was asked to open. */
    private record Stub(List<Listed> bots, List<Path> opened) implements HomeEndpoint.Shell {
        Stub() {
            this(List.of(), new ArrayList<>());
        }

        @Override
        public void open(Path dir, boolean run) {
            opened.add(dir);
        }

        @Override
        public String publishedBots() {
            return "";
        }

        @Override
        public String create(String name, String bot, List<String> plugins, Function<Path, String> then) {
            return then.apply(Path.of("/made", name));
        }

        @Override
        public String busy() {
            return "";
        }
    }

    @Test
    void openProjectTakesAnAbsoluteFolderAndOpensNothingItRefuses() {
        Stub shell = new Stub();
        IllegalArgumentException relative = assertThrows(IllegalArgumentException.class,
                () -> HomeTools.openProject(shell, "BotMakerProjects/Farm", false));
        assertTrue(relative.getMessage().startsWith("Give the folder's absolute path"), relative.getMessage());
        assertThrows(IllegalArgumentException.class, () -> HomeTools.openProject(shell, null, true));
        assertEquals(List.of(), shell.opened());
    }

    @Test
    void theHomeServesItsToolsBesideUi() {
        List<String> names = HomeTools.of(new Stub()).stream().map(t -> t.name()).toList();
        assertEquals(List.of("list_projects", "open_project", "list_bots", "create_project"), names);
    }

    @Test
    void listProjectsReadsTheShellsBots() {
        Stub shell = new Stub(List.of(new HomeEndpoint.Shell.Listed(Path.of("/bots/Farm"), "Farm", Optional.of("ann"),
                Optional.of("v1.2.0"), LocalDateTime.MIN, true, false, Optional.empty())), new ArrayList<>());
        assertEquals("Farm  (by ann, v1.2.0, its folder is gone)  /bots/Farm", HomeTools.listProjects(shell));
        assertEquals("My bots is empty: create_project makes one.", HomeTools.listProjects(new Stub()));
    }
}
