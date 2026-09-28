package com.botmaker.studio.index;

import com.botmaker.studio.palette.ApiDocs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which sources a plugin's docs are read from, and how two plugins' docs combine. */
class ApiDocsParserTest {

    private static Path sourcesJar(Path dir, Map<String, String> files) throws IOException {
        Path jar = dir.resolve("plugin-sources.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                out.putNextEntry(new ZipEntry(file.getKey()));
                out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void onlyTheCataloguedPackagesAndTheirSubpackagesAreRead(@TempDir Path tmp) throws IOException {
        Path jar = sourcesJar(tmp, Map.of(
                "com/example/api/Pad.java", """
                        package com.example.api;
                        public class Pad {
                            /**
                             * Presses a key.
                             * @param key which one
                             */
                            public static void press(int key) {}
                        }
                        """,
                "com/example/api/more/Stick.java", """
                        package com.example.api.more;
                        public class Stick { /** Tilts it. */ public static void tilt(int by) {} }
                        """,
                "com/example/internal/Pad.java", """
                        package com.example.internal;
                        public class Pad { /** Not for users. */ public static void press(int key) {} }
                        """));

        ApiDocs docs = ApiDocsParser.fromSourcesJar(jar, Set.of("com.example.api"));

        assertEquals("Presses a key.", docs.overloads("Pad", "press").getFirst().summary());
        assertEquals(1, docs.overloads("Pad", "press").size(), "the internal Pad is not read");
        assertEquals(1, docs.overloads("Stick", "tilt").size(), "a subpackage is read");
        assertTrue(ApiDocsParser.fromSourcesJar(jar, Set.of()).overloads("Pad", "press").isEmpty(),
                "no catalogued package, nothing read");
    }

    @Test
    void mergingKeepsBothPluginsAndTheFirstOnAClash() {
        ApiDocs.Overload first = new ApiDocs.Overload("first", List.of());
        ApiDocs.Overload second = new ApiDocs.Overload("second", List.of());
        ApiDocs one = new ApiDocs(Map.of("Pad", Map.of("press", List.of(first))));
        ApiDocs two = new ApiDocs(Map.of("Pad", Map.of("press", List.of(second)),
                "Stick", Map.of("tilt", List.of(second))));

        ApiDocs merged = one.mergedWith(two);

        assertEquals(List.of(first), merged.overloads("Pad", "press"));
        assertEquals(List.of(second), merged.overloads("Stick", "tilt"));
        assertEquals(one, one.mergedWith(ApiDocs.EMPTY));
    }
}
