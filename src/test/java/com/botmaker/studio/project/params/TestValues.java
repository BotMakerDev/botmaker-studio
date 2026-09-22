package com.botmaker.studio.project.params;

import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.studio.plugin.grammar.ValueForm;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import javafx.scene.Node;

import java.time.Duration;
import java.util.List;

/**
 * A grammar for these tests — <b>not {@code botmaker-plugin-basics}'</b>, on purpose.
 *
 * <p>Studio depends on no plugin, and a test dependency on one would be the first crack in that: the build
 * would start needing a plugin to compile its own tests, and the next person would reach for a plugin type
 * in main code because it was already on the classpath. What is being tested here is the <em>host's</em>
 * reading of Java, so the types it reads only have to behave like real ones.
 *
 * <p>They are declared like plugin-basics' three most awkward cases all the same — a call
 * ({@code Duration.ofMillis}), a quoted literal ({@code String}) and a bare one ({@code int}) — because those
 * are the three shapes the scanner has to tell apart. {@link Body} is the fourth, for {@code project/managed}:
 * a type a plugin declares and cannot take apart, whose value crosses as the source it is written as — the
 * way the SDK names an activity's body, {@code Collect::body}.
 *
 * <p>Since 2026-09-22 these are {@link PluginType}s and {@link ComponentType}s — one declaration each — where
 * they were a {@code ValueType} and a {@code ValueCodec} with four string methods apiece.
 */
public final class TestValues {

    /** A value named by {@code Owner::method}: declared, never taken apart. */
    public record Body(String source) {}

    /**
     * A fixed shape: a record with named components and no type arguments, written {@code Span.of(…)}.
     *
     * <p>The SDK's {@code Flow} is one of these, and it is the case that broke until 2026-09-20: a record
     * with fixed components could only be registered as an opaque leaf — which is a string, which is what
     * this whole design exists to leave behind.
     */
    public record Span(String label, int length) {

        public static Span of(String label, int length) {
            return new Span(label, length);
        }
    }

    public static final PluginType<String> TEXT_TYPE = new Leaf<>(String.class, "");
    public static final PluginType<Integer> WHOLE_NUMBER_TYPE = new Leaf<>(int.class, 0);

    /** {@code java.time.Duration.ofMillis(3000L)} — a call, taken apart into one {@code long}. */
    public static final DurationType DURATION_TYPE = new DurationType();

    /** Declared with a starting expression and nothing to read it with. */
    public static final PluginType<Body> BODY_TYPE = new PluginType<>() {
        @Override public Class<Body> type() { return Body.class; }
        @Override public Body fresh() { return null; }
        @Override public String freshSource() { return "com.example.bot.Rest::body"; }
        @Override public Node editor(ValueContext ctx) { return null; }
    };

    /** {@code Span.of(label, length)}, a component type nothing declares on its own. */
    public static final ComponentType<Span> SPAN = new ComponentType<>() {
        @Override public Class<Span> type() { return Span.class; }
        @Override public String factory() { return "of"; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class, int.class); }
        @Override public List<Object> components(Span value) { return List.of(value.label(), value.length()); }
        @Override public Span build(List<Object> parts) {
            return parts.size() != 2 ? null : new Span((String) parts.get(0), ((Number) parts.get(1)).intValue());
        }
    };

    /** The same shape as a real bind: a few declared types, one component type beside them. */
    public static final ValueGrammar GRAMMAR = ValueGrammar.of(
            List.of(TEXT_TYPE, WHOLE_NUMBER_TYPE, DURATION_TYPE, BODY_TYPE), List.of(SPAN));

    public static final ValueForm TEXT = ValueForm.of(String.class);
    public static final ValueForm WHOLE_NUMBER = ValueForm.of(int.class);
    public static final ValueForm DURATION = ValueForm.of(Duration.class);
    public static final ValueForm BODY = ValueForm.of(Body.class);
    public static final ValueForm SPAN_FORM = ValueForm.of(Span.class);

    /** A JDK literal type: nothing to take apart, and no editor either — these tests draw nothing. */
    private record Leaf<T>(Class<T> type, T fresh) implements PluginType<T> {
        @Override public Node editor(ValueContext ctx) { return null; }
    }

    /** {@code Duration.ofMillis(long)}. */
    public static final class DurationType implements PluginType<Duration>, ComponentType<Duration> {
        @Override public Class<Duration> type() { return Duration.class; }
        @Override public Duration fresh() { return Duration.ZERO; }
        @Override public Node editor(ValueContext ctx) { return null; }
        @Override public String factory() { return "ofMillis"; }
        @Override public List<Class<?>> componentTypes() { return List.of(long.class); }
        @Override public List<Object> components(Duration value) { return List.of(value.toMillis()); }
        @Override public Duration build(List<Object> parts) {
            return parts.size() == 1 && parts.getFirst() instanceof Number n ? Duration.ofMillis(n.longValue()) : null;
        }
    }

    private TestValues() {}
}
