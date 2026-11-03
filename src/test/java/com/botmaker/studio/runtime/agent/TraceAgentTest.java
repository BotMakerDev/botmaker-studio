package com.botmaker.studio.runtime.agent;

import com.botmaker.runlog.ScreenText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's rewrite, in this JVM: a bot class is rewritten as {@link CallSites} would rewrite it on load, then
 * run, and every call it makes into the traced class is one line to the run ({@link RunLines}), under the class's
 * name.
 */
class TraceAgentTest {

    /** One line as the run heard it. */
    record Heard(String source, String text, int count, String writerClass, String writerMethod) {
    }

    /** A run that keeps lines while {@code recorded}. */
    static final class Lines implements RunLines {
        final List<Heard> heard = new ArrayList<>();
        final boolean recorded;

        Lines(boolean recorded) {
            this.recorded = recorded;
        }

        @Override
        public boolean recorded() {
            return recorded;
        }

        @Override
        public void call(String source, String text, int count, String writerClass, String writerMethod) {
            heard.add(new Heard(source, text, count, writerClass, writerMethod));
        }

        @Override
        public void ended() {
        }
    }

    @AfterEach
    void restore() {
        TracedCalls.to(null);
    }

    @Test
    void eachCallTheBotMakesIsOneLineAndARepeatIsCounted() throws Exception {
        Lines lines = new Lines(true);
        TracedCalls.to(lines);
        TracePlan plan = new TracePlan(Set.of(), Set.of(TracedFixture.class.getName()),
                Set.of(TracedFixture.class.getName() + "#hiddenOne"));

        Class<?> bot = rewritten(BotFixture.class, plan);
        bot.getMethod("run").invoke(null);

        List<String> texts = lines.heard.stream().map(l -> withoutTime(l.text())).toList();
        assertEquals(List.of(
                "add(1, 2) → 3",
                "greet(\"Ana\") → \"Hi Ana from x\"",
                "poll() → false",
                "poll() → false  again",
                "add(2, 2) → 4",
                "fail() threw IllegalStateException: boom",
                "count([a, b]) → 2"), texts, "twice() is the plugin's own call, quiet() is @Untraced, "
                + "hiddenOne() is hidden: " + texts);
        assertEquals(4, lines.heard.get(3).count(), "four more polls after the first");
        Heard first = lines.heard.getFirst();
        assertEquals("TracedFixture", first.source());
        assertEquals(TracedFixture.class.getName(), first.writerClass());
        assertEquals("add", first.writerMethod());
    }

    @Test
    void aResultReadOffTheScreenIsMarkedAsTheRunLogHidesIt() {
        TracedCalls.Call read = new TracedCalls.Call(TracedFixture.class, "read", false, false, true);
        TracedCalls.Call add = new TracedCalls.Call(TracedFixture.class, "add", false, false, false);
        assertEquals(ScreenText.mark("\"Gold 12\""), TracedCalls.result(read, "Gold 12"));
        assertEquals("…", ScreenText.hide(TracedCalls.result(read, "Gold 12")));
        assertEquals("3", TracedCalls.result(add, 3));
        assertEquals("…", ScreenText.hide(TracedCalls.result(read, "name" + ScreenText.CLOSE + " secret")),
                "a closing mark the screen showed cannot end the span early");
    }

    @Test
    void aClassThatCallsNothingTracedIsLeftAsItWas() throws IOException {
        TracePlan plan = new TracePlan(Set.of(), Set.of("com.example.Nothing"), Set.of());
        assertNull(new CallSites(plan).rewrite(bytes(BotFixture.class), getClass().getClassLoader()));
    }

    @Test
    void aRunThatKeepsNothingBuildsNoLineButStillCalls() throws Exception {
        Lines lines = new Lines(false);
        TracedCalls.to(lines);
        TracePlan plan = new TracePlan(Set.of(), Set.of(TracedFixture.class.getName()), Set.of());

        rewritten(BotFixture.class, plan).getMethod("run").invoke(null);

        assertTrue(lines.heard.isEmpty(), lines.heard.toString());
    }

    @Test
    void theRunPlanSurvivesItsFile() throws IOException {
        Path file = java.nio.file.Files.createTempFile("plan", ".properties");
        TracePlan trace = new TracePlan(Set.of(Path.of("/tmp/a b/classes")), Set.of("x.Mouse"), Set.of());
        RunPlan plan = new RunPlan(trace, Optional.of(Path.of("/tmp/bot/target/botmaker-runs")),
                Optional.of(Path.of("/tmp/inner.jar")));
        plan.write(file);
        assertEquals(plan, RunPlan.read(file));
        assertEquals(trace, TracePlan.read(file), "the trace part reads alone");
    }

    @Test
    void thePlanSurvivesItsFile() throws IOException {
        Path file = java.nio.file.Files.createTempFile("plan", ".properties");
        TracePlan plan = new TracePlan(Set.of(Path.of("/tmp/a b/classes")), Set.of("x.Mouse", "x.Wait"),
                Set.of("x.Wait", "x.Mouse#move"));
        plan.write(file);
        TracePlan read = TracePlan.read(file);
        assertEquals(plan, read);
        assertTrue(read.traces("x.Mouse", "click"));
        assertTrue(!read.traces("x.Mouse", "move") && !read.traces("x.Wait", "time") && !read.traces("x.Key", "tap"));
    }

    /** Collapsed lines end in "again, N ms each, over M ms"; single ones in "N ms". The time is not asserted. */
    private static String withoutTime(String text) {
        int again = text.indexOf("  again");
        if (again >= 0) return text.substring(0, again + "  again".length());
        int last = text.lastIndexOf("  ");
        return last < 0 ? text : text.substring(0, last);
    }

    private static Class<?> rewritten(Class<?> type, TracePlan plan) throws IOException {
        byte[] bytes = new CallSites(plan).rewrite(bytes(type), type.getClassLoader());
        return new OneClass(type.getName(), bytes, type.getClassLoader()).load();
    }

    private static byte[] bytes(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    /** Defines one class from given bytes, child-first, and leaves every other to its parent. */
    private static final class OneClass extends ClassLoader {
        private final String name;
        private final byte[] bytes;

        OneClass(String name, byte[] bytes, ClassLoader parent) {
            super(parent);
            this.name = name;
            this.bytes = bytes;
        }

        Class<?> load() {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
