package com.botmaker.studio.project.source;

import com.botmaker.studio.parser.guard.CompileGuard;
import com.botmaker.studio.parser.guard.CompileGuard.Problem;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.services.BotSources;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The whole bot, parsed once with bindings — what every question about the bot's own names is asked of
 * (2026-09-27): where a declaration is used, what a rename rewrites, whether an edit to one file breaks
 * another.
 *
 * <p><b>One batch, over the buffers.</b> Each file used to be parsed on its own, against the source root on
 * disk, so a file's references to another resolved against that file's <em>saved</em> text: an unsaved
 * rename in the open buffer was invisible to every other file. Here every source — the open buffer where
 * there is one — is written into a scratch copy of the source tree and the copy is parsed in one
 * {@link ProjectAnalyzer#createCompilationUnits batch}, so every unit agrees with every other one and with
 * what the user sees. A file outside the source root (a library source the user opened) has no place in that
 * tree and is parsed on its own.
 *
 * <p><b>Why an edit can be judged before it is written.</b> {@link #with} is the same bot with some files
 * changed, parsed the same way into another scratch copy; {@link #firstNewError} compares the two. So a
 * rename that would stop the bot compiling is refused without the user's files ever holding it — the check
 * the Parameters window did by writing, re-reading and putting back.
 *
 * <p><b>Cached by content.</b> {@link #of} answers the same index until any source, the classpath or the root
 * changes. Units are shared between callers, and a JDT tree is not safe to walk from two threads while its
 * bindings resolve, so every walk goes through {@link #read}.
 */
public final class BotIndex {

    private static final Object CACHE_LOCK = new Object();
    private static BotIndex cached;

    private final List<String> classpath;
    private final Path root;
    private final Map<Path, String> sources;
    private final Map<Path, CompilationUnit> units;
    private Map<Path, List<Problem>> errors;

    private BotIndex(List<String> classpath, Path root, Map<Path, String> sources,
                     Map<Path, CompilationUnit> units) {
        this.classpath = classpath;
        this.root = root;
        this.sources = sources;
        this.units = units;
    }

    /**
     * The index of the open project, reused while nothing it was built from has changed. Reads the buffers,
     * so call it where {@link ProjectState} may be read; the parse itself is safe on a worker.
     */
    public static BotIndex of(ProjectConfig config, ProjectState state) {
        return prepare(config, state).get();
    }

    /**
     * {@link #of} in two halves: the sources are read now, on the thread that may read {@link ProjectState},
     * and the parse happens when the answer is asked for — on a worker, for a search the user waits on.
     */
    public static Supplier<BotIndex> prepare(ProjectConfig config, ProjectState state) {
        Map<Path, String> sources = new LinkedHashMap<>();
        if (config != null) BotSources.scan(config, state, sources::put);
        Path root = state != null && state.getSourcePath() != null ? state.getSourcePath()
                : config == null ? null : config.sourceRoot();
        List<String> classpath = state == null ? List.of() : List.copyOf(state.getResolvedClasspath());
        return () -> over(sources, classpath, root);
    }

    /**
     * The index of the files the editor holds — the open project's, for a caller that has its state and no
     * config. Cached like {@link #of}.
     */
    public static BotIndex of(ProjectState state) {
        Map<Path, String> sources = new LinkedHashMap<>();
        for (ProjectFile file : state.getAllFiles()) {
            if (file.getPath() != null && file.getContent() != null) sources.put(file.getPath(), file.getContent());
        }
        return over(sources, state.getResolvedClasspath(), state.getSourcePath());
    }

    /**
     * {@code sources} parsed as one bot, answered from the cache when they are what it was built from.
     *
     * @param sources   every source by absolute path, in the order answers should list them
     * @param classpath the project's resolved jars; empty resolves the bot's own names and recovers the rest
     * @param root      the bot's {@code src/main/java}; a source outside it is parsed on its own
     */
    public static BotIndex over(Map<Path, String> sources, List<String> classpath, Path root) {
        synchronized (CACHE_LOCK) {
            if (cached != null && cached.builtFrom(classpath, root, sources)) return cached;
        }
        BotIndex index = build(sources, classpath, root);
        synchronized (CACHE_LOCK) {
            cached = index;
        }
        return index;
    }

    private static BotIndex build(Map<Path, String> sources, List<String> classpath, Path root) {
        Map<Path, String> copy = normalised(sources);
        List<String> jars = classpath == null ? List.of() : List.copyOf(classpath);
        Path base = root == null ? null : root.toAbsolutePath().normalize();
        return new BotIndex(jars, base, copy, parse(copy, jars, base));
    }

    /**
     * This bot with {@code rewrites} applied and each file of {@code moves} at its new path — parsed afresh,
     * and not cached: it is a bot that does not exist yet.
     */
    public BotIndex with(Map<Path, String> rewrites, Map<Path, Path> moves) {
        Map<Path, String> changed = new LinkedHashMap<>();
        sources.forEach((file, text) -> {
            String now = rewrites.getOrDefault(file, text);
            changed.put(moves.getOrDefault(file, file), now);
        });
        return build(changed, classpath, root);
    }

    /** The same, with nothing moved. */
    public BotIndex with(Map<Path, String> rewrites) {
        return with(rewrites, Map.of());
    }

    /** Every source, by absolute path, in walk order. */
    public Map<Path, String> sources() {
        return sources;
    }

    /** The parser that reads one more text the way this index read its own. */
    public BotParser parser() {
        return new BotParser(classpath, root);
    }

    /**
     * Runs {@code walk} over the units, by path, holding them for its duration. Nothing else touches them
     * meanwhile — a walk resolves bindings, and resolution is not safe from two threads.
     */
    public synchronized <T> T read(Function<Map<Path, CompilationUnit>, T> walk) {
        return walk.apply(units);
    }

    /** Every compile error each file has, by path. */
    public synchronized Map<Path, List<Problem>> errors() {
        if (errors == null) {
            Map<Path, List<Problem>> out = new LinkedHashMap<>();
            units.forEach((file, unit) -> {
                List<Problem> problems = new ArrayList<>();
                for (IProblem problem : unit.getProblems()) {
                    if (!problem.isError()) continue;
                    problems.add(new Problem(problem.getID(), List.of(problem.getArguments()),
                            problem.getMessage(), problem.getSourceStart()));
                }
                out.put(file, problems);
            });
            errors = out;
        }
        return errors;
    }

    /**
     * The first error {@code after} has that this index did not, as {@code File.java, line N: message} — or
     * empty when {@code after} compiles no worse. Only a new error counts, so a bot that was already broken
     * somewhere is never locked out of an edit elsewhere; a moved file is compared under its new path with
     * what it had under its old one.
     */
    public Optional<String> firstNewError(BotIndex after, Map<Path, Path> moves) {
        Map<Path, List<Problem>> before = new LinkedHashMap<>();
        errors().forEach((file, problems) -> before.put(moves.getOrDefault(file, file), problems));
        for (Map.Entry<Path, List<Problem>> entry : after.errors().entrySet()) {
            List<Problem> introduced = new ArrayList<>(entry.getValue());
            for (Problem existing : before.getOrDefault(entry.getKey(), List.of())) introduced.remove(existing);
            if (introduced.isEmpty()) continue;
            String code = after.sources.getOrDefault(entry.getKey(), "");
            return Optional.of(entry.getKey().getFileName() + ", "
                    + CompileGuard.describe(introduced, code).getFirst());
        }
        return Optional.empty();
    }

    /** The same, with nothing moved. */
    public Optional<String> firstNewError(BotIndex after) {
        return firstNewError(after, Map.of());
    }

    private boolean builtFrom(List<String> classpath, Path root, Map<Path, String> sources) {
        Path base = root == null ? null : root.toAbsolutePath().normalize();
        return this.classpath.equals(classpath == null ? List.of() : classpath)
                && Objects.equals(this.root, base)
                && this.sources.equals(normalised(sources));
    }

    private static Map<Path, String> normalised(Map<Path, String> sources) {
        Map<Path, String> out = new LinkedHashMap<>();
        sources.forEach((file, text) -> out.put(file.toAbsolutePath().normalize(), text));
        return out;
    }

    // --- the parse ----------------------------------------------------------------------------------------

    /**
     * Every source under {@code root}, written into a scratch copy of the tree and parsed there in one batch,
     * keyed back by its real path. With no root there is no tree to resolve through, and each file is parsed
     * on its own.
     */
    private static Map<Path, CompilationUnit> parse(Map<Path, String> sources, List<String> classpath, Path root) {
        Map<Path, CompilationUnit> out = new LinkedHashMap<>();
        BotParser alone = new BotParser(classpath, root);
        if (root == null) {
            sources.forEach((file, text) -> out.put(file, alone.parse(file, text)));
            return out;
        }
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("botmaker-index");
            Map<Path, Path> real = new LinkedHashMap<>();
            for (Map.Entry<Path, String> entry : sources.entrySet()) {
                if (!entry.getKey().startsWith(root)) continue;
                Path copy = scratch.resolve(root.relativize(entry.getKey()).toString());
                Files.createDirectories(copy.getParent());
                Files.writeString(copy, entry.getValue());
                real.put(copy.toAbsolutePath().normalize(), entry.getKey());
            }
            Map<Path, CompilationUnit> parsed = ProjectAnalyzer.createCompilationUnits(classpath, scratch,
                    List.copyOf(real.keySet()));
            // In walk order, whatever order the batch answered in.
            Map<Path, CompilationUnit> byReal = new LinkedHashMap<>();
            parsed.forEach((copy, unit) -> byReal.put(real.get(copy.toAbsolutePath().normalize()), unit));
            sources.forEach((file, text) -> out.put(file,
                    byReal.containsKey(file) ? byReal.get(file) : alone.parse(file, text)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't stage the bot's sources for parsing", e);
        } finally {
            if (scratch != null) delete(scratch);
        }
    }

    private static void delete(Path scratch) {
        try (Stream<Path> walk = Files.walk(scratch)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A scratch file left in the temp directory costs nothing; the parse already has its units.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }
}
