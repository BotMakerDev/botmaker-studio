package com.botmaker.studio.plugin.record;

import com.botmaker.plugin.api.StudioPlugin;
import com.botmaker.plugin.api.StudioServices;
import com.botmaker.plugin.api.record.Gesture;
import com.botmaker.plugin.api.record.RecordedValue;
import com.botmaker.plugin.api.slot.ValueContext;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.plugin.api.value.PluginType;
import com.botmaker.plugin.host.Recordings;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.ManagedConstants;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Recognised gestures written as a plugin's calls: the ranked writer that fills wins, values are spelled by the
 * grammar, and a value equal to a {@code @Managed} constant is written as the constant.
 */
class RecordingWriterTest {

    private static final String PAD = "com.botmaker.studio.plugin.record.Pad";

    /** A picture at x below 50, nothing elsewhere. */
    private static final RecordedValue<Thing> THING_AT = new RecordedValue<>() {
        @Override public Class<Thing> type() { return Thing.class; }
        @Override public Optional<Thing> at(StudioServices services, Spot spot) {
            return spot.x() < 50 ? Optional.of(new Thing("a.png")) : Optional.empty();
        }
    };

    private static final ComponentType<Thing> THING_TYPE = new ComponentType<>() {
        @Override public Class<Thing> type() { return Thing.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(Thing t) { return List.of(t.path()); }
        @Override public Thing build(List<Object> parts) { return new Thing((String) parts.getFirst()); }
    };

    private static final ComponentType<Duration> DURATION_TYPE = new ComponentType<>() {
        @Override public Class<Duration> type() { return Duration.class; }
        @Override public java.lang.reflect.Executable factory() {
            return com.botmaker.studio.project.params.TestValues.method(Duration.class, "ofMillis", long.class);
        }
        @Override public List<Class<?>> componentTypes() { return List.of(long.class); }
        @Override public List<Object> components(Duration d) { return List.of(d.toMillis()); }
        @Override public Duration build(List<Object> parts) { return Duration.ofMillis(((Number) parts.getFirst()).longValue()); }
    };

    private static final PluginType<Where> WHERE_TYPE = new PluginType<>() {
        @Override public Class<Where> type() { return Where.class; }
        @Override public Where fresh() { return null; }
        @Override public java.lang.reflect.Method freshCall() {
            try {
                return Where.class.getMethod("current");
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
        @Override public Node editor(ValueContext ctx) { return null; }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(WHERE_TYPE), List.of(THING_TYPE, DURATION_TYPE));

    private static final StudioPlugin PLUGIN = () -> "test.record";

    private static final StudioPlugin OTHER = new StudioPlugin() {
        @Override public String id() { return "test.other"; }
        @Override public String displayName() { return "Other"; }
    };

    private static Recordings.Writer writer(String method, Gesture gesture, int rank, Recordings.Slot... slots)
            throws ReflectiveOperationException {
        return writer(PLUGIN, method, gesture, rank, slots);
    }

    private static Recordings.Writer writer(StudioPlugin plugin, String method, Gesture gesture, int rank,
                                            Recordings.Slot... slots) throws ReflectiveOperationException {
        Class<?>[] types = switch (method) {
            case "click" -> new Class<?>[]{Where.class, int.class, int.class};
            case "clickXY" -> new Class<?>[]{int.class, int.class};
            case "clickThing" -> new Class<?>[]{Thing.class};
            case "type" -> new Class<?>[]{String.class};
            case "combo" -> new Class<?>[]{Key[].class};
            case "pause" -> new Class<?>[]{Duration.class};
            case "awaitThing" -> new Class<?>[]{Thing.class, int.class};
            default -> throw new IllegalArgumentException(method);
        };
        return new Recordings.Writer(plugin, Pad.class.getMethod(method, types), gesture, rank, List.of(slots));
    }

    private static RecordingWriter recorder(List<ManagedConstants.Constant> constants) throws ReflectiveOperationException {
        return new RecordingWriter(ownWriters(), GRAMMAR, null, constants);
    }

    /** This plugin's writers plus a second plugin's two CLICK writers, the chosen plugin tried first. */
    private static RecordingWriter contested(String chosenForClick) throws ReflectiveOperationException {
        List<Recordings.Writer> writers = new java.util.ArrayList<>(ownWriters());
        writers.addAll(otherWriters());
        return new RecordingWriter(writers, GRAMMAR, null, List.of(),
                gesture -> gesture == Gesture.CLICK ? chosenForClick : null);
    }

    private static List<Recordings.Writer> otherWriters() throws ReflectiveOperationException {
        return List.of(
                writer(OTHER, "clickThing", Gesture.CLICK, 0, new Recordings.Slot.Recorded(THING_AT_FAR)),
                writer(OTHER, "clickXY", Gesture.CLICK, 0,
                        new Recordings.Slot.Number(int.class), new Recordings.Slot.Number(int.class)));
    }

    /** A picture at x of 200 or more, nothing nearer. */
    private static final RecordedValue<Thing> THING_AT_FAR = new RecordedValue<>() {
        @Override public Class<Thing> type() { return Thing.class; }
        @Override public Optional<Thing> at(StudioServices services, Spot spot) {
            return spot.x() >= 200 ? Optional.of(new Thing("far.png")) : Optional.empty();
        }
    };

    private static List<Recordings.Writer> ownWriters() throws ReflectiveOperationException {
        return List.of(
                writer("clickThing", Gesture.CLICK, 10, new Recordings.Slot.Recorded(THING_AT)),
                writer("click", Gesture.CLICK, 0, new Recordings.Slot.Fresh(Where.class.getName()),
                        new Recordings.Slot.Number(int.class), new Recordings.Slot.Number(int.class)),
                writer("type", Gesture.TYPE, 0, new Recordings.Slot.Text()),
                writer("combo", Gesture.COMBO, 0, new Recordings.Slot.Keys(Key.class, true)),
                writer("pause", Gesture.PAUSE, 0, new Recordings.Slot.Parts(DURATION_TYPE, List.of(long.class))),
                writer("awaitThing", Gesture.AWAIT, 0, new Recordings.Slot.Recorded(THING_AT),
                        new Recordings.Slot.Number(int.class)));
    }

    private static Gestures.Recognized click(int x, int y, long at) {
        return new Gestures.Recognized(Gesture.CLICK, List.of(x, y), new RecordedValue.Spot(x, y, null), at);
    }

    private static List<String> sources(List<RecordingWriter.Statement> statements) {
        return statements.stream().map(RecordingWriter.Statement::source).toList();
    }

    @Test
    void a_click_on_something_nameable_takes_the_ranked_writer() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(click(10, 20, 0)));

        assertEquals(List.of("Pad.clickThing(new Thing(\"a.png\"));"), sources(out));
        assertEquals(List.of(PAD.replace("Pad", "Thing"), PAD), out.getFirst().imports());
    }

    @Test
    void a_click_elsewhere_falls_back_to_the_spot_with_the_fresh_source() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(click(100, 20, 0)));

        assertEquals(List.of("Pad.click(Where.current(), 100, 20);"), sources(out));
        assertEquals(List.of(PAD.replace("Pad", "Where"), PAD), out.getFirst().imports());
    }

    @Test
    void a_value_equal_to_a_managed_constant_is_written_as_the_constant() throws Exception {
        List<ManagedConstants.Constant> pictures = List.of(
                new ManagedConstants.Constant("com.example.bot.Pictures", "COLLECT", "new Thing(\"a.png\")"));

        List<RecordingWriter.Statement> out = recorder(pictures).write(List.of(click(10, 20, 0)));

        assertEquals(List.of("Pad.clickThing(Pictures.COLLECT);"), sources(out));
        assertEquals(List.of("com.example.bot.Pictures", PAD), out.getFirst().imports());
    }

    @Test
    void typing_and_a_combo_are_written_with_the_plugins_types() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(
                new Gestures.Recognized(Gesture.TYPE, List.of("hi \"you\""), null, 0),
                new Gestures.Recognized(Gesture.COMBO, List.of("CTRL", "S"), null, 10)));

        assertEquals(List.of("Pad.type(\"hi \\\"you\\\"\");", "Pad.combo(Key.CTRL, Key.S);"), sources(out));
    }

    @Test
    void a_pause_before_a_nameable_click_waits_for_it() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(
                new Gestures.Recognized(Gesture.PAUSE, List.of(1200L), null, 0),
                click(10, 20, 1200)));

        assertEquals(List.of("Pad.awaitThing(new Thing(\"a.png\"), 6);", "Pad.clickThing(new Thing(\"a.png\"));"),
                sources(out));
    }

    @Test
    void a_pause_before_anything_else_stays_a_pause() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(
                new Gestures.Recognized(Gesture.PAUSE, List.of(1200L), null, 0),
                click(100, 20, 1200)));

        assertEquals("Pad.pause(Duration.ofMillis(1200L));", out.getFirst().source());
        assertEquals(List.of("java.time.Duration", PAD), out.getFirst().imports());
    }

    @Test
    void with_no_choice_a_tie_goes_to_the_highest_rank() throws Exception {
        List<RecordingWriter.Statement> out = contested(null).write(List.of(click(10, 20, 0)));

        assertEquals(List.of("Pad.clickThing(new Thing(\"a.png\"));"), sources(out));
    }

    @Test
    void the_chosen_plugin_writes_the_gesture_whatever_its_rank() throws Exception {
        List<RecordingWriter.Statement> out = contested(OTHER.id()).write(List.of(click(10, 20, 0)));

        assertEquals(List.of("Pad.clickXY(10, 20);"), sources(out));
    }

    @Test
    void the_chosen_plugins_own_order_is_kept_and_its_first_writer_that_fills_wins() throws Exception {
        List<RecordingWriter.Statement> out = contested(OTHER.id()).write(List.of(click(250, 20, 0)));

        assertEquals(List.of("Pad.clickThing(new Thing(\"far.png\"));"), sources(out));
    }

    @Test
    void a_choice_naming_a_plugin_that_is_gone_changes_nothing() throws Exception {
        List<RecordingWriter.Statement> out = contested("test.uninstalled").write(List.of(click(10, 20, 0)));

        assertEquals(List.of("Pad.clickThing(new Thing(\"a.png\"));"), sources(out));
    }

    @Test
    void only_a_gesture_two_plugins_write_is_a_contest() throws Exception {
        List<Recordings.Writer> writers = new java.util.ArrayList<>(ownWriters());
        writers.addAll(otherWriters());
        writers.sort(java.util.Comparator.comparingInt((Recordings.Writer w) -> w.gesture().ordinal())
                .thenComparing(java.util.Comparator.comparingInt(Recordings.Writer::rank).reversed()));

        List<RecordingWriter.Contest> contests = RecordingWriter.contests(writers);

        assertEquals(1, contests.size());
        assertEquals(Gesture.CLICK, contests.getFirst().gesture());
        assertEquals(List.of("test.record", "test.other"),
                contests.getFirst().claims().stream().map(c -> c.pluginId()).toList());
        assertEquals("Click", contests.getFirst().label());
    }

    @Test
    void a_key_the_plugins_enum_does_not_have_writes_nothing() throws Exception {
        List<RecordingWriter.Statement> out = recorder(List.of()).write(List.of(
                new Gestures.Recognized(Gesture.COMBO, List.of("CTRL", "F13"), null, 0)));

        assertEquals(List.of(), out);
    }
}

/** A plugin's facade, as far as recording is concerned. */
final class Pad {
    private Pad() {}
    public static void click(Where where, int x, int y) {}
    public static void clickXY(int x, int y) {}
    public static void clickThing(Thing thing) {}
    public static void type(String text) {}
    public static void combo(Key... keys) {}
    public static void pause(Duration duration) {}
    public static void awaitThing(Thing thing, int seconds) {}
}

/** A picture: a value the host cannot read off the screen. */
record Thing(String path) {}

/** A capture source: a type written only through its fresh call. */
final class Where {
    public static Where current() { return new Where(); }
    private Where() {}
}

enum Key { CTRL, S }
