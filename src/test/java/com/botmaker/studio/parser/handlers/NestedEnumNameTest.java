package com.botmaker.studio.parser.handlers;

import com.botmaker.studio.types.ResolvedType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An activity's outcomes are an enum nested in {@code Activities} ({@code Activities.Collect}), and the activity's
 * own class is often {@code Collect} too: a constant written by the simple name, {@code Collect.NEXT}, names the
 * class, not the enum.
 */
class NestedEnumNameTest {

    private static final String SOURCE = """
            package com.mybot;
            public class Activities {
                public enum Collect { NEXT, NOTHING_LEFT }
                public static Collect body() { return Collect.NEXT; }
            }
            """;

    @Test
    void aNestedEnumIsNamedThroughItsOuterClass() {
        ITypeBinding returned = returnTypeOfBody();
        assertTrue(returned.isEnum());

        assertEquals("Activities.Collect", EnumManipulationHandler.referableName(ResolvedType.of(returned)));
        assertEquals(java.util.List.of("NEXT", "NOTHING_LEFT"), ResolvedType.of(returned).enumConstants(),
                "a return slot typed by the binding offers the outcomes");
        assertEquals("Collect", EnumManipulationHandler.referableName(ResolvedType.named("Collect")));
    }

    private static ITypeBinding returnTypeOfBody() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(SOURCE.toCharArray());
        parser.setUnitName("Activities.java");
        parser.setEnvironment(null, null, null, true);
        parser.setResolveBindings(true);
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        CompilationUnit cu = (CompilationUnit) parser.createAST(null);
        TypeDeclaration activities = (TypeDeclaration) cu.types().getFirst();
        MethodDeclaration body = activities.getMethods()[0];
        return body.getReturnType2().resolveBinding();
    }
}
