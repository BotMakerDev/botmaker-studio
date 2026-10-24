package com.botmaker.studio.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Studio's javac builds the classes the pom asks for, whatever JDK Studio itself runs on. */
class JavacReleaseTest {

    @Test
    void thePomsReleaseIsPassedAndNothingElse() {
        assertEquals(List.of("--release", "25"), JavacRelease.args(Map.of("maven.compiler.release", " 25 ")));
        assertEquals(List.of(), JavacRelease.args(Map.of()));
        assertEquals(List.of(), JavacRelease.args(Map.of("maven.compiler.release", "${java.version}")));
        // A cloned bot's pom is not trusted: a value that is not a release must never reach javac as an option.
        assertEquals(List.of(), JavacRelease.args(Map.of("maven.compiler.release", "-Xplugin:Evil")));
    }

    @Test
    void aClassCompiledThroughItHasThePomsVersion(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.miner</groupId><artifactId>miner</artifactId><version>1.0.0</version>
                  <properties><maven.compiler.release>25</maven.compiler.release></properties>
                </project>
                """);
        Path source = Files.writeString(project.resolve("Miner.java"), "class Miner {}");
        String javac = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
        List<String> command = new ArrayList<>(List.of(javac, "-d", project.toString()));
        command.addAll(JavacRelease.args(project));
        command.add(source.toString());

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
        assertEquals(69, majorVersion(project.resolve("Miner.class")), "Java 25 is class file version 69");
    }

    private static int majorVersion(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile); DataInputStream data = new DataInputStream(in)) {
            data.readInt();
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }
}
