package com.botmaker.studio.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How an update's compile check reports javac: its error lines only, each relative to the project. */
class TreeCompileTest {

    @Test
    void onlyErrorLinesAreKeptEachRelativeToTheProject() {
        Path root = Path.of("/home/u/BotMakerProjects/Miner").toAbsolutePath();
        String output = String.join("\n",
                root.resolve("src/main/java/com/miner/Miner.java") + ":12: error: cannot find symbol",
                "        Screens.go(HOME);",
                "               ^",
                root.resolve("src/main/java/com/miner/Miner.java") + ":3: warning: [deprecation] old",
                "/elsewhere/Other.java:7: error: ';' expected",
                "2 errors");

        assertEquals(List.of("src/main/java/com/miner/Miner.java:12: cannot find symbol",
                        "/elsewhere/Other.java:7: ';' expected"),
                TreeCompile.errorLines(output, root));
    }
}
