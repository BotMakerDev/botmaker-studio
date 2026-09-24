package com.botmaker.studio.plugin.grammar;

import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.project.params.TestValues;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type a plugin declares as an interface, with one component per implementation — the SDK's
 * {@code CaptureSource}, in miniature.
 *
 * <p>The grammar reads such a value as whichever declared call builds one, a part typed as the interface
 * included, and writes one back through the component of its own class. What no call reads still crosses as
 * the source it is written as.
 */
class InterfaceValuesTest {

    public interface Place {
        static Place here() { return new Here(); }
        static Place named(String title) { return new Named(title); }
        static Place inside(Place of, int margin) { return new Inside(of, margin); }
    }

    public record Here() implements Place {}

    public record Named(String title) implements Place {}

    public record Inside(Place of, int margin) implements Place {}

    static final class PlaceType implements PluginType<Place> {
        @Override public Class<Place> type() { return Place.class; }
        @Override public Place fresh() { return new Here(); }
        @Override public Node editor(ValueContext ctx) { return null; }
    }

    /** One call per implementation, each on the interface. */
    static <T extends Place> ComponentType<T> call(Class<T> type, java.lang.reflect.Method factory,
                                                   List<Class<?>> parts,
                                                   java.util.function.Function<T, List<Object>> components,
                                                   java.util.function.Function<List<Object>, T> build) {
        return new ComponentType<>() {
            @Override public Class<T> type() { return type; }
            @Override public java.lang.reflect.Executable factory() { return factory; }
            @Override public List<Class<?>> componentTypes() { return parts; }
            @Override public List<Object> components(T value) { return components.apply(value); }
            @Override public T build(List<Object> values) { return build.apply(values); }
        };
    }

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(new PlaceType()), List.of(
            call(Here.class, TestValues.method(Place.class, "here"), List.of(), h -> List.of(), p -> new Here()),
            call(Named.class, TestValues.method(Place.class, "named", String.class), List.of(String.class),
                    n -> List.of(n.title()), p -> new Named((String) p.getFirst())),
            call(Inside.class, TestValues.method(Place.class, "inside", Place.class, int.class),
                    List.of(Place.class, int.class), i -> List.of(i.of(), i.margin()),
                    p -> new Inside((Place) p.get(0), (int) p.get(1)))));

    private static final java.lang.reflect.Type PLACE = Place.class;

    private static String owner() {
        return JavaNames.canonical(Place.class);
    }

    @Test
    void anInterfaceValueIsReadAsTheCallThatBuildsIt() {
        assertEquals(new Named("Game"), GRAMMAR.valueOf(PLACE, owner() + ".named(\"Game\")").orElseThrow());
    }

    @Test
    void aPartTypedAsTheInterfaceIsReadTheSameWay() {
        String source = owner() + ".inside(" + owner() + ".named(\"Game\"), 3)";

        assertEquals(new Inside(new Named("Game"), 3), GRAMMAR.valueOf(PLACE, source).orElseThrow());
    }

    @Test
    void aValueIsWrittenThroughTheComponentOfItsOwnClass() {
        Place value = new Inside(new Named("Game"), 3);

        String written = GRAMMAR.initializer(PLACE, value).orElseThrow().source();

        assertEquals(owner() + ".inside(" + owner() + ".named(\"Game\"), 3)", written);
        assertEquals(value, GRAMMAR.valueOf(PLACE, written).orElseThrow(), "it reads back as itself");
    }

    @Test
    void whatNoCallReadsCrossesAsWritten() {
        assertEquals("myPlace()", GRAMMAR.valueOf(PLACE, "myPlace()").orElseThrow());
        // A chain is not a call the grammar writes, so it is not read as the call it starts with.
        assertEquals(owner() + ".here().margin(3)",
                GRAMMAR.valueOf(PLACE, owner() + ".here().margin(3)").orElseThrow());
    }

    @Test
    void aFreshValueIsSpelledWithItsImport() {
        JavaValue fresh = GRAMMAR.freshSpelling(PLACE).orElseThrow();

        assertTrue(fresh.source().endsWith(".here()"), fresh.source());
        assertEquals(List.of(JavaNames.importName(Place.class)), fresh.imports());
        assertEquals(owner() + ".here()", GRAMMAR.freshInitializer(PLACE).orElseThrow().source());
    }
}
