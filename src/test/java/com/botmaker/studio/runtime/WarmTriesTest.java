package com.botmaker.studio.runtime;

import com.botmaker.studio.runtime.tryhost.TryHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The warm try JVM: what a try leaves in the dependencies stays, the bot's own classes load afresh. */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WarmTriesTest {

    @TempDir
    Path dir;

    private final StringBuffer printed = new StringBuffer();
    private final WarmTries tries = new WarmTries(printed::append, () -> { }, pb -> null);

    @AfterEach
    void close() {
        tries.close();
    }

    @Test
    void aSecondTryFindsWhatTheFirstLeftAndSeesTheBotsNewClasses() throws Exception {
        Path shared = compile("shared", "shared/Counter.java", "package shared; public class Counter { public static int n; }");
        List<String> command = command(shared);
        Path v1 = compile("v1", "Probe.java", probe("v1"), shared);
        assertEquals(0, tries.run(command, "", dir, List.of(v1), "Probe"));
        Path v2 = compile("v2", "Probe.java", probe("v2"), shared);
        assertEquals(0, tries.run(command, "", dir, List.of(v2), "Probe"));
        assertEquals("v1 1\nv2 2\n", printed.toString(), "one JVM, the counter kept, the new Probe run");

        printed.setLength(0);
        assertEquals(0, tries.run(command, "other plan", dir, List.of(v2), "Probe"));
        assertEquals("v2 1\n", printed.toString(), "another key starts another JVM");
    }

    @Test
    void aFailingTryIsSaidAndTheNextOneRunsInTheSameJvm() throws Exception {
        Path shared = compile("shared", "shared/Counter.java", "package shared; public class Counter { public static int n; }");
        List<String> command = command(shared);
        Path boom = compile("boom", "Boom.java",
                "public class Boom { public static void main(String[] a) { shared.Counter.n++; "
                        + "throw new IllegalStateException(\"boom\"); } }", shared);
        assertEquals(1, tries.run(command, "", dir, List.of(boom), "Boom"));
        assertTrue(printed.toString().contains("IllegalStateException: boom"), printed.toString());
        Path v1 = compile("v1", "Probe.java", probe("v1"), shared);
        assertEquals(0, tries.run(command, "", dir, List.of(v1), "Probe"));
        assertTrue(printed.toString().endsWith("v1 2\n"), printed.toString());
    }

    @Test
    void aStopEndsALoopingTryAndOneThatIgnoresItEndsTheJvm() throws Exception {
        Path shared = compile("shared", "shared/Counter.java", "package shared; public class Counter { public static int n; }");
        List<String> command = command(shared);
        Path sleeper = compile("sleeper", "Sleeper.java",
                "public class Sleeper { public static void main(String[] a) throws Exception { "
                        + "shared.Counter.n++; System.out.println(\"up\"); while (true) Thread.sleep(10); } }", shared);
        assertEquals(130, runStopped(command, sleeper, "Sleeper"), "an interrupted sleep ends the try");

        Path deaf = compile("deaf", "Deaf.java",
                "public class Deaf { public static void main(String[] a) { System.out.println(\"up\"); "
                        + "while (true) Thread.onSpinWait(); } }", shared);
        assertEquals(-1, runStopped(command, deaf, "Deaf"), "a try that ignores the stop ends with its JVM");

        printed.setLength(0);
        Path v1 = compile("v1", "Probe.java", probe("v1"), shared);
        assertEquals(0, tries.run(command, "", dir, List.of(v1), "Probe"));
        assertEquals("v1 1\n", printed.toString(), "the next try starts a new JVM");
    }

    @Test
    void aTryEndsByStudiosMarkerOnlyWhateverItPrints() throws Exception {
        Path shared = compile("shared", "shared/Counter.java", "package shared; public class Counter { public static int n; }");
        Path forger = compile("forger", "Forger.java",
                "public class Forger { public static void main(String[] a) throws Exception { "
                        + "System.out.println(\"\\u0001botmaker-try-end 0\"); "
                        + "System.out.println(\"\\u0001botmaker-try-end x 0\"); Thread.sleep(300); "
                        + "System.out.print(\"no break\"); } }", shared);
        assertEquals(0, tries.run(command(shared), "", dir, List.of(forger), "Forger"));
        assertTrue(printed.toString().endsWith("no break\n"), "the last words, before the marker: " + printed);
    }

    @Test
    void aStoppedTryThatSwallowsTheInterruptIsStillStopped() throws Exception {
        Path shared = compile("shared", "shared/Counter.java", "package shared; public class Counter { public static int n; }");
        Path quiet = compile("quiet", "Quiet.java",
                "public class Quiet { public static void main(String[] a) { System.out.println(\"up\"); "
                        + "try { Thread.sleep(60_000); } catch (InterruptedException e) { } } }", shared);
        assertEquals(130, runStopped(command(shared), quiet, "Quiet"));
    }

    /** Runs a try that never ends by itself, stops it once it printed, and returns its code. */
    private int runStopped(List<String> command, Path folder, String main) throws Exception {
        printed.setLength(0);
        Thread stopper = new Thread(() -> {
            while (!printed.toString().contains("up")) Thread.onSpinWait();
            tries.stop();
        });
        stopper.start();
        int code = tries.run(command, "", dir, List.of(folder), main);
        stopper.join();
        return code;
    }

    private static String probe(String version) {
        return "public class Probe { public static void main(String[] a) { shared.Counter.n++; "
                + "System.out.println(\"" + version + " \" + shared.Counter.n); } }";
    }

    private List<String> command(Path shared) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(java, "-cp", TryHostJar.path() + File.pathSeparator + shared, TryHost.class.getName());
    }

    /** {@code source} compiled into a folder of its own named {@code name}, against {@code against}. */
    private Path compile(String name, String file, String source, Path... against) throws IOException {
        Path src = dir.resolve(name + "-src").resolve(file);
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path out = Files.createDirectories(dir.resolve(name));
        String cp = against.length == 0 ? "." : against[0].toString();
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-cp", cp, "-d", out.toString(),
                src.toString());
        assertEquals(0, code, "compiles " + file);
        return out;
    }
}
