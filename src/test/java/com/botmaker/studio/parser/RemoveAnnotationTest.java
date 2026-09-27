package com.botmaker.studio.parser;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The × on an annotation pill: the annotation goes, and nothing else — except a {@code @Refactor}, whose import
 * goes with the file's last one.
 */
class RemoveAnnotationTest {

    private static MethodDeclaration method(EditorFixture fx, String name) {
        CompilationUnit unit = fx.state.getCompilationUnit().orElseThrow();
        for (MethodDeclaration method : ((TypeDeclaration) unit.types().getFirst()).getMethods()) {
            if (method.getName().getIdentifier().equals(name)) return method;
        }
        throw new AssertionError("no method " + name);
    }

    private static Annotation first(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation annotation) return annotation;
        }
        throw new AssertionError("no annotation on " + method.getName());
    }

    @Test
    void aUsersAnnotationIsRemovedAlone() {
        EditorFixture fx = new EditorFixture("""
                package test;

                public class Subject {
                    @SuppressWarnings("unused")
                    public void run() {
                        int x = 1;
                    }
                }
                """);
        MethodDeclaration run = method(fx, "run");

        fx.editor.removeAnnotation(run, first(run));

        assertFalse(fx.lastCode.contains("@SuppressWarnings"), fx.lastCode);
        assertTrue(fx.lastCode.contains("public void run()") && fx.lastCode.contains("int x = 1;"), fx.lastCode);
    }

    @Test
    void theLastRefactorTakesItsImportWithIt() {
        EditorFixture fx = new EditorFixture("""
                package test;

                import com.botmaker.plugin.api.meta.Refactor;

                public class Subject {
                    @Refactor(value = "reviewed long ago", done = true)
                    public void run() {
                        int x = 1;
                    }
                }
                """);
        MethodDeclaration run = method(fx, "run");

        fx.editor.removeAnnotation(run, first(run));

        assertFalse(fx.lastCode.contains("Refactor"), fx.lastCode);
    }
}
