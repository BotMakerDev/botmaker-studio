package com.botmaker.studio.project;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.project.LockResolver.EditKind;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The truth table for {@link LockResolver} — which is now two rows long, and it is worth recording what the
 * other rows were.
 *
 * <p>This used to be {@link FileRole} × {@code MethodLock} × {@link EditKind}: a generated file whose one
 * granted method kept an editable body, an activity's {@code isEnabled()} locked inside a file the user
 * otherwise owned, a flow driver locked wholesale. All of it described code BotMaker wrote and rewrote, and
 * it wrote none between 2026-08-29 and 2026-09-20. Two of the verdicts here were never about generation at
 * all — a bot opened for <em>reading</em>, and bundled library source — and the third is back, because a
 * plugin's model is compiled code again. What came back is the <em>file</em>, whole: there is no granted
 * method inside a generated file and no locked member inside an editable one.
 */
class LockResolverTest {

    private static final ProjectConfig CONFIG =
            ProjectConfig.forProject("MyBot", Paths.get("/tmp/projects"));

    private static Path inMainPackage(String fileName) {
        return CONFIG.mainSourceFile().getParent().resolve(fileName);
    }

    private static final Path HELPER = inMainPackage("MyHelper.java");
    private static final Path FLOW_DRIVER = inMainPackage("FlowDriver.java");
    private static final Path LIBRARY_FILE =
            Paths.get("/tmp/projects/MyBot/src/main/java/com/botmaker/library/Helper.java");

    private static final String SOURCE = """
            package com.mybot;
            public class FlowDriver {
                private int field = 1;
                public static void run() { System.out.println("hi"); }
                public void helper() { System.out.println("mine"); }
            }
            """;

    private static CompilationUnit parse() {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(SOURCE.toCharArray());
        return (CompilationUnit) parser.createAST(null);
    }

    private static final CompilationUnit CU = parse();

    private static TypeDeclaration type() {
        return (TypeDeclaration) CU.types().getFirst();
    }

    private static MethodDeclaration method(String name) {
        for (MethodDeclaration m : type().getMethods()) {
            if (m.getName().getIdentifier().equals(name)) return m;
        }
        throw new AssertionError("no method " + name);
    }

    /** The statement inside {@code name}'s body — a node deep enough to exercise the ancestor walk. */
    private static Statement statementIn(String name) {
        return (Statement) method(name).getBody().statements().getFirst();
    }

    private static LockResolver resolver(Path file) {
        return new LockResolver(CONFIG, file);
    }

    // --- reader mode: outranks the file's own verdict -----------------------------------------------------

    @Test
    void readerModeDeniesEvenAUsersOwnHelperInAnEditableFile() {
        // A helper file is EDITABLE and its body is normally the user's…
        LockResolver editable = new LockResolver(CONFIG, HELPER, false);
        assertTrue(editable.permits(statementIn("helper"), EditKind.BODY), "precondition: editable when editing");

        // …but opened for reading, the same edit is refused with the reader message.
        LockResolver reader = new LockResolver(CONFIG, HELPER, true);
        LockResolver.Verdict v = reader.check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertEquals(LockResolver.READER_MODE_REASON, v.reason());
        assertTrue(reader.suppressesInteraction(), "reader mode suppresses interaction across the file");
    }

    // --- the project's own files: all of them the user's ---------------------------------------------------

    /**
     * {@code FlowDriver.java} is the strongest case: BotMaker wrote it in every game bot for a year, and every
     * line of it was locked. In a project that still has one it is now ordinary code the user may change or
     * delete, because nothing will write over their change.
     */
    @Test
    void aFileBotMakerUsedToGenerateIsFullyEditable() {
        LockResolver r = resolver(FLOW_DRIVER);
        assertEquals(FileRole.EDITABLE, r.role());
        assertTrue(r.permits(statementIn("run"), EditKind.BODY));
        assertTrue(r.permits(method("run"), EditKind.SIGNATURE));
        assertTrue(r.permits(type(), EditKind.SIGNATURE), "the class header too");
    }

    @Test
    void anOrdinaryUserFileIsFullyEditable() {
        LockResolver r = resolver(HELPER);
        assertTrue(r.permits(statementIn("helper"), EditKind.BODY));
        assertTrue(r.permits(method("helper"), EditKind.SIGNATURE));
        assertFalse(r.suppressesInteraction());
    }

    // --- library source: not the project's at all ----------------------------------------------------------

    @Test
    void libraryCodeRejectsEverything() {
        LockResolver r = resolver(LIBRARY_FILE);
        assertEquals(FileRole.LIBRARY, r.role());
        assertFalse(r.permits(statementIn("helper"), EditKind.BODY));
        assertFalse(r.permits(method("helper"), EditKind.SIGNATURE));
        assertTrue(r.suppressesInteraction());
        assertTrue(r.check(method("helper"), EditKind.SIGNATURE).reason().contains("library"));
    }

    // --- a plugin's own file: the user's, one method excepted ---------------------------------------------

    /**
     * The file a plugin ships is ordinary user code. It was {@code FileRole.GENERATED} for one day, from the
     * withdrawn design where the host wrote it whole; what is refused now is one {@code @Managed} body, by
     * the annotation rule below, and everything around it in the same file stays editable.
     */
    @Test
    void aPluginsOwnFileIsEditableLikeAnyOther() {
        LockResolver r = resolver(inMainPackage("plugins/sdk/Sdk.java"));
        assertEquals(FileRole.EDITABLE, r.role());
        assertTrue(r.permits(statementIn("helper"), EditKind.BODY));
        assertTrue(r.permits(method("helper"), EditKind.SIGNATURE));
        assertFalse(r.suppressesInteraction());
    }

    // --- the two escape hatches ---------------------------------------------------------------------------

    @Test
    void noConfigMeansEverythingIsEditable() {
        LockResolver none = new LockResolver(null, null);
        assertTrue(none.permits(statementIn("run"), EditKind.BODY));
        assertTrue(none.permits(method("run"), EditKind.SIGNATURE));
    }

    @Test
    void aMissingTargetIsDeniedNotWavedThrough() {
        LockResolver.Verdict v = resolver(HELPER).check(null, EditKind.BODY);
        assertFalse(v.allowed(), "a caller that forgot to say what it was editing fails loudly");
        assertNotNull(v.reason());
    }

    @Test
    void aRefusalAlwaysCarriesAReasonToShowTheUser() {
        LockResolver.Verdict v = resolver(LIBRARY_FILE).check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertNotNull(v.reason());
        assertFalse(v.reason().isBlank());
    }

    // --- files and fields another window owns (2026-09-19) --------------------------------------------------

    private static final List<ManagedValue> PICTURES = List.of(
            new ManagedValue("pictures", "Change pictures in the picture window."),
            new ManagedValue("flow", "Draw the flow in ✂ Activity Flow."));

    private static TypeDeclaration typeOf(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        return (TypeDeclaration) ((CompilationUnit) parser.createAST(null)).types().getFirst();
    }

    @Test
    void everyEditInTheParametersFileIsRefusedWithWhereToGo() {
        LockResolver resolver = resolver(CONFIG.parametersSourceFile());

        LockResolver.Verdict v = resolver.check(statementIn("helper"), EditKind.BODY);
        assertFalse(v.allowed());
        assertTrue(v.reason().contains("Project ▸ Parameters"), v.reason());
        assertTrue(resolver.suppressesInteraction());
        assertTrue(resolver(HELPER).permits(statementIn("helper"), EditKind.BODY));
    }

    @Test
    void aParamFieldIsTheParametersWindowsWhereverItIs() {
        TypeDeclaration type = typeOf("""
                package com.mybot;
                import com.botmaker.plugin.basics.params.Param;
                public class Tuning {
                    @Param public static int attempts = 3;
                    public static int counter = 0;
                }
                """);

        assertEquals(LockResolver.PARAM_REASON, LockResolver.managedReason(type.getFields()[0], List.of()));
        assertNull(LockResolver.managedReason(type.getFields()[1], List.of()));
    }

    @Test
    void anAnnotatedClassIsManagedWhole() {
        TypeDeclaration type = typeOf("""
                package com.mybot;
                import com.botmaker.plugin.basics.managed.Managed;
                import com.example.vision.Picture;
                @Managed("pictures")
                public final class Pictures {
                    public static final Picture COLLECT = new Picture("collect.png");
                    private Pictures() {}
                }
                """);

        assertEquals(PICTURES.getFirst().reason(), LockResolver.managedReason(type.getFields()[0], PICTURES));
        // The constructor is not a picture, and is refused anyway: the class is the plugin's window's whole.
        assertEquals(PICTURES.getFirst().reason(), LockResolver.managedReason(type.getMethods()[0], PICTURES));
        // No plugin claiming the id: nothing is locked, which is every project before this rule.
        assertNull(LockResolver.managedReason(type.getFields()[0], List.of()));
    }

    @Test
    void anUnannotatedClassOfTheSameConstantsIsOrdinaryCode() {
        TypeDeclaration type = typeOf("""
                package com.mybot;
                import com.example.vision.Picture;
                final class Pictures {
                    static final Picture COLLECT = new Picture("collect.png");
                    static final Picture BATTLE = new Picture("battle.png");
                }
                """);

        // The type-match heuristic locked this file and every one shaped like it. An annotation is a
        // statement, and nobody made one here.
        assertNull(LockResolver.managedReason(type.getFields()[0], PICTURES));
    }

    @Test
    void anAnnotatedMethodIsLockedAndItsNeighboursAreNot() {
        TypeDeclaration type = typeOf("""
                package com.mybot.plugins.sdk;
                import com.botmaker.plugin.basics.managed.Managed;
                public final class Sdk {
                    @Managed("flow")
                    public static Flow flow() { return Flow.of(); }
                    public static void install() { Flows.use(flow()); }
                }
                """);

        assertEquals("Draw the flow in ✂ Activity Flow.",
                LockResolver.managedReason(type.getMethods()[0], PICTURES));
        // install() is the plugin's hand-off and the user's to call or not: the class is not annotated, so
        // only the one method the plugin writes is refused.
        assertNull(LockResolver.managedReason(type.getMethods()[1], PICTURES));
    }

    @Test
    void anIdNoPluginClaimsIsOrdinaryCode() {
        TypeDeclaration type = typeOf("""
                package com.mybot;
                import com.botmaker.plugin.basics.managed.Managed;
                public final class Mine {
                    @Managed("something-of-my-own")
                    public static int value() { return 3; }
                }
                """);

        assertNull(LockResolver.managedReason(type.getMethods()[0], PICTURES));
        assertNotNull(LockResolver.managedReason(type.getMethods()[0],
                List.of(new ManagedValue("something-of-my-own", "Mine."))));
    }
}
