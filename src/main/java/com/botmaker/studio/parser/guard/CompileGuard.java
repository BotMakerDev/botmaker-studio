package com.botmaker.studio.parser.guard;

import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether an edit makes a file compile worse than it did — the rule every generated edit is held to,
 * whoever asked for it: <b>only a new error refuses</b>.
 *
 * <p>It was the assistant's alone ({@code assist/AssistTurn}) until 2026-09-24. A block chosen from a menu is
 * the same kind of edit — the host writes Java the user did not type — and it could leave a file that no
 * longer compiled: a second {@code enum MyEnum}, an instance call in a static method. The syntax gate in
 * {@code CodeEditor.wouldBreak} cannot see those; this parses against the project's classpath and can.
 *
 * <p>An error the file already had never refuses, so a half-finished file is never locked out of the edits
 * that would finish it. Two versions of a file agree on an error by its JDT id and arguments, not its
 * position: an insert moves every offset after it.
 *
 * @param parser how the file is parsed; {@link BotParser#SYNTAX} checks syntax only, as a test with no
 *               classpath does
 * @param file   the file's path, which the binding resolver needs for a unit's own declarations; nullable
 */
public record CompileGuard(BotParser parser, Path file) {

    public CompileGuard {
        Objects.requireNonNull(parser, "parser");
    }

    /** The guard for the open project's active file. FX thread, as {@link ProjectState} is. */
    public static CompileGuard of(ProjectState state) {
        ProjectFile active = state == null ? null : state.getActiveFile();
        return new CompileGuard(BotParser.of(state), active == null ? null : active.getPath());
    }

    /** One compile error, identified the way two versions of a file can agree on: id plus arguments. */
    public record Problem(int id, List<String> arguments, String message, int offset) {
        public Problem {
            arguments = List.copyOf(arguments);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Problem p && p.id == id && p.arguments.equals(arguments);
        }

        @Override public int hashCode() {
            return Objects.hash(id, arguments);
        }
    }

    /** Every error {@code code} compiles with. */
    public List<Problem> errorsOf(String code) {
        CompilationUnit cu = parser.parse(file, code);
        List<Problem> problems = new ArrayList<>();
        for (IProblem problem : cu.getProblems()) {
            if (!problem.isError()) continue;
            problems.add(new Problem(problem.getID(), List.of(problem.getArguments()), problem.getMessage(),
                    problem.getSourceStart()));
        }
        return problems;
    }

    /**
     * The errors {@code after} has that {@code before} did not — counted, so a second copy of an error the
     * file already had is new.
     */
    public List<Problem> introduced(String before, String after) {
        List<Problem> introduced = new ArrayList<>(errorsOf(after));
        for (Problem existing : errorsOf(before)) introduced.remove(existing);
        return introduced;
    }

    /**
     * The errors each of {@code files} compiles with now — the "before" of an edit to <em>another</em> file
     * they depend on, which {@link #introduced} cannot see: a field retyped in {@code Parameters.java} breaks
     * {@code Base.java}, whose own text did not change.
     */
    public static Map<Path, List<Problem>> errorsIn(BotParser parser, Map<Path, String> files) {
        Map<Path, List<Problem>> out = new LinkedHashMap<>();
        files.forEach((file, code) -> out.put(file, new CompileGuard(parser, file).errorsOf(code)));
        return out;
    }

    /**
     * The first error one of the files in {@code before} has in {@code now} that it did not have then, as the
     * sentence {@link #describe} makes of it prefixed with the file's name — or empty when none is new.
     */
    public static Optional<String> firstIntroduced(BotParser parser, Map<Path, List<Problem>> before,
                                                   Map<Path, String> now) {
        for (Map.Entry<Path, List<Problem>> entry : before.entrySet()) {
            String code = now.get(entry.getKey());
            if (code == null) continue;
            List<Problem> introduced = new ArrayList<>(new CompileGuard(parser, entry.getKey()).errorsOf(code));
            for (Problem existing : entry.getValue()) introduced.remove(existing);
            if (!introduced.isEmpty()) {
                return Optional.of(entry.getKey().getFileName() + ", " + describe(introduced, code).getFirst());
            }
        }
        return Optional.empty();
    }

    /** One sentence per problem, with the line it is on in {@code code}. */
    public static List<String> describe(List<Problem> problems, String code) {
        return problems.stream().map(p -> "line " + lineOf(code, p.offset()) + ": " + p.message()).toList();
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, code.length()); i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }
}
