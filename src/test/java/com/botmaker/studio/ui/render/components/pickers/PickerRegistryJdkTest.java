package com.botmaker.studio.ui.render.components.pickers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Basics draws the JDK types (the maintainer's rule, 2026-09-27), so the host's picker registry names none: a
 * project without basics gets the by-shape fallback, never a host copy of basics' picker.
 */
class PickerRegistryJdkTest {

    @Test
    void no_registry_entry_names_a_jdk_type() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/botmaker/studio/ui/render/components/pickers/PickerRegistry.java"));
        for (String jdk : List.of("\"LocalTime\"", "\"LocalDate\"", "\"DayOfWeek\"", "\"Month\"", "\"Duration\"",
                "\"Color\"")) {
            assertFalse(source.contains("isType(" + jdk + ")"), () -> "PickerRegistry still draws " + jdk);
        }
    }
}
