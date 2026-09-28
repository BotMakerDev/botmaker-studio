package com.botmaker.studio.plugin;

import com.botmaker.plugin.api.catalog.PaletteCatalog;
import com.botmaker.plugin.api.palette.Hidden;
import com.botmaker.plugin.api.palette.Palette;
import com.botmaker.studio.types.ResolvedType;
import com.botmaker.studio.util.MethodSignature;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the menus offer on a catalogued type: the members the catalog does not hide, and the current call's
 * overload whatever the catalog says. {@code @Hidden} hides a member by name, every overload of it.
 */
class PaletteCurationTest {

    @Palette(category = "input")
    public static final class Pad {
        public static void press(int key) {}

        public static void press(long key) {}

        @Hidden("the raw form a bot never writes")
        public static void raw(String key) {}
    }

    private static final PaletteCuration CURATED = PaletteCuration.of(PaletteCatalog.of(Pad.class));

    private static MethodSignature call(String name, Class<?> param) {
        return new MethodSignature(name, List.of(ResolvedType.of(param)), List.of("key"),
                ResolvedType.of(void.class));
    }

    @Test
    void anUncuratedCatalogOffersEverything() {
        PaletteCuration none = PaletteCuration.of(PaletteCatalog.empty());
        List<MethodSignature> raw = List.of(call("raw", String.class));

        assertTrue(none.isOffered("Pad", "raw"));
        assertEquals(raw, none.retainOffered("Pad", "raw", raw, null));
        assertEquals(List.of("press", "raw"), none.retainOfferedNames("Pad", List.of("press", "raw"), null));
    }

    @Test
    void aCuratedTypeOffersOnlyWhatItsCatalogDoesNotHide() {
        List<MethodSignature> presses = List.of(call("press", int.class), call("press", long.class));

        assertTrue(CURATED.isOffered("Pad", "press"));
        assertFalse(CURATED.isOffered("Pad", "raw"));
        assertEquals(presses, CURATED.retainOffered("Pad", "press", presses, null));
        assertEquals(List.of("press"), CURATED.retainOfferedNames("Pad", List.of("press", "raw"), null));
        // A type no catalog names is not curated by it.
        assertTrue(CURATED.isOffered("SomethingElse", "raw"));
    }

    /** Filter what is offered, never what is resolved: a call already on a hidden member keeps it. */
    @Test
    void theCurrentNameIsKept() {
        assertEquals(List.of("press", "raw"),
                CURATED.retainOfferedNames("Pad", List.of("press", "raw"), "raw"));
    }

    @Test
    void anOverloadPickerIsNeverEmptiedButAMemberListMayBe() {
        List<MethodSignature> raw = List.of(call("raw", String.class));

        assertEquals(raw, CURATED.retainOffered("Pad", "raw", raw, null),
                "an empty ⚙ picker reads as a broken block");
        assertTrue(CURATED.retainOfferedNames("Pad", List.of("raw"), null).isEmpty(),
                "an empty member list drops the submenu, which is a correct answer");
    }
}
