package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.api.value.ComponentType;
import com.botmaker.studio.plugin.grammar.JavaExpressions;
import com.botmaker.studio.plugin.grammar.JavaNames;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot's managed constants, both ways: a reference read as the constant's value, and a value
 * written as the constant that holds it.
 */
class ManagedConstantsTest {

    record Picture(String path) {}

    static final ComponentType<Picture> PICTURE = new ComponentType<>() {
        @Override public Class<Picture> type() { return Picture.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(Picture p) { return List.of(p.path()); }
        @Override public Picture build(List<Object> parts) { return new Picture((String) parts.getFirst()); }
    };

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(List.of(), List.of(PICTURE));

    /** What a bound plugin declares: the set the class below is marked as. */
    private static final List<ManagedValue<?>> KNOWN = List.of(
            ManagedValue.openSet(TestValue.Id.PICTURES).of(Picture.class).in("Pictures").because("Mine."));

    private static final String PICTURES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.PICTURES)
            public final class Pictures {
                public static final Picture ORE = new %s("images/ore.png");
                public static final Picture GOLD = new %s("images/gold.png");
                static final Picture HIDDEN = new %s("images/hidden.png");
            }
            """.formatted(name(), name(), name());

    private static String name() {
        return JavaNames.canonical(Picture.class);
    }

    private static ManagedConstants.Lookup lookup() {
        return new ManagedConstants.Lookup(ManagedConstants.read(PICTURES, KNOWN), GRAMMAR);
    }

    @Test
    void onlyPublicStaticFinalFieldsOfAManagedTypeAreConstants() {
        assertEquals(List.of("ORE", "GOLD"),
                ManagedConstants.read(PICTURES, KNOWN).stream().map(ManagedConstants.Constant::field).toList());
    }

    @Test
    void aReferenceReadsAsTheConstantsValueQualifiedOrNot() {
        assertEquals(new Picture("images/ore.png"), lookup().read(name("Pictures.ORE")).orElseThrow());
        assertEquals(new Picture("images/ore.png"),
                lookup().read(name("com.bot.plugins.sdk.Pictures.ORE")).orElseThrow());
        assertTrue(lookup().read(name("Pictures.SILVER")).isEmpty());
    }

    /** The owner is resolved through the file's imports: another class called Pictures is another class. */
    @Test
    void aReferenceIsResolvedThroughItsFilesImports() {
        String own = "package com.bot;\nimport com.bot.plugins.sdk.Pictures;\n"
                     + "class Use { Object o = Pictures.ORE; }\n";
        String other = "package com.bot;\nimport com.other.Pictures;\nclass Use { Object o = Pictures.ORE; }\n";

        assertEquals(new Picture("images/ore.png"), lookup().read(fieldValue(own)).orElseThrow());
        assertTrue(lookup().read(fieldValue(other)).isEmpty());
    }

    private static QualifiedName name(String source) {
        return (QualifiedName) JavaExpressions.parse(source);
    }

    private static QualifiedName fieldValue(String source) {
        CompilationUnit unit = BotParser.syntax(source);
        TypeDeclaration type = (TypeDeclaration) unit.types().getFirst();
        VariableDeclarationFragment fragment =
                (VariableDeclarationFragment) type.getFields()[0].fragments().getFirst();
        return (QualifiedName) fragment.getInitializer();
    }

    /** What a reference to the picture {@code name} stands for, in a set referred to by name. */
    public static Picture referenced(String name) {
        return new Picture("→" + name);
    }

    /**
     * A set referred to by name: a reference reads as the plugin's value for the name, not as the initialiser,
     * and that value is written back as the same reference — even where two constants hold equal values.
     */
    @Test
    void aSetReferredToByNameReadsAndWritesEachReferenceByItsName() {
        List<ManagedValue<?>> known = List.of(ManagedValue.openSet(TestValue.Id.PICTURES).of(Picture.class)
                .referredToAs(ManagedConstantsTest::referenced).in("Pictures").because("Mine."));
        String twins = PICTURES.replace("images/gold.png", "images/ore.png");
        ManagedConstants.Lookup lookup = new ManagedConstants.Lookup(ManagedConstants.read(twins, known), GRAMMAR);

        assertEquals(new Picture("→GOLD"), lookup.read(name("Pictures.GOLD")).orElseThrow());
        assertEquals("Pictures.GOLD", lookup.spell(new Picture("→GOLD")).orElseThrow().source(),
                "GOLD, though ORE holds the same picture");
        assertEquals("Pictures.ORE", lookup.spell(new Picture("→ORE")).orElseThrow().source());
    }

    @Test
    void aValueEqualToAConstantIsWrittenAsTheConstant() {
        JavaValue written = lookup().spell(new Picture("images/gold.png")).orElseThrow();

        assertEquals("Pictures.GOLD", written.source());
        assertEquals(List.of("com.bot.plugins.sdk.Pictures"), written.imports());
        assertTrue(lookup().spell(new Picture("images/new.png")).isEmpty(), "no constant holds it");
    }

    /**
     * Two constants holding equal values are still two names: the one picked is the one written, and a reference
     * is read back as the one it names (2026-10-12, a fresh flow equal to {@code Flow.NONE}).
     */
    @Test
    void ofTwoEqualConstantsThePickedNameIsWrittenAndRead() {
        String twins = PICTURES.replace("static final Picture HIDDEN",
                "public static final Picture ALSO_GOLD = new " + name() + "(\"images/gold.png\");\n    static final Picture HIDDEN");
        ManagedConstants.Lookup lookup = new ManagedConstants.Lookup(ManagedConstants.read(twins, KNOWN), GRAMMAR);
        String id = KNOWN.getFirst().id();

        JavaValue written = ManagedConstants.Lookup.reference(lookup.member(id, "ALSO_GOLD").orElseThrow());

        assertEquals("Pictures.ALSO_GOLD", written.source());
        assertEquals(List.of("com.bot.plugins.sdk.Pictures"), written.imports());
        assertEquals("ALSO_GOLD", lookup.member(id, name("Pictures.ALSO_GOLD")).orElseThrow().memberName());
        assertTrue(lookup.member("another", name("Pictures.ALSO_GOLD")).isEmpty(), "another set's name is not its");
        assertTrue(lookup.member(id, "SILVER").isEmpty());
    }

    /**
     * A field that only holds a value with a name of its own ({@code EZF = Flow.NONE}) does not take that name
     * over: the value is still written as itself.
     */
    @Test
    void aFieldHoldingANamedValueDoesNotStandForIt() {
        String days = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.PICTURES)
                public final class Pictures {
                    public static final java.time.DayOfWeek FIRST = java.time.DayOfWeek.MONDAY;
                }
                """;
        ManagedConstants.Lookup lookup = new ManagedConstants.Lookup(ManagedConstants.read(days, KNOWN), GRAMMAR);

        assertTrue(lookup.spell(java.time.DayOfWeek.MONDAY).isEmpty());
    }

    /**
     * A marked enum's constants are what its set's plugin makes of each name, so a reference reads as that
     * value and the value is written back as the constant.
     */
    @Test
    void anEnumSetsConstantIsWhatItsPluginMakesOfItsName() {
        ComponentType<TestOutcome.Named> outcome = new ComponentType<>() {
            @Override public Class<TestOutcome.Named> type() { return TestOutcome.Named.class; }
            @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
            @Override public List<Object> components(TestOutcome.Named o) { return List.of(o.name()); }
            @Override public TestOutcome.Named build(List<Object> parts) {
                return new TestOutcome.Named((String) parts.getFirst());
            }
        };
        List<ManagedValue<?>> known = List.of(ManagedValue.openSet(TestValue.Id.OUTCOMES)
                .ofEnum(TestOutcome.class, TestOutcome::named).in("Outcomes").because("Mine."));
        String outcomes = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.OUTCOMES)
                public enum Outcomes implements TestOutcome { WON, LOST }
                """;
        ManagedConstants.Lookup lookup = new ManagedConstants.Lookup(ManagedConstants.read(outcomes, known),
                ValueGrammar.of(List.of(), List.of(outcome)));

        assertEquals(new TestOutcome.Named("LOST"), lookup.read(name("Outcomes.LOST")).orElseThrow());
        assertEquals("Outcomes.WON", lookup.spell(new TestOutcome.Named("WON")).orElseThrow().source());
        assertTrue(lookup.spell(new TestOutcome.Named("DRAW")).isEmpty(), "no constant is called that");
        assertEquals(List.of(), ManagedConstants.read(outcomes, KNOWN), "an enum no plugin declares holds nothing");
    }

    /** A value whose part is an outcome: {@code new Wire(Outcomes.WON)}. */
    record Wire(TestOutcome on) {}

    private static final ComponentType<TestOutcome.Named> NAMED_OUTCOME = new ComponentType<>() {
        @Override public Class<TestOutcome.Named> type() { return TestOutcome.Named.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(String.class); }
        @Override public List<Object> components(TestOutcome.Named o) { return List.of(o.name()); }
        @Override public TestOutcome.Named build(List<Object> parts) {
            return new TestOutcome.Named((String) parts.getFirst());
        }
    };

    private static final ComponentType<Wire> WIRE = new ComponentType<>() {
        @Override public Class<Wire> type() { return Wire.class; }
        @Override public List<Class<?>> componentTypes() { return List.of(TestOutcome.class); }
        @Override public List<Object> components(Wire w) { return List.of(w.on()); }
        @Override public Wire build(List<Object> parts) { return new Wire((TestOutcome) parts.getFirst()); }
    };

    @Test
    void aConstantInsideAPartReadsAsItsValueAndIsWrittenAsTheConstant() {
        List<ManagedValue<?>> known = List.of(ManagedValue.openSet(TestValue.Id.OUTCOMES)
                .ofEnum(TestOutcome.class, TestOutcome::named).in("Outcomes").because("Mine."));
        String outcomes = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.OUTCOMES)
                public enum Outcomes implements TestOutcome { WON, LOST }
                """;
        ValueGrammar grammar = ValueGrammar.of(List.of(), List.of(NAMED_OUTCOME, WIRE));
        List<ManagedConstants.Constant> constants = ManagedConstants.read(outcomes, known);
        String wire = "new " + JavaNames.canonical(Wire.class) + "(com.bot.plugins.sdk.Outcomes.LOST)";

        Object read = com.botmaker.studio.plugin.ConstantValues.read(grammar, () -> constants, Wire.class, wire)
                .orElseThrow();
        assertEquals(new Wire(new TestOutcome.Named("LOST")), read);
        String written = com.botmaker.studio.plugin.ConstantValues.write(grammar, () -> constants, Wire.class,
                new Wire(new TestOutcome.Named("WON"))).orElseThrow().source();
        assertTrue(written.endsWith("(Outcomes.WON)"), written);
    }

    /** A whole nested enum as an editor holds it. */
    record Group(String name) {}

    public static TestOutcome inGroup(String group, String name) {
        return new TestOutcome.Named(group + "." + name);
    }

    public static Group group(String name) {
        return new Group(name);
    }

    /** {@code Step.of(Activities.Collect.class, Activities.Collect.WON)}: a class literal and a nested constant. */
    record Step(Object activity, TestOutcome on) {

        /** What a bot calls, which only javac sees: an editor never holds the bot's class. */
        public static Step of(Class<?> activity, TestOutcome on) {
            return new Step(activity, on);
        }
    }

    private static final ComponentType<Step> STEP = new ComponentType<>() {
        @Override public Class<Step> type() { return Step.class; }
        @Override public java.lang.reflect.Executable factory() {
            try {
                return Step.class.getMethod("of", Class.class, TestOutcome.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public List<Class<?>> componentTypes() { return List.of(Class.class, TestOutcome.class); }
        @Override public List<Object> components(Step s) { return List.of(s.activity(), s.on()); }
        @Override public Step build(List<Object> parts) {
            return parts.get(0) instanceof Group g ? new Step(g, (TestOutcome) parts.get(1)) : null;
        }
    };

    @Test
    void aGroupedSetsEnumsAndTheirConstantsReadAndWriteAtAnyDepth() {
        List<ManagedValue<?>> known = List.of(ManagedValue.openSet(TestValue.Id.OUTCOMES)
                .ofEnumGroups(TestOutcome.class, ManagedConstantsTest::inGroup)
                .groupedAs(Group.class, ManagedConstantsTest::group)
                .in("Activities").because("Mine."));
        String activities = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.OUTCOMES)
                public final class Activities {
                    public enum Collect implements TestOutcome { NEXT, WON }
                    public enum Rest implements TestOutcome { NEXT }
                }
                """;
        List<ManagedConstants.Constant> constants = ManagedConstants.read(activities, known);
        assertEquals(5, constants.size(), "two enums and their three constants");
        ValueGrammar grammar = ValueGrammar.of(List.of(), List.of(NAMED_OUTCOME, STEP));
        String step = JavaNames.canonical(Step.class)
                + ".of(com.bot.plugins.sdk.Activities.Collect.class, com.bot.plugins.sdk.Activities.Collect.WON)";

        Object read = com.botmaker.studio.plugin.ConstantValues.read(grammar, () -> constants, Step.class, step)
                .orElseThrow();
        assertEquals(new Step(new Group("Collect"), new TestOutcome.Named("Collect.WON")), read);
        JavaValue written = com.botmaker.studio.plugin.ConstantValues.write(grammar, () -> constants, Step.class,
                new Step(new Group("Rest"), new TestOutcome.Named("Rest.NEXT"))).orElseThrow();
        assertTrue(written.source().endsWith("(Activities.Rest.class, Activities.Rest.NEXT)"), written.source());
        assertTrue(written.imports().contains("com.bot.plugins.sdk.Activities"), written.imports().toString());
    }

    /**
     * A constant whose value names the bot's own constants (a picture filed under {@code Activities.Tavern.class})
     * reads with them, and a value equal to it is written as that constant, not spelled out again (2026-10-15).
     */
    @Test
    void aConstantHoldingTheBotsNamesIsReadAndWrittenAsItself() {
        List<ManagedValue<?>> known = List.of(ManagedValue.openSet(TestValue.Id.OUTCOMES)
                        .ofEnumGroups(TestOutcome.class, ManagedConstantsTest::inGroup)
                        .groupedAs(Group.class, ManagedConstantsTest::group)
                        .in("Activities").because("Mine."),
                ManagedValue.openSet(TestValue.Id.FLOW).of(Step.class).in("Steps").because("Mine."));
        String activities = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.OUTCOMES)
                public final class Activities {
                    public enum Collect implements TestOutcome { NEXT, WON }
                }
                """;
        String steps = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.FLOW)
                public final class Steps {
                    public static final Step FIRST = %s.of(com.bot.plugins.sdk.Activities.Collect.class,
                            com.bot.plugins.sdk.Activities.Collect.WON);
                }
                """.formatted(JavaNames.canonical(Step.class));
        List<ManagedConstants.Constant> constants = new ArrayList<>(ManagedConstants.read(activities, known));
        constants.addAll(ManagedConstants.read(steps, known));
        ManagedConstants.Lookup lookup = new ManagedConstants.Lookup(constants,
                ValueGrammar.of(List.of(), List.of(NAMED_OUTCOME, STEP)));
        Step first = new Step(new Group("Collect"), new TestOutcome.Named("Collect.WON"));

        assertEquals(first, lookup.read(name("Steps.FIRST")).orElseThrow());
        assertEquals("Steps.FIRST", lookup.spell(first).orElseThrow().source());
    }

    /** An unchanged file is not parsed again; a file whose text changed is, and its new constant is found. */
    @Test
    void aScanParsesOnlyWhatChanged(@TempDir Path root) throws IOException {
        ProjectConfig config = ProjectConfig.forProject("MyFarmer", root);
        Path pkg = config.mainSourceFile().getParent();
        Files.createDirectories(pkg);
        Path pictures = pkg.resolve("Pictures.java");
        Files.writeString(pictures, PICTURES);

        assertEquals(2, ManagedConstants.scan(config, null, KNOWN).size());
        int afterFirst = ManagedConstants.parses();
        assertEquals(2, ManagedConstants.scan(config, null, KNOWN).size());
        assertEquals(afterFirst, ManagedConstants.parses(), "nothing changed, nothing parsed");

        Files.writeString(pictures, PICTURES.replace("static final Picture HIDDEN", "public static final Picture HIDDEN"));
        assertEquals(List.of("ORE", "GOLD", "HIDDEN"),
                ManagedConstants.scan(config, null, KNOWN).stream().map(ManagedConstants.Constant::field).toList());
        assertEquals(afterFirst + 1, ManagedConstants.parses(), "one file changed, one parsed");

        // A plugin bound since: the same text is read again, against what that plugin declares.
        assertEquals(List.of(), ManagedConstants.scan(config, null, List.of()));
        assertEquals(afterFirst + 2, ManagedConstants.parses(), "new declarations, parsed again");
    }
}
