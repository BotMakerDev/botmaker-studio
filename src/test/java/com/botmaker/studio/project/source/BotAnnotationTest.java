package com.botmaker.studio.project.source;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code @Param} and {@code @Managed} are identified by class: through a binding when the unit has one,
 * through the unit's imports when it has none — never by a name that ends in {@code Param}.
 */
class BotAnnotationTest {

    /** The test JVM's own classpath: it carries the contract, which is what a bot's classpath carries. */
    private static final BotParser BOUND = new BotParser(
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), null);

    private static List<Annotation> annotations(BotParser parser, String source) {
        CompilationUnit unit = parser.parse(null, source);
        List<Annotation> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration field) {
                for (Object modifier : field.modifiers()) if (modifier instanceof Annotation a) out.add(a);
                return false;
            }
        });
        return out;
    }

    private static Annotation only(BotParser parser, String source) {
        List<Annotation> found = annotations(parser, source);
        assertEquals(1, found.size(), found.toString());
        return found.getFirst();
    }

    @Test
    void anotherPackagesParamIsNotTheContracts() {
        // The suffix match took this for the contract's annotation.
        Annotation annotation = only(BotParser.SYNTAX, """
                package com.example.bot;
                import com.example.other.Param;
                class Parameters { @Param public static int a = 1; }
                """);
        assertEquals(false, BotAnnotation.PARAM.marks(annotation));
    }

    @Test
    void aBotsOwnParamInItsOwnPackageIsNotTheContracts() {
        Annotation annotation = only(BotParser.SYNTAX, """
                package com.example.bot;
                class Parameters { @Param public static int a = 1; }
                """);
        assertEquals(false, BotAnnotation.PARAM.marks(annotation));
    }

    @Test
    void theContractsTheOldBasicsOneAndAQualifiedUseAllAre() {
        for (String header : List.of("import com.botmaker.plugin.api.params.Param;",
                "import com.botmaker.plugin.basics.params.Param;",
                "import com.botmaker.plugin.api.params.*;")) {
            Annotation annotation = only(BotParser.SYNTAX, """
                    package com.example.bot;
                    %s
                    class Parameters { @Param public static int a = 1; }
                    """.formatted(header));
            assertEquals(true, BotAnnotation.PARAM.marks(annotation), header);
        }
        Annotation qualified = only(BotParser.SYNTAX, """
                package com.example.bot;
                class Parameters { @com.botmaker.plugin.api.params.Param public static int a = 1; }
                """);
        assertEquals(true, BotAnnotation.PARAM.marks(qualified));
    }

    @Test
    void withoutBindingsTheAnnotationsOwnConstantsAndLiteralsAreRead() {
        Annotation annotation = only(BotParser.SYNTAX, """
                package com.example.bot;
                import com.botmaker.plugin.api.params.Param;
                class Parameters {
                    @Param(visibility = Param.PUBLIC, min = -1, max = 2.5, category = "Limits", options = {"a", "b"})
                    public static int a = 1;
                }
                """);
        Map<String, Object> members = BotAnnotation.PARAM.members(annotation);
        assertEquals("public", members.get("visibility"));
        assertEquals(-1.0, members.get("min"));
        assertEquals(2.5, members.get("max"));
        assertEquals("Limits", members.get("category"));
        assertEquals(List.of("a", "b"), members.get("options"));
    }

    @Test
    void withBindingsAConstantOfTheBotsOwnIsReadAsItsValue() {
        Annotation annotation = only(BOUND, """
                package com.example.bot;
                import com.botmaker.plugin.api.params.Param;
                class Parameters {
                    static final String LIMITS = "Limits";
                    @Param(category = LIMITS, min = 1)
                    public static int a = 1;
                }
                """);
        assertNotNull(annotation.resolveAnnotationBinding(), "the contract is on the test classpath");
        assertEquals(true, BotAnnotation.PARAM.marks(annotation));
        Map<String, Object> members = BotAnnotation.PARAM.members(annotation);
        assertEquals("Limits", members.get("category"));
        assertEquals(1.0, members.get("min"));
    }

    @Test
    void withBindingsAnUnresolvedContractFallsBackToTheImports() {
        // A classpath with no contract on it: the binding is recovered, the import still decides.
        BotParser empty = new BotParser(List.of(), null);
        Annotation annotation = only(empty, """
                package com.example.bot;
                import com.botmaker.plugin.api.params.Param;
                class Parameters { @Param(category = "X") public static int a = 1; }
                """);
        assertEquals(true, BotAnnotation.PARAM.marks(annotation));
        assertEquals("X", BotAnnotation.PARAM.members(annotation).get("category"));
    }

    @Test
    void managedIsNotParam() {
        Annotation annotation = only(BotParser.SYNTAX, """
                package com.example.bot;
                import com.botmaker.plugin.api.managed.Managed;
                class Sdk { @Managed("flow") static int a = 1; }
                """);
        assertEquals(true, BotAnnotation.MANAGED.marks(annotation));
        assertEquals(false, BotAnnotation.PARAM.marks(annotation));
        assertNull(BotAnnotation.PARAM.on(List.of(annotation)));
    }
}
