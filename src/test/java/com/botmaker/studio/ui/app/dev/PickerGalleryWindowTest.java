package com.botmaker.studio.ui.app.dev;

import com.botmaker.plugin.api.value.ValueForm;
import com.botmaker.plugin.api.value.ValueType;
import com.botmaker.plugin.api.parameters.ParameterRow;
import com.botmaker.studio.plugin.ValueWire;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gallery's rows, without a scene: which type-and-shape pairings get a row, and what each row is seeded
 * with. The widgets themselves are what the screen is for — this only guards the seed, because a row whose
 * choices all normalise to the same string looks like a working picker and tests nothing.
 *
 * <p>The types come from {@link ValueWire#registered()} rather than a constant list, for the same reason the
 * screen enumerates them that way: the vocabulary is open, and a fixed list would stop covering the editors
 * the moment a second plugin registers one.
 */
public class PickerGalleryWindowTest {

    private static final List<String> TEMPLATES = List.of("button", "logo");

    @Test
    void everyPairingIsEitherARowOrNotASentence() {
        for (ValueType type : ValueWire.registered()) {
            for (PickerGalleryWindow.Shape shape : PickerGalleryWindow.SHAPES) {
                ParameterRow variable = PickerGalleryWindow.sample(type, shape, TEMPLATES);
                // The one pairing that is not a sentence: a declared subset of a type that already is a
                // closed set, or of one this screen has no distinguishable samples for. Everything else is
                // declarable, lists and maps of a closed set included.
                boolean legal = !shape.options()
                        || (ValueWire.fixedOptions(type).isEmpty()
                            && !PickerGalleryWindow.options(type, TEMPLATES).isEmpty());
                if (legal) {
                    assertNotNull(variable, type + " as " + shape.label() + " is declarable and needs a row");
                    assertEquals(type.id(), variable.form().leaf().id());
                    assertEquals(shape.formOf(type), variable.form());
                } else {
                    assertNull(variable, type + " as " + shape.label() + " is not a sentence anyone writes");
                }
            }
        }
    }

    /** The second axis is a form, so a map row really is one: text in front, the type being shown behind. */
    @Test
    void aMapRowIsKeyedByTextAndValuedByTheTypeOnShow() {
        ValueType text = ValueWire.type("TEXT");
        PickerGalleryWindow.Shape map = PickerGalleryWindow.SHAPES.stream()
                .filter(PickerGalleryWindow.Shape::map).findFirst().orElseThrow();

        ValueForm form = map.formOf(text);

        // By form and not by spelling: with no plugin bound the text type is an unknown one, whose own
        // source name is its id — and what this guards is the shape of the sample, not the vocabulary.
        assertEquals(ValueForm.mapOf(ValueForm.of(text), ValueForm.of(text)), form);
    }

    /**
     * The point of the hand-written table: three radio buttons all reading {@code "0,0"} would look like a
     * working picker. Every type that can carry an author-written set has to offer distinguishable values —
     * and they have to survive {@link ValueWire}'s normaliser, which is where a badly spelled sample dies.
     *
     * <p>A type nothing here has a sample for is skipped rather than failed: the table is keyed by id over an
     * open vocabulary, so a plugin's own type legitimately falls through to no samples.
     */
    @Test
    void aDeclaredSetIsAlwaysMoreThanOneDistinctValue() {
        for (ValueType type : ValueWire.registered()) {
            if (!ValueWire.fixedOptions(type).isEmpty()) continue;   // a closed set brings its own constants
            if (PickerGalleryWindow.options(type, TEMPLATES).isEmpty()) continue;
            PickerGalleryWindow.Shape oneOf = PickerGalleryWindow.SHAPES.stream()
                    .filter(shape -> shape.options() && !shape.list()).findFirst().orElseThrow();
            ParameterRow variable = PickerGalleryWindow.sample(type, oneOf, TEMPLATES);
            assertNotNull(variable);
            assertTrue(variable.options().size() >= 2,
                    type + " offers " + variable.options() + ", which is not a set to choose from");
        }
    }

    /** A closed-set type never reads the table: its choices are the enum's own constants. */
    @Test
    void aClosedSetTypeBringsItsOwnChoices() {
        com.botmaker.studio.TestSupport.assumeSdkPluginBound();
        ValueType direction = ValueWire.type("DIRECTION");
        assertEquals(List.of(), PickerGalleryWindow.options(direction, TEMPLATES));
        assertTrue(ValueWire.effectiveOptions(direction, List.of()).size() > 1);
    }

    /** Templates cannot be written down — they are whatever the open project happens to have. */
    @Test
    void templateChoicesComeFromTheProject() {
        ValueType template = ValueWire.type("IMAGE_TEMPLATE");
        assertEquals(TEMPLATES, PickerGalleryWindow.options(template, TEMPLATES));
        assertEquals(List.of(), PickerGalleryWindow.options(template, List.of()));
    }
}
