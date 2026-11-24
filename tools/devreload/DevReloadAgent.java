// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

/**
 * Dev-only: redefines Studio's classes in the running JVM when {@code mvn compile} (or the IDE) rewrites them in
 * {@code target/classes}. On a JetBrains Runtime started with {@code -XX:+AllowEnhancedClassRedefinition} a class may
 * also gain or lose fields and methods; on a stock JDK only method bodies change and the rest is reported.
 * Started by {@code tools/dev-studio.sh}: {@code -javaagent:devreload.jar=<classes dir>}.
 */
public final class DevReloadAgent {

    /** Hash of each class's bytes as last redefined, so an unchanged rewrite is skipped. */
    private static final Map<String, Integer> SEEN = new HashMap<>();

    private DevReloadAgent() {}

    public static void premain(String classesDir, Instrumentation inst) throws IOException {
        Path root = Path.of(classesDir).toAbsolutePath();
        Thread watcher = new Thread(() -> watch(root, inst), "dev-reload");
        watcher.setDaemon(true);
        watcher.start();
        System.err.println("[dev-reload] watching " + root
                + (inst.isRedefineClassesSupported() ? "" : " (redefine NOT supported)"));
    }

    private static void watch(Path root, Instrumentation inst) {
        try (WatchService service = root.getFileSystem().newWatchService()) {
            Map<WatchKey, Path> dirs = new HashMap<>();
            register(root, service, dirs);
            while (true) {
                WatchKey key = service.take();
                Map<String, Path> changed = new HashMap<>();
                // Maven writes many classes in a burst: collect, settle, then redefine once.
                collect(key, service, dirs, changed, root);
                Thread.sleep(400);
                for (WatchKey more; (more = service.poll()) != null; ) {
                    collect(more, service, dirs, changed, root);
                }
                redefine(changed, inst);
            }
        } catch (InterruptedException | IOException e) {
            System.err.println("[dev-reload] stopped: " + e);
        }
    }

    private static void collect(WatchKey key, WatchService service, Map<WatchKey, Path> dirs,
                                Map<String, Path> changed, Path root) throws IOException {
        Path dir = dirs.get(key);
        if (dir == null) return;
        for (var event : key.pollEvents()) {
            if (!(event.context() instanceof Path name)) continue;
            Path file = dir.resolve(name);
            if (Files.isDirectory(file)) {
                register(file, service, dirs);
            } else if (file.toString().endsWith(".class")) {
                String rel = root.relativize(file).toString();
                changed.put(rel.substring(0, rel.length() - 6).replace('/', '.'), file);
            }
        }
        key.reset();
    }

    private static void register(Path start, WatchService service, Map<WatchKey, Path> dirs) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dirs.put(dir.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY), dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void redefine(Map<String, Path> changed, Instrumentation inst) {
        if (changed.isEmpty()) return;
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (changed.containsKey(c.getName()) && inst.isModifiableClass(c)) loaded.put(c.getName(), c);
        }
        int done = 0;
        for (var entry : changed.entrySet()) {
            Class<?> c = loaded.get(entry.getKey());
            if (c == null) continue; // not loaded yet: it loads fresh from disk
            try {
                byte[] bytes = Files.readAllBytes(entry.getValue());
                int hash = java.util.Arrays.hashCode(bytes);
                Integer before = SEEN.put(entry.getKey(), hash);
                if (before != null && before == hash) continue; // Maven rewrites every class: only a change counts
                inst.redefineClasses(new ClassDefinition(c, bytes));
                done++;
            } catch (Throwable t) { // UnsupportedOperationException on a stock JDK's structural change
                System.err.println("[dev-reload] " + entry.getKey() + " NOT reloaded (restart needed): " + t);
            }
        }
        if (done > 0) System.err.println("[dev-reload] redefined " + done + " class(es)");
    }
}
