package com.botmaker.studio.runtime;

import com.botmaker.studio.runtime.agent.TraceAgent;
import com.botmaker.studio.runtime.agent.TracePlan;
import com.botmaker.studio.runtime.agent.TracedCalls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * The trace agent's jar, written from Studio's own classes the first time a run needs it (2026-09-30).
 *
 * <p>Not Studio's jar itself: an agent jar joins the bot's classpath, and Studio's is the whole IDE. Not a module
 * of its own either: the agent is three classes and the JDK, and a module would have been a repository, a release
 * and a pin to keep for them. Studio carries the classes and writes them out, so the agent is always the one this
 * Studio was built with.
 */
public final class TraceAgentJar {

    /**
     * Every class file the agent is made of. A class the package grows is listed here, and
     * {@code TraceAgentJarTest} fails until it is.
     */
    static final List<String> CLASSES = List.of(
            TraceAgent.class.getName(),
            TracePlan.class.getName(),
            TracedCalls.class.getName(),
            TracedCalls.class.getName() + "$Call",
            TracedCalls.class.getName() + "$Repeat",
            "com.botmaker.studio.runtime.agent.CallSites");

    private static Path written;

    private TraceAgentJar() {}

    /** The jar, written once per Studio process into a temporary directory. */
    public static synchronized Path path() throws IOException {
        if (written != null && Files.isRegularFile(written)) return written;
        Path dir = Files.createTempDirectory("botmaker-trace-agent");
        Path jar = dir.resolve("botmaker-trace-agent.jar");
        write(jar);
        jar.toFile().deleteOnExit();
        dir.toFile().deleteOnExit();
        written = jar;
        return jar;
    }

    static void write(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Premain-Class"), TraceAgent.class.getName());
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (String className : CLASSES) {
                String entry = className.replace('.', '/') + ".class";
                try (InputStream in = TraceAgentJar.class.getClassLoader().getResourceAsStream(entry)) {
                    if (in == null) throw new IOException("Studio is missing the trace agent's " + entry);
                    out.putNextEntry(new JarEntry(entry));
                    in.transferTo(out);
                    out.closeEntry();
                }
            }
        }
    }
}
