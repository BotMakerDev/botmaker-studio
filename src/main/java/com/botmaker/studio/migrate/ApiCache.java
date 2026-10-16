package com.botmaker.studio.migrate;

import com.botmaker.shared.config.CacheDirs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * The classpath each project loaded before its last change, kept so the old API can still be read once the
 * jars moved (2026-10-16): what the Migrate window compares the bot against, on demand.
 *
 * <p><b>What a change is.</b> Each load records the classpath it loaded; one that differs from the last
 * recorded keeps that last one as the classpath before. An upgrade or a removal changes the jars' paths; a
 * {@code -SNAPSHOT} rebuilt in place by {@code mvn install} keeps its path and changes its bytes, so each
 * {@code -SNAPSHOT} jar is recorded as a copy of the bytes loaded, {@code <sha>.jar} — the jar the bot compiled
 * against is gone once rebuilt, unless a copy was taken while it was loaded. A released jar never changes under
 * its path, so it is recorded as itself.
 *
 * <p>Best-effort: a cache that cannot be written answers the jars themselves, which is the same bot on both
 * sides — nothing is broken and nothing rewritten.
 */
public final class ApiCache {

    private static final String LOADED = "classpath";
    private static final String BEFORE = "before";

    private final Path root;

    ApiCache(Path root) {
        this.root = root;
    }

    /** The cache every project shares, under {@code ~/.cache/botmaker/api}. */
    public static ApiCache shared() {
        return new ApiCache(CacheDirs.cacheRoot().resolve("api"));
    }

    /**
     * Records that {@code project} loaded {@code classpath}: each {@code -SNAPSHOT} jar whose size or time changed
     * is hashed and copied when its bytes are new, and a classpath unlike the last one keeps that one as before.
     */
    public void loaded(Path project, List<String> classpath) {
        // An open whose resolve failed binds no jar: no change the bot was carried over.
        if (classpath == null || classpath.isEmpty()) return;
        synchronized (LOCK) {
            record(project, classpath);
        }
    }

    /** Opening a project and reloading its plugins both load: one at a time, so neither loses the other's record. */
    private static final Object LOCK = new Object();

    private void record(Path project, List<String> classpath) {
        Properties record = read(project);
        Properties was = (Properties) record.clone();
        List<String> kept = new ArrayList<>();
        for (String entry : classpath) {
            Path jar = Path.of(entry);
            if (!snapshot(jar) || !Files.isRegularFile(jar)) {
                kept.add(entry);
                continue;
            }
            try {
                String stamp = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
                if (!stamp.equals(record.getProperty(entry + ".stamp"))) {
                    record.setProperty(entry + ".current", kept(jar));
                    record.setProperty(entry + ".stamp", stamp);
                }
                kept.add(root.resolve(record.getProperty(entry + ".current") + ".jar").toString());
            } catch (IOException | UncheckedIOException e) {
                System.err.println("Could not keep a copy of " + jar.getFileName() + " for migrations: "
                        + e.getMessage());
                kept.add(entry);
            }
        }
        String loaded = String.join(File.pathSeparator, kept);
        String last = record.getProperty(LOADED);
        if (!loaded.equals(last)) {
            if (last != null) record.setProperty(BEFORE, last);
            record.setProperty(LOADED, loaded);
        }
        if (!record.equals(was)) write(project, record);
    }

    /**
     * The classpath {@code project} loaded before {@code classpath}, the one it has now: the one before the last
     * recorded change, or the last one recorded when {@code classpath} is a change not recorded yet. {@code classpath}
     * itself when there is none, or a jar of it is gone.
     */
    public List<String> previous(Path project, List<String> classpath) {
        Properties record;
        synchronized (LOCK) {
            record = read(project);
        }
        String last = record.getProperty(LOADED);
        if (last == null) return List.copyOf(classpath);
        String before = recorded(record, classpath).equals(last) ? record.getProperty(BEFORE) : last;
        if (before == null || before.isEmpty()) return List.copyOf(classpath);
        List<String> out = Arrays.asList(before.split(File.pathSeparator));
        return out.stream().allMatch(entry -> Files.isRegularFile(Path.of(entry))) ? List.copyOf(out)
                : List.copyOf(classpath);
    }

    /** {@code classpath} as a load would record it, with what {@code record} knows: a jar's bytes unseen as itself. */
    private String recorded(Properties record, List<String> classpath) {
        List<String> out = new ArrayList<>();
        for (String entry : classpath) {
            Path jar = Path.of(entry);
            String current = record.getProperty(entry + ".current");
            String stamp = record.getProperty(entry + ".stamp");
            String now = null;
            if (current != null && snapshot(jar) && Files.isRegularFile(jar)) {
                try {
                    now = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
                } catch (IOException e) {
                    now = null;
                }
            }
            out.add(now != null && now.equals(stamp) ? root.resolve(current + ".jar").toString() : entry);
        }
        return String.join(File.pathSeparator, out);
    }

    /** Whether {@code jar} sits in a {@code -SNAPSHOT} version's folder of a Maven repository. */
    static boolean snapshot(Path jar) {
        Path folder = jar.getParent();
        return folder != null && folder.getFileName() != null && folder.getFileName().toString().endsWith("-SNAPSHOT");
    }

    private Path recordOf(Path project) {
        String key = HexFormat.of().formatHex(digest("SHA-1",
                project.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)));
        return root.resolve("projects").resolve(key + ".properties");
    }

    private Properties read(Path project) {
        Properties record = new Properties();
        Path file = recordOf(project);
        if (!Files.isRegularFile(file)) return record;
        try (InputStream in = Files.newInputStream(file)) {
            record.load(in);
        } catch (IOException e) {
            System.err.println("Could not read the migration cache for " + project + ": " + e.getMessage());
        }
        return record;
    }

    private void write(Path project, Properties record) {
        Path file = recordOf(project);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                record.store(out, "The jars " + project + " loaded, for its migrations");
            }
        } catch (IOException e) {
            System.err.println("Could not write the migration cache for " + project + ": " + e.getMessage());
        }
    }

    /**
     * {@code jar} copied into the cache under the hash of the bytes copied — hashed as they are copied, so a jar
     * rebuilt mid-copy is kept under its own bytes' name — and that hash.
     */
    private String kept(Path jar) throws IOException {
        Files.createDirectories(root);
        Path partial = Files.createTempFile(root, "jar", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(jar); OutputStream out = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            String sha = HexFormat.of().formatHex(digest.digest());
            Path copy = root.resolve(sha + ".jar");
            if (!Files.exists(copy)) {
                Files.move(partial, copy, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            return sha;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
