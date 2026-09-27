package com.botmaker.studio.ui.render.theme;

import com.botmaker.plugin.api.StyleClasses;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract's {@link StyleClasses} names what this stylesheet defines.
 *
 * <p>A plugin's widgets wear these classes and nothing else of the host's look, so a class renamed here and
 * not there would leave every plugin editor unstyled with no error anywhere. This test is where that rename
 * fails instead.
 */
class StyleClassesTest {

    @Test
    void everyContractClassIsAStyleThisStylesheetDefines() throws IOException {
        String css = stylesheet();
        List<String> missing = new ArrayList<>();
        for (String name : names()) {
            if (name.equals(StyleClasses.UNTHEMED)) continue;
            Pattern selector = Pattern.compile("\\." + Pattern.quote(name) + "(?![\\w-])");
            if (!selector.matcher(css).find()) missing.add(name);
        }
        assertEquals(List.of(), missing, "contract style classes blocks.css does not define");
    }

    /** The one marker with no rule of its own: the host reads it when theming a window. */
    @Test
    void theOptOutIsTheOneThemedWindowsReads() {
        assertEquals(StyleClasses.UNTHEMED, ThemedWindows.UNTHEMED);
    }

    @Test
    void theContractDeclaresNamesAndNothingElse() {
        List<String> names = names();
        assertFalse(names.isEmpty());
        assertEquals(names.size(), names.stream().distinct().count(), "two constants name one class");
        assertTrue(names.stream().allMatch(n -> n.matches("[a-z][a-z0-9-]*")), names::toString);
    }

    private static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Field field : StyleClasses.class.getFields()) {
            if (field.getType() != String.class || !Modifier.isStatic(field.getModifiers())) continue;
            try {
                out.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    private static String stylesheet() throws IOException {
        try (InputStream in = StyleClassesTest.class.getResourceAsStream("/css/blocks.css")) {
            assertNotNull(in, "blocks.css is on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
