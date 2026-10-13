package com.botmaker.studio.runtime;

import com.botmaker.studio.runtime.tryhost.TryHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * {@link TryHost}'s jar, written from Studio's own class the first time a try needs it, as {@link TraceAgentJar}
 * writes the trace agent: the warm JVM's classpath is the bot's, and Studio's jar is the whole IDE.
 */
final class TryHostJar {

    private static Path written;

    private TryHostJar() {}

    /** The jar, written once per Studio process into a temporary directory. */
    static synchronized Path path() throws IOException {
        if (written != null && Files.isRegularFile(written)) return written;
        Path dir = Files.createTempDirectory("botmaker-try-host");
        Path jar = dir.resolve("botmaker-try-host.jar");
        write(jar);
        // Deleted in reverse order of registering: the jar, then its emptied folder.
        dir.toFile().deleteOnExit();
        jar.toFile().deleteOnExit();
        written = jar;
        return jar;
    }

    static void write(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        String entry = TryHost.class.getName().replace('.', '/') + ".class";
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest);
             InputStream in = TryHostJar.class.getClassLoader().getResourceAsStream(entry)) {
            if (in == null) throw new IOException("Studio is missing the try host's " + entry);
            out.putNextEntry(new JarEntry(entry));
            in.transferTo(out);
            out.closeEntry();
        }
    }
}
