package com.botmaker.studio.project.source;

import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.project.params.JavaParameterSource;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.nio.file.Path;
import java.util.List;

/**
 * How a bot's source is parsed when the host reads what it declares: against the project's resolved
 * classpath and source root, so a type, an annotation and a constant are what javac says they are.
 *
 * <p><b>Why bindings now (2026-09-24).</b> The value readers parsed syntax only, so that a bot whose pom did
 * not resolve still showed its parameters, and paid for it with names: a type was whatever its spelling
 * ended in. Binding recovery keeps the first property — an unresolved type comes back <em>recovered</em>,
 * not missing, and the unit still parses — and {@link BotAnnotation} and {@link SourceNames} fall back to the
 * unit's imports for exactly that case. What the classpath gives is the exact class.
 *
 * <p>{@link #SYNTAX} is the parser for text the host has just been handed and is about to rewrite, and for a
 * caller with no project: the same tree, no bindings, and every reader still exact through the imports.
 *
 * @param classpath  the project's resolved jars, or {@code null} for no bindings at all
 * @param sourceRoot the bot's {@code src/main/java}, or {@code null}; its other types resolve through it
 */
public record BotParser(List<String> classpath, Path sourceRoot) {

    /** Syntax only: no classpath, no bindings. */
    public static final BotParser SYNTAX = new BotParser(null, null);

    public BotParser {
        classpath = classpath == null ? null : List.copyOf(classpath);
    }

    /** The parser for the open project, or {@link #SYNTAX} with none. FX thread, as {@link ProjectState} is. */
    public static BotParser of(ProjectState state) {
        return state == null ? SYNTAX : new BotParser(state.getResolvedClasspath(), state.getSourcePath());
    }

    /** Whether units from this parser carry bindings. */
    public boolean binds() {
        return classpath != null;
    }

    /**
     * {@code source} as a unit. {@code file} names it for the binding resolver, which needs a unit name to
     * resolve a source's own declarations; {@code null} is fine for a unit that declares nothing another
     * reads.
     */
    public CompilationUnit parse(Path file, String source) {
        if (!binds()) return JavaParameterSource.parse(source);
        return ProjectAnalyzer.createCompilationUnit(classpath, source, sourceRoot,
                file == null ? null : file.toAbsolutePath().toString());
    }
}
