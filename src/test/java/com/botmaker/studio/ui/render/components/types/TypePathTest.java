package com.botmaker.studio.ui.render.components.types;

import com.botmaker.studio.plugin.grammar.ValueContainer;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.lang.reflect.Type;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Which part of a type the chooser changes (feedback 3): a map's key as well as its value, any level of a
 * nested list — where a pick used to reach only the innermost last argument.
 */
class TypePathTest {

    private static final Type POINTS_BY_NAME = ValueTypes.mapOf(String.class, ValueTypes.listOf(Point.class));

    @Test
    void theDefaultPartIsTheInnermostLastArgument() {
        TypePath path = TypePath.innermost(POINTS_BY_NAME);
        assertEquals(List.of(1, 0), path.steps());
        assertEquals(Point.class, path.at(POINTS_BY_NAME));
        assertEquals(List.of(), TypePath.innermost(int.class).steps());
    }

    @Test
    void aMapsKeyAndItsValueAreEachTheirOwnPart() {
        TypePath key = TypePath.ROOT.child(0);
        assertEquals(ValueTypes.mapOf(int.class, ValueTypes.listOf(Point.class)),
                key.replace(POINTS_BY_NAME, int.class));
        TypePath value = TypePath.ROOT.child(1);
        assertEquals(ValueTypes.mapOf(String.class, Integer.class), value.replace(POINTS_BY_NAME, Integer.class));
    }

    @Test
    void theWholeTypeIsAPartToo() {
        assertEquals(int.class, TypePath.ROOT.replace(POINTS_BY_NAME, int.class));
        // void holds nothing, so picking it anywhere is picking it for the whole type.
        assertEquals(void.class, TypePath.innermost(POINTS_BY_NAME).replace(POINTS_BY_NAME, void.class));
    }

    @Test
    void wrapAndUnwrapActOnThePartAlone() {
        TypePath key = TypePath.ROOT.child(0);
        assertEquals(ValueTypes.mapOf(ValueTypes.listOf(String.class), ValueTypes.listOf(Point.class)),
                key.wrap(POINTS_BY_NAME, ValueContainer.LIST));
        TypePath list = TypePath.ROOT.child(1);
        assertEquals(ValueTypes.mapOf(String.class, Point.class), list.unwrap(POINTS_BY_NAME));
        // Unwrapping the whole map keeps what it holds.
        assertEquals(ValueTypes.listOf(Point.class), TypePath.ROOT.unwrap(POINTS_BY_NAME));
        // A leaf has nothing to unwrap.
        assertEquals(POINTS_BY_NAME, key.unwrap(POINTS_BY_NAME));
    }

    /** Unwrap from a leaf unwraps the container around it — what Unwrap did from the default part before. */
    @Test
    void theContainerOfALeafIsTheOneAroundIt() {
        assertEquals(List.of(1), TypePath.innermost(POINTS_BY_NAME).container(POINTS_BY_NAME).steps());
        assertEquals(List.of(1), TypePath.ROOT.child(1).container(POINTS_BY_NAME).steps());
        assertNull(TypePath.ROOT.container(int.class));
        assertEquals(List.of(1, 0), TypePath.ROOT.child(1).then(TypePath.ROOT.child(0)).steps());
    }

    @Test
    void aPathAnUnwrapLeftBehindIsCutBackToWhatStillExists() {
        Type unwrapped = TypePath.ROOT.child(1).unwrap(POINTS_BY_NAME);
        TypePath stale = TypePath.innermost(POINTS_BY_NAME);
        assertNull(stale.at(unwrapped));
        assertEquals(List.of(1), stale.within(unwrapped).steps());
        assertEquals(List.of(), stale.within(Point.class).steps());
    }

    @Test
    void theSegmentsSpellTheTypeAndNameTheirParts() {
        List<TypePath.Segment> segments = TypePath.segments(POINTS_BY_NAME);
        assertEquals("Map<String, List<Point>>",
                String.join("", segments.stream().map(TypePath.Segment::text).toList()));
        TypePath.Segment key = segments.stream().filter(s -> s.text().equals("String")).findFirst().orElseThrow();
        assertEquals(List.of(0), key.path().steps());
        TypePath.Segment list = segments.stream().filter(s -> s.text().equals("List")).findFirst().orElseThrow();
        assertEquals(List.of(1), list.path().steps());
        TypePath.Segment open = segments.stream().filter(s -> s.text().equals("<")).findFirst().orElseThrow();
        assertNull(open.path());
    }
}
