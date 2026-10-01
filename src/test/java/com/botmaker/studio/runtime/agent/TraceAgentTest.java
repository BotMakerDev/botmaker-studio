package com.botmaker.studio.runtime.agent;

import com.botmaker.shared.Diag;
import com.botmaker.shared.ipc.TelemetryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent's rewrite, in this JVM: a bot class is rewritten as {@link CallSites} would rewrite it on load, then
 * run, and every call it makes into the traced class is one line, through {@code Diag}, under the class's name.
 */
class TraceAgentTest {

    private final List<TelemetryEvent.Log> lines = new ArrayList<>();

    @AfterEach
    void restore() {
        Diag.setSink(null);
        Diag.set(true);
    }

    @Test
    void eachCallTheBotMakesIsOneLineAndARepeatIsCounted() throws Exception {
        Diag.set(true);
        Diag.setSink(lines::add);
        TracePlan plan = new TracePlan(Set.of(), Set.of(TracedFixture.class.getName()),
                Set.of(TracedFixture.class.getName() + "#hiddenOne"));

        Class<?> bot = rewritten(BotFixture.class, plan);
        bot.getMethod("run").invoke(null);

        List<String> texts = lines.stream().map(l -> withoutTime(l.text())).toList();
        assertEquals(List.of(
                "add(1, 2) → 3",
                "greet(\"Ana\") → \"Hi Ana from x\"",
                "poll() → false",
                "poll() → false  again",
                "add(2, 2) → 4",
                "fail() threw IllegalStateException: boom",
                "count([a, b]) → 2"), texts, "twice() is the plugin's own call, quiet() is @Untraced, "
                + "hiddenOne() is hidden: " + texts);
        assertEquals(4, lines.get(3).count(), "four more polls after the first");
        TelemetryEvent.Log first = lines.getFirst();
        assertEquals("TracedFixture", first.source());
        assertEquals(TracedFixture.class.getName(), first.writerClass());
        assertEquals("add", first.writerMethod());
    }

    @Test
    void aClassThatCallsNothingTracedIsLeftAsItWas() throws IOException {
        TracePlan plan = new TracePlan(Set.of(), Set.of("com.example.Nothing"), Set.of());
        assertNull(new CallSites(plan).rewrite(bytes(BotFixture.class), getClass().getClassLoader()));
    }

    @Test
    void aQuietRunWritesNoLineButStillCalls() throws Exception {
        Diag.set(false);
        Diag.setSink(lines::add);
        TracePlan plan = new TracePlan(Set.of(), Set.of(TracedFixture.class.getName()), Set.of());

        rewritten(BotFixture.class, plan).getMethod("run").invoke(null);

        assertTrue(lines.isEmpty(), lines.toString());
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
