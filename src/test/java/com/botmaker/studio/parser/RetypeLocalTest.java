package com.botmaker.studio.parser;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.plugin.grammar.ValueTypes;
import com.botmaker.studio.project.ProjectFile;
import com.botmaker.studio.project.ProjectState;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A local retyped from the type chooser: any type, written as a tree with its imports (2026-09-26). */
class RetypeLocalTest {

    private static final String SOURCE = """
            package test;

            public class Subject {
                public void run() {
                    int count = 3;
                }
            }
            """;

    private String lastCode;

    @Test
    void aLocalBecomesAListOfTextWithItsImport() {
        ProjectState state = new ProjectState();
        Path path = Paths.get("Subject.java").toAbsolutePath();
        state.addFile(new ProjectFile(path, SOURCE));
        state.setActiveFile(path);
        state.setSourcePath(Paths.get("src", "main", "java").toAbsolutePath());
        state.setResolvedClasspath(TestSupport.runtimeClassPath());
        EventBus bus = new EventBus(false);
        bus.subscribe(CoreApplicationEvents.CodeUpdatedEvent.class, e -> lastCode = e.newCode());
        state.setCompilationUnit(com.botmaker.studio.parser.helpers.SourceParser.parse(SOURCE));
        CodeEditor editor = new CodeEditor(null, state, bus, new ProjectAnalyzer(null, state));

        TypeDeclaration subject = (TypeDeclaration) state.getCompilationUnit().orElseThrow().types().getFirst();
        MethodDeclaration run = subject.getMethods()[0];
        VariableDeclarationStatement count = (VariableDeclarationStatement) run.getBody().statements().getFirst();

        editor.replaceVariableType(count, ValueTypes.listOf(String.class));

        assertNotNull(lastCode, "a retype publishes a code update");
        assertTrue(lastCode.contains("List<String> count"), lastCode);
        assertTrue(lastCode.contains("import java.util.List;"), lastCode);
        // The old int's 3 is no value of a list: the initialiser is the new type's own fresh value.
        assertTrue(!lastCode.contains("= 3;"), lastCode);
    }
}
