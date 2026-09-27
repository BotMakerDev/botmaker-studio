package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Executable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Local | UTC switch on a time parameter (feedback 3): the field is retyped between {@code LocalTime} and
 * {@code OffsetTime}, and the clock reading stays — 07:30 on this computer becomes 07:30 UTC, not converted.
 * Declared here like basics declares them, since Studio's tests load no plugin ({@link TestValues}).
 */
class ClockSwitchTest {

    private static final PluginType<LocalTime> LOCAL = new Both<>(LocalTime.class, LocalTime.MIDNIGHT,
            method(LocalTime.class, "of", int.class, int.class, int.class),
            List.of(int.class, int.class, int.class),
            t -> List.of(t.getHour(), t.getMinute(), t.getSecond()),
            p -> LocalTime.of(n(p, 0), n(p, 1), n(p, 2)), List.of());

    private static final PluginType<ZoneOffset> OFFSET = new Both<>(ZoneOffset.class, ZoneOffset.UTC,
            method(ZoneOffset.class, "ofHoursMinutes", int.class, int.class), List.of(int.class, int.class),
            o -> List.of(o.getTotalSeconds() / 3600, (o.getTotalSeconds() % 3600) / 60),
            p -> ZoneOffset.ofHoursMinutes(n(p, 0), n(p, 1)), List.of(field(ZoneOffset.class, "UTC")));

    private static final PluginType<OffsetTime> AT_OFFSET = new Both<>(OffsetTime.class,
            OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC),
            method(OffsetTime.class, "of", int.class, int.class, int.class, int.class, ZoneOffset.class),
            List.of(int.class, int.class, int.class, int.class, ZoneOffset.class),
            t -> List.of(t.getHour(), t.getMinute(), t.getSecond(), t.getNano(), t.getOffset()),
            p -> OffsetTime.of(n(p, 0), n(p, 1), n(p, 2), n(p, 3), (ZoneOffset) p.get(4)), List.of());

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(LOCAL, OFFSET, AT_OFFSET),
            List.of((ComponentType<?>) LOCAL, (ComponentType<?>) OFFSET, (ComponentType<?>) AT_OFFSET));

    @Test
    void onlyATimeOfDayHasTheOtherClock() {
        assertEquals(Optional.of(OffsetTime.class), ClockSwitch.other(LocalTime.class));
        assertEquals(Optional.of(LocalTime.class), ClockSwitch.other(OffsetTime.class));
        assertTrue(ClockSwitch.other(int.class).isEmpty());
    }

    @Test
    void flippingKeepsTheClockReadingBothWays(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("refbot", root);
        Path file = config.mainPackageDir().resolve("Parameters.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package com.refbot;

                import com.botmaker.plugin.basics.params.Param;

                public final class Parameters {
                    @Param
                    public static java.time.LocalTime reset = java.time.LocalTime.of(7, 30, 0);
                }
                """);
        JavaParameter local = JavaParameters.scan(config, null, GRAMMAR).getFirst();

        ClockSwitch.flip(config, null, local, GRAMMAR).orElseThrow();

        JavaParameter utc = JavaParameters.scan(config, null, GRAMMAR).getFirst();
        assertEquals(OffsetTime.class, utc.form());
        assertEquals(Optional.of(OffsetTime.of(7, 30, 0, 0, ZoneOffset.UTC)),
                GRAMMAR.valueOf(utc.form(), utc.row().value()));
        assertTrue(Files.readString(file).contains("ZoneOffset.UTC"), Files.readString(file));

        ClockSwitch.flip(config, null, utc, GRAMMAR).orElseThrow();

        JavaParameter back = JavaParameters.scan(config, null, GRAMMAR).getFirst();
        assertEquals(LocalTime.class, back.form());
        assertEquals(Optional.of(LocalTime.of(7, 30)), GRAMMAR.valueOf(back.form(), back.row().value()));
    }

    // ---- fixtures ------------------------------------------------------------------------------------------

    /** A declared type that is its own component type, as basics' time types are. */
    private record Both<T>(Class<T> type, T fresh, Executable factory, List<Class<?>> componentTypes,
                           java.util.function.Function<T, List<Object>> parts,
                           java.util.function.Function<List<Object>, T> builder,
                           List<java.lang.reflect.Field> constants) implements PluginType<T>, ComponentType<T> {
        @Override public List<Object> components(T value) { return parts.apply(value); }
        @Override public T build(List<Object> built) { return builder.apply(built); }
    }

    private static int n(List<Object> parts, int i) {
        return ((Number) parts.get(i)).intValue();
    }

    private static java.lang.reflect.Method method(Class<?> owner, String name, Class<?>... parameters) {
        return TestValues.method(owner, name, parameters);
    }

    private static java.lang.reflect.Field field(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }
}
