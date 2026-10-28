package com.botmaker.studio.assist;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeToolsTest {

    @Test
    void openProjectTakesAnAbsoluteFolderAndOpensNothingItRefuses() {
        List<Path> opened = new ArrayList<>();
        HomeEndpoint.Shell shell = (dir, run) -> opened.add(dir);
        IllegalArgumentException relative = assertThrows(IllegalArgumentException.class,
                () -> HomeTools.openProject(shell, "BotMakerProjects/Farm", false));
        assertTrue(relative.getMessage().startsWith("Give the folder's absolute path"), relative.getMessage());
        assertThrows(IllegalArgumentException.class, () -> HomeTools.openProject(shell, null, true));
        assertEquals(List.of(), opened);
    }

    @Test
    void theHomeServesItsToolsBesideUi() {
        List<String> names = HomeTools.of((dir, run) -> { }).stream().map(t -> t.name()).toList();
        assertEquals(List.of("list_projects", "open_project", "list_bots", "create_project"), names);
    }
}
