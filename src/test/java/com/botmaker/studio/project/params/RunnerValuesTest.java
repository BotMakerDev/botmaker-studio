package com.botmaker.studio.project.params;

import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A runner's values, kept per bot and applied again to a newer tag (2026-10-25, plan Review Focus 1 and 2). */
class RunnerValuesTest {

    @TempDir
    Path dir;

    private static JavaParameter row(String className, String name, String type, String value) {
        return new JavaParameter(Path.of(className + ".java"), className,
                ParameterRow.named(name, type).value(value).build(), ValueTypes.NONE, value, true, "", "", "");
    }

    /** What the grammar would say, by the row's written type: an {@code int} reads digits, a boolean true/false. */
    private static final BiPredicate<JavaParameter, String> FITS = (row, source) -> switch (row.row().typeName()) {
        case "int" -> source.matches("-?\\d+");
        case "boolean" -> source.equals("true") || source.equals("false");
        default -> true;
    };

    @Test
    void roundTrip() {
        Path file = dir.resolve("runner-values/LiQiyeDev/Firestone.json");
        new RunnerValues(file, Map.of()).with("Settings.speed", "3").with("Settings.name", "\"Bob\"").save();
        assertEquals(Map.of("Settings.speed", "3", "Settings.name", "\"Bob\""), RunnerValues.at(file).values());
        assertEquals(Map.of(), RunnerValues.at(dir.resolve("none.json")).values());
    }

    @Test
    void aNewerTagKeepsTheValues() {
        RunnerValues kept = new RunnerValues(dir.resolve("v.json"), Map.of()).with("Settings.speed", "3")
                .with("Settings.fast", "true");
        // 1.1.0's sources hold the author's defaults again.
        List<JavaParameter> newer = List.of(row("Settings", "speed", "int", "1"),
                row("Settings", "fast", "boolean", "true"));

        RunnerValues.Applied applied = kept.applyTo(newer, Set.of("Settings.speed", "Settings.fast"), FITS);

        assertEquals(Map.of(newer.get(0), "3"), applied.values(), "the default the source already holds is left");
        assertEquals(List.of(), applied.dropped());
    }

    @Test
    void aValueThatNoLongerFitsIsDroppedOnce() {
        Path file = dir.resolve("v.json");
        RunnerValues kept = new RunnerValues(file, Map.of()).with("Settings.speed", "3")
                .with("Settings.oldSpeed", "2");
        // speed was an int and is a boolean now; oldSpeed is gone.
        List<JavaParameter> rows = List.of(row("Settings", "speed", "boolean", "false"));
        Set<String> declared = Set.of("Settings.speed");

        RunnerValues.Applied applied = kept.applyTo(rows, declared, FITS);
        assertEquals(List.of("Settings.oldSpeed", "Settings.speed"), applied.dropped());
        assertTrue(applied.values().isEmpty());

        kept.without(applied.dropped()).save();
        assertEquals(List.of(), RunnerValues.at(file).applyTo(rows, declared, FITS).dropped(), "said once");
    }

    /** A conditional setting not shown now, or one whose plugin did not load, keeps its value for later. */
    @Test
    void aDeclaredValueNotShownNowIsKept() {
        RunnerValues kept = new RunnerValues(dir.resolve("v.json"), Map.of()).with("Sdk.vm", "true");
        RunnerValues.Applied applied = kept.applyTo(List.of(), Set.of("Sdk.vm"), FITS);
        assertEquals(List.of(), applied.dropped());
        assertTrue(applied.values().isEmpty());
    }

    @Test
    void aNullOrBrokenFileReadsAsNoValues() throws Exception {
        Path file = dir.resolve("broken.json");
        Files.writeString(file, "{\"Settings.speed\": null, \"Settings.fast\": \"true\"}");
        assertEquals(Map.of("Settings.fast", "true"), RunnerValues.at(file).values());
        Files.writeString(file, "{not json");
        assertEquals(Map.of(), RunnerValues.at(file).values());
    }

    @Test
    void theKeyIsTheAuthorsRepositoryOrTheFolder() {
        assertEquals("LiQiyeDev/Firestone",
                RunnerValues.keyOf(Optional.of("LiQiyeDev"), Optional.of("Firestone"), Path.of("/p/x")));
        assertEquals("_local/My_bot", RunnerValues.keyOf(Optional.empty(), Optional.empty(), Path.of("/p/My bot")));
        assertEquals("_local/_", RunnerValues.keyOf(Optional.empty(), Optional.empty(), Path.of("/p/..")));
    }
}
