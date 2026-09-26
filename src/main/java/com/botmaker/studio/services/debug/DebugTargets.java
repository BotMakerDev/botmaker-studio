package com.botmaker.studio.services.debug;

import com.botmaker.studio.parser.BlockId;
import com.botmaker.studio.parser.helpers.SourceParser;
import com.botmaker.studio.project.ProjectState.SourceFile;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Statement;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where a debug session can stop, in every source file of the bot — not only the one on screen.
 *
 * <p>A session used to map the <em>open</em> file's blocks and request breakpoints on the <em>entry</em> class,
 * so both only lined up when the entry file was the one open. A breakpoint set in an activity's file never hit,
 * and Follow on a template bot watched {@code main} block on {@code Bot.run} and showed nothing. This reads each
 * file's own parse instead, so a line is always a line of the class it is requested on.
 *
 * <p>Pure: built from a snapshot's sources and the breakpoint store's copy, off the FX thread.
 */
public record DebugTargets(Map<String, FileTargets> byTopLevelClass) {

    /**
     * One source file: its top-level classes' binary names, the first line of every statement in it, and the
     * lines its breakpoints sit on.
     */
    public record FileTargets(Path file, List<String> classNames, Set<Integer> lines, Set<Integer> breakpointLines) {}

    public static DebugTargets of(Path sourceRoot, List<SourceFile> files, Map<Path, Set<String>> breakpoints) {
        Map<String, FileTargets> byClass = new HashMap<>();
        for (SourceFile source : files) {
            Path path = source.path();
            if (path == null || !path.startsWith(sourceRoot) || !path.toString().endsWith(".java")) continue;
            FileTargets targets = read(path, source.content(), breakpoints.getOrDefault(path, Set.of()));
            targets.classNames().forEach(name -> byClass.put(name, targets));
        }
        return new DebugTargets(Map.copyOf(byClass));
    }

    static FileTargets read(Path path, String content, Set<String> breakpointIds) {
        CompilationUnit cu = SourceParser.parse(content);
        String pkg = cu.getPackage() == null ? "" : cu.getPackage().getName().getFullyQualifiedName() + ".";
        List<String> classNames = ((List<?>) cu.types()).stream()
                .map(t -> pkg + ((AbstractTypeDeclaration) t).getName().getIdentifier())
                .toList();
        Set<Integer> lines = new TreeSet<>();
        Set<Integer> breakpointLines = new TreeSet<>();
        cu.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                int line = cu.getLineNumber(node.getStartPosition());
                if (line <= 0) return;
                if (node instanceof Statement && !(node instanceof Block)) lines.add(line);
                if (!breakpointIds.isEmpty() && breakpointIds.contains(BlockId.of(node))) breakpointLines.add(line);
            }
        });
        return new FileTargets(path, classNames, Set.copyOf(lines), Set.copyOf(breakpointLines));
    }

    /** The file a class was compiled from; a nested or anonymous class answers for its top-level one. */
    public Optional<FileTargets> forClass(String binaryName) {
        int nested = binaryName.indexOf('$');
        return Optional.ofNullable(byTopLevelClass.get(nested < 0 ? binaryName : binaryName.substring(0, nested)));
    }

    public boolean anyBreakpoint() {
        return byTopLevelClass.values().stream().anyMatch(f -> !f.breakpointLines().isEmpty());
    }

    public Set<String> classNames() {
        return byTopLevelClass.keySet();
    }

    /** The first statement line of {@code file}, where a session with no breakpoint pauses. */
    public Optional<Integer> firstLine(Path file) {
        return byTopLevelClass.values().stream()
                .filter(f -> f.file().equals(file))
                .flatMap(f -> f.lines().stream())
                .min(Integer::compare);
    }
}
