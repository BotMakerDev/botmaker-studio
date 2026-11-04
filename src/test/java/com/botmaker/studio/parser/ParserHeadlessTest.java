package com.botmaker.studio.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The parser reads and edits Java without drawing anything, so it can leave Studio: only the two classes that
 * turn a tree into blocks may name the view.
 */
class ParserHeadlessTest {

    private static final Path PARSER = Path.of("src/main/java/com/botmaker/studio/parser");
    private static final Set<String> VIEW_BUILDERS = Set.of("BlockConverter.java", "BlockReuse.java");
    private static final Pattern VIEW_IMPORT = Pattern.compile(
            "^import\\s+(static\\s+)?(javafx\\.|com\\.botmaker\\.studio\\.(blocks|core|ui)\\.).*", Pattern.MULTILINE);

    @Test
    void onlyTheViewBuildersImportTheView() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(PARSER)) {
            offenders = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> !VIEW_BUILDERS.contains(file.getFileName().toString()))
                    .filter(ParserHeadlessTest::importsTheView)
                    .map(Path::toString)
                    .toList();
        }
        assertEquals(List.of(), offenders);
    }

    private static boolean importsTheView(Path file) {
        try {
            return VIEW_IMPORT.matcher(Files.readString(file)).find();
        } catch (IOException e) {
            throw new IllegalStateException(file.toString(), e);
        }
    }
}
