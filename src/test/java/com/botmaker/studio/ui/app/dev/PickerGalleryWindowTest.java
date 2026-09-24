package com.botmaker.studio.ui.app.dev;

import com.botmaker.studio.plugin.PluginHost;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's rows, without a scene: which type-and-shape pairings get a row, and what each row is seeded
 * with. The widgets themselves are what the screen is for — this only guards the seed, because a row whose
 * choices are all one value looks like a working picker and tests nothing.
 *
 * <p>The types come from the bound plugins' declarations rather than a constant list, for the same reason the
 * screen enumerates them that way: the vocabulary is open, and a fixed list would stop covering the editors
 * the moment a second plugin declares one.
 */
public class PickerGalleryWindowTest {

    @Test
    void everyPairingIsEitherARowOrNotASentence() {
        for (String type : PickerGalleryWindow.declaredTypes()) {
            for (PickerGalleryWindow.Shape shape : PickerGalleryWindow.SHAPES) {
                PickerGalleryWindow.Sample variable = PickerGalleryWindow.sample(type, shape);
                boolean legal = !shape.options() || !PickerGalleryWindow.options(type).isEmpty();
                if (legal) {
                    assertNotNull(variable, type + " as " + shape.label() + " is declarable and needs a row");
                    assertEquals(shape.formOf(type), variable.form());
                } else {
                    assertNull(variable, type + " as " + shape.label() + " has no choices to fill in");
                }
            }
        }
    }

    /** The second axis is a form, so a map row really is one: text in front, the type being shown behind. */
    @Test
    void aMapRowIsKeyedByTextAndValuedByTheTypeOnShow() {
        PickerGalleryWindow.Shape map = PickerGalleryWindow.SHAPES.stream()
                .filter(PickerGalleryWindow.Shape::map).findFirst().orElseThrow();

        assertEquals(ValueTypes.mapOf(String.class, PluginHost.grammar().named("java.time.Duration")
                        .<java.lang.reflect.Type>map(cls -> cls).orElse(new ValueTypes.Unknown("java.time.Duration"))),
                map.formOf("java.time.Duration"));
    }

    /**
     * The point of the hand-written table: three radio buttons all reading {@code 0} would look like a
     * working picker. Every sample set has to be distinct values <em>of its type</em> — read by the grammar,
     * which is where a badly spelled sample dies.
     */
    @Test
    void aDeclaredSetIsAlwaysMoreThanOneDistinctValueOfItsType() {
        for (String type : List.of("java.lang.String", "int", "double", "char")) {
            List<String> options = PickerGalleryWindow.options(type);
            assertTrue(options.size() >= 2, type + " offers " + options + ", which is not a set to choose from");
            LinkedHashSet<Object> values = new LinkedHashSet<>();
            for (String option : options) {
                Optional<Object> value = PluginHost.grammar().valueOf(PluginHost.grammar().named(type).orElseThrow(), option);
                values.add(value.orElse(option));
            }
            assertEquals(options.size(), values.size(), type + " has two samples that are one value");
        }
    }

    /** A type this screen cannot spell without knowing a plugin's vocabulary gets no set to choose from. */
    @Test
    void aPluginsTypeHasNoHandWrittenSamples() {
        assertEquals(List.of(), PickerGalleryWindow.options("com.botmaker.sdk.api.geometry.Point"));
    }
}
