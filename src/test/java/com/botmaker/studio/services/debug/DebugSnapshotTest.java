package com.botmaker.studio.services.debug;

import com.botmaker.studio.project.ProjectState.SourceFile;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Debug tab's snapshot, read off a real paused VM: the bot's frames are marked as the bot's, and a value
 * is shown without running any of the bot's code — a primitive, a string, an enum constant, a boxed number,
 * an array's length and elements, an object's fields.
 */
class DebugSnapshotTest {

    private static final String SOURCE = """
            package com.mybot;

            public class Probe {
                enum Mode { FAST, SLOW }
                static final class Box { int size = 7; String label = "crate"; }

                public static void main(String[] args) {
                    play(3);
                }

                static void play(int rounds) {
                    String name = "hero";
                    Mode mode = Mode.SLOW;
                    Integer boxed = 42;
                    int[] scores = {1, 2, 3};
                    Box box = new Box();
                    System.out.println(name + mode + boxed + scores.length + box.size);
                }
            }
            """;

    /** The println line: every local above it is assigned. */
    private static final int PAUSE_LINE = 17;

    @Test
    void aPausedFrameShowsItsVariablesAndTheBotsFramesAreMarked(@TempDir Path dir) throws Exception {
        Path sourceRoot = dir.resolve("src");
        Path file = sourceRoot.resolve("com/mybot/Probe.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), file.toString()));
        DebugTargets targets = DebugTargets.of(sourceRoot, List.of(new SourceFile(file, SOURCE)), Map.of());

        DebugSnapshot snapshot = pauseAt(classes, "com.mybot.Probe", PAUSE_LINE, targets);

        DebugSnapshot.Frame top = snapshot.frames().getFirst();
        assertEquals("Probe.play:" + PAUSE_LINE, top.label());
        assertTrue(top.inBot());
        assertEquals(file, top.file());
        assertEquals("Probe.main:8", snapshot.frames().get(1).label());

        Map<String, DebugSnapshot.Variable> vars = new java.util.LinkedHashMap<>();
        for (DebugSnapshot.Variable v : top.variables()) vars.put(v.name(), v);
        assertEquals(List.of("rounds", "name", "mode", "boxed", "scores", "box"), List.copyOf(vars.keySet()));
        assertEquals("3", vars.get("rounds").value());
        assertEquals("\"hero\"", vars.get("name").value());
        assertEquals("Probe.Mode.SLOW", vars.get("mode").value());
        assertEquals("42", vars.get("boxed").value());
        assertEquals("int[3]", vars.get("scores").value());
        assertEquals(List.of("[0]=1", "[1]=2", "[2]=3"), vars.get("scores").children().stream()
                .map(c -> c.name() + "=" + c.value()).toList());
        assertTrue(vars.get("box").value().startsWith("Probe.Box #"), vars.get("box").value());
        assertEquals(List.of("size=7", "label=\"crate\""), vars.get("box").children().stream()
                .map(c -> c.name() + "=" + c.value()).toList());
    }

    /**
     * The debugger's own way in: a {@code suspend=y} JVM attached over a socket, and nothing resumed but the
     * event sets — the VMStart set is what starts it. If an attach delivered no VMStartEvent the bot would never
     * start, and this would time out.
     */
    @Test
    void anAttachedSuspendedVmStartsFromItsStartEventAndStopsOnItsFirstLine(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("src/com/mybot/Probe.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-g", "-d", classes.toString(), file.toString()));
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + port,
                "-cp", classes.toString(), "com.mybot.Probe").redirectErrorStream(true).start();
        try {
            com.sun.jdi.connect.AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors()
                    .stream().filter(c -> c.transport().name().equals("dt_socket")).findFirst().orElseThrow();
            Map<String, Connector.Argument> args = connector.defaultArguments();
            args.get("port").setValue(String.valueOf(port));
            args.get("hostname").setValue("localhost");
            VirtualMachine vm = null;
            for (int i = 0; i < 50 && vm == null; i++) {
                try {
                    vm = connector.attach(args);
                } catch (java.io.IOException notYet) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(vm, "attached");
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("com.mybot.Probe");
            prepare.enable();
            assertEquals("Probe.main:8", awaitBreakpoint(vm, 8, null).frames().getFirst().label());
        } finally {
            process.destroyForcibly();
        }
    }

    /** Launches {@code mainClass} suspended, stops at {@code line}, reads the snapshot, and kills the VM. */
    private static DebugSnapshot pauseAt(Path classes, String mainClass, int line, DebugTargets targets)
            throws Exception {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = connector.defaultArguments();
        args.get("main").setValue(mainClass);
        args.get("options").setValue("-cp " + classes);
        VirtualMachine vm = connector.launch(args);
        try {
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter(mainClass);
            prepare.enable();
            // No vm.resume(): the VMStart event set's resume starts it. Resuming here too races the
            // ClassPrepare suspension (see DebuggingService.attachJdi).
            return awaitBreakpoint(vm, line, targets);
        } finally {
            try {
                vm.exit(0);
            } catch (RuntimeException alreadyGone) {
                // The VM may have disconnected already; either way it is not left running.
            }
        }
    }

    /** Runs the event loop until the probe class is prepared, stops it at {@code line}, and reads the pause. */
    private static DebugSnapshot awaitBreakpoint(VirtualMachine vm, int line, DebugTargets targets) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            EventSet events = vm.eventQueue().remove(1000);
            if (events == null) continue;
            for (Event event : events) {
                if (event instanceof ClassPrepareEvent cpe) {
                    ReferenceType type = cpe.referenceType();
                    Location at = type.locationsOfLine(line).getFirst();
                    vm.eventRequestManager().createBreakpointRequest(at).enable();
                } else if (event instanceof BreakpointEvent bp) {
                    return DebugSnapshot.of(bp.thread(), targets);
                } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                    fail("the probe ended before its breakpoint");
                }
            }
            events.resume();
        }
        return fail("no breakpoint within 20 s");
    }
}
