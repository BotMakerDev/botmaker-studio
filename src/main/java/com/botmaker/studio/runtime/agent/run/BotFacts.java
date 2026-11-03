package com.botmaker.studio.runtime.agent.run;

import com.botmaker.runlog.RunFacts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The facts any host knows of a run, written as its log's first line ({@link RunFacts}): the bot's coordinate,
 * where its code came from (a run copy's {@code bot.json}, else its git checkout), the versions of the host jars
 * and plugins on its classpath, and the OS. Each fact it cannot tell is left unknown; nothing here throws or waits
 * long (git gets {@value #GIT_SECONDS} s).
 */
final class BotFacts {

    private static final String GROUP = "com.github.BotMakerDev";
    private static final List<String> HOST_JARS = List.of("botmaker-shared", "botmaker-session", "botmaker-runlog");
    /** What every plugin jar carries ({@code botmaker-plugin-parent} copies its changelog there). */
    private static final String PLUGIN_MARK = "META-INF/botmaker/changelog.md";
    private static final Pattern PARENT = Pattern.compile("(?s)<parent>.*?</parent>");
    private static final Pattern POM_PROPERTIES = Pattern.compile("META-INF/maven/[^/]+/[^/]+/pom\\.properties");
    private static final long GIT_SECONDS = 3;

    private BotFacts() {}

    static RunFacts of(Path botFolder, Map<String, String> details, long startMillis) {
        Map<String, String> source = source(botFolder);
        return new RunFacts(coordinate(botFolder), source.get("tag"), source.get("commit"),
                Boolean.parseBoolean(source.get("dirty")), versions(ClassLoader.getSystemClassLoader()),
                System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""), startMillis,
                details);
    }

    /** {@code groupId:artifactId} of the bot's own pom, its parent's left out; null when it does not read. */
    static String coordinate(Path botFolder) {
        try {
            String pom = PARENT.matcher(Files.readString(botFolder.resolve("pom.xml"), StandardCharsets.UTF_8))
                    .replaceFirst("");
            String group = element(pom, "groupId");
            String artifact = element(pom, "artifactId");
            return group == null || artifact == null ? null : group + ":" + artifact;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String element(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<\\s]+)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** {@code tag}, {@code commit} and {@code dirty}, those the folder tells. */
    static Map<String, String> source(Path botFolder) {
        Map<String, String> out = new LinkedHashMap<>();
        Path json = botFolder.resolve("bot.json");
        if (Files.isRegularFile(json)) {
            try {
                JsonNode node = new ObjectMapper().readTree(json.toFile());
                if (node.hasNonNull("tag")) out.put("tag", node.get("tag").asText());
                if (node.hasNonNull("commit")) out.put("commit", node.get("commit").asText());
            } catch (IOException | RuntimeException ignored) {
                // a bot.json that does not read says nothing
            }
            return out;
        }
        if (!Files.exists(botFolder.resolve(".git"))) return out;
        String commit = git(botFolder, "rev-parse", "HEAD");
        if (commit != null) out.put("commit", commit);
        String tag = git(botFolder, "describe", "--tags", "--exact-match");
        if (tag != null) out.put("tag", tag);
        String status = git(botFolder, "status", "--porcelain");
        if (status != null) out.put("dirty", String.valueOf(!status.isBlank()));
        return out;
    }

    private static String git(Path folder, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", folder.toString()));
        command.addAll(List.of(args));
        Path out = null;
        try {
            // Output to a file, so the wait's timeout holds: a git stuck with its output open never blocks the run.
            out = Files.createTempFile("botmaker-git", ".out");
            Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(out.toFile()).start();
            if (!p.waitFor(GIT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? Files.readString(out, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // a temp file left behind
                }
            }
        }
    }

    /** {@code groupId:artifactId} to version of the host jars and every plugin jar {@code loader} sees. */
    static Map<String, String> versions(ClassLoader loader) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String artifact : HOST_JARS) {
            try (InputStream in = loader.getResourceAsStream(
                    "META-INF/maven/" + GROUP + "/" + artifact + "/pom.properties")) {
                if (in != null) put(out, in);
            } catch (IOException ignored) {
                // left out
            }
        }
        try {
            Enumeration<URL> plugins = loader.getResources(PLUGIN_MARK);
            while (plugins.hasMoreElements()) {
                if (plugins.nextElement().openConnection() instanceof JarURLConnection jar) pluginVersion(jar, out);
            }
        } catch (IOException ignored) {
            // the plugins' versions are left out
        }
        return out;
    }

    private static void pluginVersion(JarURLConnection connection, Map<String, String> out) {
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            for (JarEntry entry : jar.stream().toList()) {
                if (!POM_PROPERTIES.matcher(entry.getName()).matches()) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    put(out, in);
                }
            }
        } catch (IOException ignored) {
            // this plugin's version is left out
        }
    }

    private static void put(Map<String, String> out, InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        String group = p.getProperty("groupId");
        String artifact = p.getProperty("artifactId");
        String version = p.getProperty("version");
        if (group != null && artifact != null && version != null) out.put(group + ":" + artifact, version);
    }
}
