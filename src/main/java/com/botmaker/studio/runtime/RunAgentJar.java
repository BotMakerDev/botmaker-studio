package com.botmaker.studio.runtime;

import com.botmaker.plugin.host.PluginPoms;
import com.botmaker.runlog.RunLog;
import com.botmaker.shared.ipc.TelemetryClient;
import com.botmaker.studio.runtime.agent.RunAgent;
import com.botmaker.studio.runtime.agent.run.AgentRun;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * The run agent's two jars, written from Studio's own classes the first time a run needs them (2026-09-30, two
 * since 2026-11-03).
 *
 * <ul>
 *   <li><b>The agent jar</b>, named by {@code -javaagent}: the package {@code runtime.agent}, JDK only, which joins
 *       the bot's classpath because every traced bot class links to it.</li>
 *   <li><b>The inner jar</b>, which the agent loads child first ({@code InnerLoader}): {@code runtime.agent.run}
 *       with the wire ({@code shared.ipc}), the log writer (runlog), Jackson, and plugin-host with the pom reader
 *       it uses. Copied package by package from wherever Studio's classes are — its shaded jar, or the build's
 *       jars and class folders in development — so the agent is always the one this Studio was built with, and
 *       none of it ever meets the bot's own versions of those libraries.</li>
 * </ul>
 *
 * <p>Not a module of its own: only Studio launches bots, and the wire's two ends stay one build (user,
 * 2026-11-03). Packages are copied rather than relocated: the JDK has no class remapper, and a loader of its own
 * isolates them as well.
 */
public final class RunAgentJar {

    /** The agent jar's package: every class in it, none from below. */
    static final String AGENT = "com/botmaker/studio/runtime/agent/";

    /** Each package tree of the inner jar, by a class that lives in it. */
    static final List<Tree> INNER = List.of(
            new Tree(AgentRun.class, "com/botmaker/studio/runtime/agent/run/"),
            new Tree(TelemetryClient.class, "com/botmaker/shared/ipc/"),
            new Tree(RunLog.class, "com/botmaker/runlog/"),
            new Tree(ObjectMapper.class, "com/fasterxml/jackson/"),
            new Tree(JsonParser.class, "com/fasterxml/jackson/"),
            new Tree(JsonProperty.class, "com/fasterxml/jackson/"),
            new Tree(PluginPoms.class, "com/botmaker/plugin/host/"),
            new Tree(MavenXpp3Reader.class, "org/apache/maven/model/"),
            new Tree(XmlPullParserException.class, "org/codehaus/plexus/util/"));

    /** The classes under {@code prefix} wherever {@code anchor} was loaded from. */
    record Tree(Class<?> anchor, String prefix) {
        /** The anchor's own class file, which the tree must hold. */
        String anchorEntry() {
            return anchor.getName().replace('.', '/') + ".class";
        }
    }

    /** Both jars, written once per Studio process. */
    /** The agent's jar, and its inner jar unless Studio could not write it (the run then gets the trace only). */
    public record Jars(Path agent, Optional<Path> inner) {
    }

    private static Jars written;

    private RunAgentJar() {}

    /** Both jars, written once per Studio process into a temporary directory. */
    public static synchronized Jars jars() throws IOException {
        if (written != null && Files.isRegularFile(written.agent())
                && written.inner().map(Files::isRegularFile).orElse(true)) {
            return written;
        }
        Path dir = Files.createTempDirectory("botmaker-run-agent");
        dir.toFile().deleteOnExit();
        Path agent = dir.resolve("botmaker-run-agent.jar");
        writeAgent(agent);
        agent.toFile().deleteOnExit();
        Path inner = dir.resolve("botmaker-run-agent-inner.jar");
        Optional<Path> kept = Optional.of(inner);
        try {
            writeInner(inner);
            inner.toFile().deleteOnExit();
        } catch (IOException | RuntimeException e) {
            System.err.println("Runs keep no log and reach no plugin this time: " + e.getMessage());
            kept = Optional.empty();
        }
        Jars jars = new Jars(agent, kept);
        written = jars;
        return jars;
    }

    static void writeAgent(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(new Attributes.Name("Premain-Class"), RunAgent.class.getName());
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            int copied = copy(out, new Tree(RunAgent.class, AGENT), false, new HashSet<>());
            if (copied == 0) throw new IOException("Studio is missing the run agent's classes");
        }
    }

    static void writeInner(Path jar) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Set<String> entries = new HashSet<>();
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (Tree tree : INNER) {
                copy(out, tree, true, entries);
                if (!entries.contains(tree.anchorEntry())) {
                    throw new IOException("Studio is missing the run agent's " + tree.prefix());
                }
            }
        }
    }

    /**
     * Copies the class files under {@code tree}'s prefix (and below it when {@code below}) from where its anchor was
     * loaded; an entry already in {@code entries} is skipped. Returns how many it copied.
     */
    private static int copy(JarOutputStream out, Tree tree, boolean below, Set<String> entries) throws IOException {
        Path from = location(tree.anchor());
        int copied = 0;
        if (Files.isDirectory(from)) {
            Path root = from.resolve(tree.prefix());
            if (!Files.isDirectory(root)) return 0;
            try (Stream<Path> files = below ? Files.walk(root) : Files.list(root)) {
                for (Path f : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class"))
                        .toList()) {
                    String name = from.relativize(f).toString().replace('\\', '/');
                    if (!entries.add(name)) continue;
                    try (InputStream in = Files.newInputStream(f)) {
                        put(out, name, in);
                    }
                    copied++;
                }
            }
            return copied;
        }
        try (JarFile jar = new JarFile(from.toFile())) {
            Enumeration<JarEntry> all = jar.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(tree.prefix()) || !name.endsWith(".class")) continue;
                if (!below && name.indexOf('/', tree.prefix().length()) >= 0) continue;
                if (!entries.add(name)) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    put(out, name, in);
                }
                copied++;
            }
        }
        return copied;
    }

    private static void put(JarOutputStream out, String name, InputStream in) throws IOException {
        out.putNextEntry(new JarEntry(name));
        in.transferTo(out);
        out.closeEntry();
    }

    /** The jar or class folder {@code type} was loaded from. */
    private static Path location(Class<?> type) throws IOException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IOException("cannot tell where " + type.getName() + " was loaded from");
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new IOException("cannot read " + source.getLocation(), e);
        }
    }
}
