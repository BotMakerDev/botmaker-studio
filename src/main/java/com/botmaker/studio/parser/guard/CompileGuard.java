package com.botmaker.studio.parser.guard;

import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
