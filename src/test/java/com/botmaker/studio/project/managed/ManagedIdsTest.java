package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's typed marker is read off a bot's source: through a binding when the unit has one, and through
 * its imports against the plugins' declarations when it has none — with the contract's id either way.
 */
class ManagedIdsTest {

    /** The test JVM's own classpath: it carries the contract and {@link TestValue}, as a bot's carries a plugin. */
    private static final BotParser BOUND = new BotParser(
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), null);

    private static final List<ManagedValue<?>> KNOWN = List.of(
            ManagedValue.method(TestValue.Id.GREETING).in("Values").holds(String.class, null).because("Mine."),
            ManagedValue.openSet(TestValue.Id.NAMES).of(String.class).in("Names").because("Mine."));

    private static final String GREETING = ManagedValue.idOf(TestValue.Id.GREETING);

    private static final String QUALIFIED = """
            package bot;

            import com.botmaker.studio.project.managed.TestValue;

            public final class Values {
                @TestValue(TestValue.Id.GREETING)
                public static String greeting() { return "hi"; }
            }
            """;

    private static List<BodyDeclaration> declarations(BotParser parser, String source) {
        CompilationUnit unit = parser.parse(null, source);
        List<BodyDeclaration> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration type) {
                out.add(type);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration method) {
                out.add(method);
                return false;
            }
        });
        return out;
    }

    private static String idOfLast(BotParser parser, String source, List<ManagedValue<?>> known) {
        BodyDeclaration declaration = declarations(parser, source).getLast();
        Annotation annotation = ManagedIds.on(declaration, known);
        return annotation == null ? null : ManagedIds.idOf(annotation, known);
    }

    @Test
    void aBindingSaysTheAnnotationIsAMarkerAndWhichConstantItHolds() {
        assertEquals("com.botmaker.studio.project.managed.TestValue$Id.GREETING", GREETING);
        // No declarations needed: the class itself says it is marked.
        assertEquals(GREETING, idOfLast(BOUND, QUALIFIED, List.of()));
    }

    @Test
    void withoutABindingItIsMatchedAgainstWhatThePluginsDeclare() {
        assertEquals(GREETING, idOfLast(BotParser.SYNTAX, QUALIFIED, KNOWN));
        assertNull(idOfLast(BotParser.SYNTAX, QUALIFIED, List.of()), "no plugin declares the marker");
    }

    @Test
    void aStaticallyImportedConstantReadsTheSame() {
        String source = """
                package bot;

                import com.botmaker.studio.project.managed.TestValue;
                import static com.botmaker.studio.project.managed.TestValue.Id.FAREWELL;

                public final class Values {
                    @TestValue(FAREWELL)
                    public static String farewell() { return "bye"; }
                }
                """;
        String farewell = ManagedValue.idOf(TestValue.Id.FAREWELL);
        assertEquals(farewell, idOfLast(BotParser.SYNTAX, source, KNOWN));
        assertEquals(farewell, idOfLast(BOUND, source, List.of()));
    }

    @Test
    void anOpenSetsMarkIsOnItsClass() {
        String source = """
                package bot;

                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.NAMES)
                public final class Names {
                    public static final String ANN = "Ann";
                }
                """;
        BodyDeclaration type = declarations(BotParser.SYNTAX, source).getFirst();
        assertEquals(ManagedValue.idOf(TestValue.Id.NAMES), ManagedIds.idOf(ManagedIds.on(type, KNOWN), KNOWN));
    }

    @Test
    void anAnnotationOfTheSameSimpleNameFromElsewhereIsNotTheMarker() {
        String source = QUALIFIED.replace("import com.botmaker.studio.project.managed.TestValue;",
                "import other.TestValue;");
        assertNull(idOfLast(BotParser.SYNTAX, source, KNOWN));
    }

    @Test
    void aTypedMarkWinsOverAStaleManagedBesideIt() {
        String source = QUALIFIED.replace("    @TestValue(", "    @com.botmaker.plugin.api.managed.Managed(\"old\")\n"
                + "    @TestValue(");
        assertEquals(GREETING, idOfLast(BotParser.SYNTAX, source, KNOWN));
        assertEquals(GREETING, idOfLast(BOUND, source, List.of()));
    }

    @Test
    void aStringIdIsStillRead() {
        String source = """
                package bot;

                import com.botmaker.plugin.api.managed.Managed;

                public final class Values {
                    @Managed("greeting")
                    public static String greeting() { return "hi"; }
                }
                """;
        assertEquals("greeting", idOfLast(BotParser.SYNTAX, source, KNOWN));
    }

    @Test
    void aMarkIsSpelledTheWayABotWritesIt() {
        assertEquals("@TestValue(TestValue.Id.GREETING)", ManagedIds.spelled(GREETING, KNOWN));
        assertEquals("@Managed(\"flow\")", ManagedIds.spelled("flow", KNOWN));
        // A typed id no plugin in hand declares still says its own shape.
        assertEquals("@SdkValue(SdkValue.Id.FLOW)", ManagedIds.spelled("com.botmaker.sdk.api.bot.SdkValue$Id.FLOW",
                List.of()));
    }

    @Test
    void aMarkersClassBindingIsRecognised() {
        CompilationUnit unit = BOUND.parse(null, QUALIFIED);
        List<Annotation> found = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                for (Object each : method.modifiers()) if (each instanceof Annotation a) found.add(a);
                return false;
            }
        });
        assertNotNull(found.getFirst().resolveAnnotationBinding());
        assertTrue(ManagedIds.isMarker(found.getFirst().resolveAnnotationBinding().getAnnotationType()));
    }
}
