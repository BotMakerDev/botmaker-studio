package com.botmaker.studio.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which plugin jars the docs are looked for at all. */
class ApiDocsServiceTest {

    private static Path jar(Path dir, String... entries) throws IOException {
        Path jar = dir.resolve("plugin.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void aJarWithNoCataloguedClassIsNotDocumented(@TempDir Path tmp) throws IOException {
        Path basics = jar(tmp, "com/example/basics/BasicsTypes.class", "META-INF/maven/x/y/pom.properties");

        assertFalse(ApiDocsService.holdsAny(basics, Set.of("com.example.api")));
        assertFalse(ApiDocsService.holdsAny(basics, Set.of()), "no catalogued package, nothing to document");
    }

    @Test
    void aJarWithACataloguedClassOrSubpackageIsDocumented(@TempDir Path tmp) throws IOException {
        Path sdk = jar(tmp, "com/example/api/more/Stick.class");

        assertTrue(ApiDocsService.holdsAny(sdk, Set.of("com.example.api")));
        assertFalse(ApiDocsService.holdsAny(sdk, Set.of("com.example.ap")), "a package, not a name prefix");
    }
}
