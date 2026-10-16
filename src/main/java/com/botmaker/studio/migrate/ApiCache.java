package com.botmaker.studio.migrate;

import com.botmaker.shared.config.CacheDirs;

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
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/**
 * The bytes each {@code -SNAPSHOT} jar of a project had before its last change, kept so the old API can still be
 * read on a reload of the same version (2026-10-16).
 *
 * <p><b>Why only {@code -SNAPSHOT}.</b> A released jar never changes under its path, so an upgrade's old jars are
 * still in the local repository under the version the pom pinned. A {@code -SNAPSHOT} is rebuilt in place by
 * {@code mvn install}, and once it is the jar the bot compiled against is gone — unless a copy was taken while it
 * was loaded. Each load copies a {@code -SNAPSHOT} jar whose bytes it has not seen to {@code <sha>.jar} and
 * records, per project and jar path, the bytes now loaded and the ones loaded before them.
 *
 * <p>Best-effort: a cache that cannot be written answers the jars themselves, which is the same bot on both
 * sides — nothing is broken and nothing rewritten.
 */
public final class ApiCache {

    private final Path root;

    ApiCache(Path root) {
        this.root = root;
    }

    /** The cache every project shares, under {@code ~/.cache/botmaker/api}. */
    public static ApiCache shared() {
        return new ApiCache(CacheDirs.cacheRoot().resolve("api"));
    }

    /**
     * Records that {@code project} loaded {@code classpath}: each {@code -SNAPSHOT} jar whose size or time
     * changed is hashed, copied when its bytes are new, and its bytes before kept as its previous ones.
     */
    public void loaded(Path project, List<String> classpath) {
        synchronized (LOCK) {
            record(project, classpath);
        }
    }

    /** Opening a project and reloading its plugins both load: one at a time, so neither loses the other's record. */
    private static final Object LOCK = new Object();

    private void record(Path project, List<String> classpath) {
        Properties record = read(project);
        boolean changed = false;
        for (String entry : classpath) {
            Path jar = Path.of(entry);
            if (!snapshot(jar) || !Files.isRegularFile(jar)) continue;
            try {
                String stamp = Files.size(jar) + ":" + Files.getLastModifiedTime(jar).toMillis();
                if (stamp.equals(record.getProperty(entry + ".stamp"))) continue;
                String sha = kept(jar);
                String current = record.getProperty(entry + ".current");
                if (current != null && !current.equals(sha)) record.setProperty(entry + ".previous", current);
                record.setProperty(entry + ".current", sha);
                record.setProperty(entry + ".stamp", stamp);
                changed = true;
            } catch (IOException | UncheckedIOException e) {
                System.err.println("Could not keep a copy of " + jar.getFileName() + " for migrations: "
                        + e.getMessage());
            }
        }
        if (changed) write(project, record);
    }

    /**
     * {@code classpath} as {@code project} loaded it before: each {@code -SNAPSHOT} jar whose bytes changed since
     * replaced by its copy of the bytes before. Every other entry is itself.
     */
    public List<String> previous(Path project, List<String> classpath) {
        Properties record;
        synchronized (LOCK) {
            record = read(project);
        }
        List<String> out = new ArrayList<>();
        for (String entry : classpath) {
            String before = record.getProperty(entry + ".previous");
            Path copy = before == null ? null : root.resolve(before + ".jar");
            out.add(copy != null && Files.isRegularFile(copy) ? copy.toString() : entry);
        }
        return List.copyOf(out);
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
