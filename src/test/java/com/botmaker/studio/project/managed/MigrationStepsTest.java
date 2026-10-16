package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationSteps.Choice;
import com.botmaker.studio.project.managed.MigrationSteps.Shape;
import com.botmaker.studio.project.managed.MigrationSteps.Step;
import com.botmaker.studio.project.managed.MigrationTypes.Area;
import com.botmaker.studio.project.managed.MigrationTypes.Badge;
import com.botmaker.studio.project.managed.MigrationTypes.Frame;
import com.botmaker.studio.project.managed.MigrationTypes.OldArea;
import com.botmaker.studio.project.managed.MigrationTypes.OldBadge;
import com.botmaker.studio.project.managed.MigrationTypes.OldFrame;
import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.OldTint;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import com.botmaker.studio.project.managed.MigrationTypes.Tint;
import com.botmaker.studio.project.source.BotIndex;
import org.eclipse.jdt.core.dom.AST;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migration window's steps: a pair's parts matched by name and type, written as a {@code @Migrate} method the
 * host can run, read back into the same steps, and applied with the migration as one change that compiles.
 */
@SuppressWarnings("removal")
class MigrationStepsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(MigrationTypes.types(), List.of());

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.openSet(TestValue.Id.OLD_SHOTS).of(OldShot.class).in("OldShots").because("Old."),
            ManagedValue.openSet(TestValue.Id.SHOTS).of(Shot.class).in("Shots").because("New."),
            ManagedValue.openSet(TestValue.Id.OLD_FRAMES).of(OldFrame.class).in("OldFrames").because("Old."),
            ManagedValue.openSet(TestValue.Id.FRAMES).of(Frame.class).in("Frames").because("New."));

    private static final String OLD_SHOTS = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.OldShot;

            @TestValue(TestValue.Id.OLD_SHOTS)
            public final class OldShots {
                public static final OldShot ORE = new OldShot("ore.png", 80);
                public static final OldShot GOLD = new OldShot("gold.png", 90);

                private OldShots() {}
            }
            """;

    private static final String SHOTS = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.Shot;

            @TestValue(TestValue.Id.SHOTS)
            public final class Shots {
                private Shots() {}
            }
            """;

    private static final String OLD_FRAMES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.OldArea;
            import com.botmaker.studio.project.managed.MigrationTypes.OldFrame;

            @TestValue(TestValue.Id.OLD_FRAMES)
            public final class OldFrames {
                public static final OldFrame DOOR = new OldFrame("door.png", new OldArea(3, 4));

                private OldFrames() {}
            }
            """;

    private static final String FRAMES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.Frame;

            @TestValue(TestValue.Id.FRAMES)
            public final class Frames {
                private Frames() {}
            }
            """;

    @Test
    void partsAreMatchedByNameThenTypeThenStartingValue() {
        Shape.Parts shape = assertInstanceOf(Shape.Parts.class, MigrationSteps.shape(GRAMMAR, OldShot.class, Shot.class));
        assertEquals(List.of("file", "threshold"), shape.from().stream().map(MigrationSteps.Part::label).toList());
        assertEquals(List.of("file", "threshold", "grey"), shape.to().stream().map(MigrationSteps.Part::label).toList());

        List<Choice> matched = MigrationSteps.matched(GRAMMAR, shape);
        assertEquals(new Choice.From(0), matched.get(0));
        assertEquals(new Choice.From(1), matched.get(1));
        assertEquals("false", assertInstanceOf(Choice.Fixed.class, matched.get(2)).value().source());
        assertNull(MigrationSteps.problem(shape, matched));
    }

    @Test
    void aPartOfTheSameNameIsNeverTakenEarlierByItsTypeAlone() {
        Shape.Parts shape = assertInstanceOf(Shape.Parts.class,
                MigrationSteps.shape(GRAMMAR, MigrationTypes.OldLabel.class, MigrationTypes.Label.class));
        List<Choice> matched = MigrationSteps.matched(GRAMMAR, shape);

        assertEquals(new Choice.From(0), matched.get(1), "name from name");
        assertEquals("\"\"", assertInstanceOf(Choice.Fixed.class, matched.get(0)).value().source(),
                "title starts empty rather than taking name twice");
    }

    @Test
    void aPartOfAnotherTypeIsTakenThroughItsOwnStep() {
        Shape.Parts shape = assertInstanceOf(Shape.Parts.class,
                MigrationSteps.shape(GRAMMAR, OldFrame.class, Frame.class));
        List<Choice> matched = MigrationSteps.matched(GRAMMAR, shape);

        assertEquals(List.of(new Choice.From(0), new Choice.From(1)), matched);
        assertEquals(List.of(Map.entry(OldArea.class, Area.class)), MigrationSteps.nested(shape, matched));
    }

    @Test
    void aPartIsBuiltByItsOwnOnePartFactory() {
        Shape.Parts shape = assertInstanceOf(Shape.Parts.class,
                MigrationSteps.shape(GRAMMAR, OldBadge.class, Badge.class));
        assertEquals(List.of(new Choice.Built(0)), MigrationSteps.offered(GRAMMAR, shape, 0));
        List<Choice> matched = MigrationSteps.matched(GRAMMAR, shape);
        assertEquals(List.of(new Choice.Built(0)), matched);

        String written = written(new Step.Calls(OldBadge.class, Badge.class, matched));
        assertTrue(flat(written).contains("public static MigrationTypes.Badge badge(String name)"
                + "{return new MigrationTypes.Badge(new MigrationTypes.Tag(name));}"), written);
    }

    @Test
    void anEnumPairsItsConstantsAndReadsBack() {
        Shape.Constants shape = assertInstanceOf(Shape.Constants.class,
                MigrationSteps.shape(GRAMMAR, OldTint.class, Tint.class));
        Map<String, String> arms = MigrationSteps.matched(shape);
        assertEquals(Map.of("RED", "CRIMSON", "BLUE", "NAVY"), arms, "by place, no name being shared");

        String written = written(new Step.Arms(OldTint.class, Tint.class, arms));
        assertTrue(flat(written).contains("case RED -> MigrationTypes.Tint.CRIMSON;"), written);
    }

    @Test
    void anEnumDoesNotMigrateToACall() {
        assertInstanceOf(Shape.Refused.class, MigrationSteps.shape(GRAMMAR, OldTint.class, Shot.class));
    }

    @Test
    void applyingWritesTheMethodsAndCopiesTheValues(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldFrames"), OLD_FRAMES,
                file(root, "plugins/sdk/Frames"), FRAMES));
        Shape.Parts frame = (Shape.Parts) MigrationSteps.shape(GRAMMAR, OldFrame.class, Frame.class);
        Shape.Parts area = (Shape.Parts) MigrationSteps.shape(GRAMMAR, OldArea.class, Area.class);
        List<Step> steps = List.of(
                new Step.Calls(OldFrame.class, Frame.class, MigrationSteps.matched(GRAMMAR, frame)),
                new Step.Calls(OldArea.class, Area.class, MigrationSteps.matched(GRAMMAR, area)));

        MigrationSteps.Draft draft = MigrationSteps.plan(index, GRAMMAR, DECLARED, file(root, "Migrations"), "com.bot",
                steps, OldFrame.class, Frame.class);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, draft.outcome(), draft.outcome()::toString);
        assertEquals(java.util.Set.of(file(root, "Migrations"), file(root, "plugins/sdk/Frames")),
                plan.rewrites().keySet(), "the originals are never touched");
        String migrations = plan.rewrites().get(file(root, "Migrations"));
        assertTrue(migrations.startsWith("package com.bot;"), migrations);
        // The test grammar spells a nested class by its outer one: MigrationTypes.Frame.
        assertTrue(flat(migrations).contains("@Migrate(MigrationTypes.OldFrame.class) public static MigrationTypes.Frame"
                + " frame(String file, MigrationTypes.Area area){return new MigrationTypes.Frame(file, area);}"),
                migrations);
        assertTrue(flat(migrations).contains("@Migrate(MigrationTypes.OldArea.class) public static MigrationTypes.Area"
                + " area(int x, int y){return new MigrationTypes.Area(x, y, 0);}"), migrations);
        MigrationSteps.Row row = draft.rows().getFirst();
        assertEquals("OldFrames.DOOR", row.name());
        assertEquals("new OldFrame(\"door.png\", new OldArea(3, 4))", row.before());
        assertTrue(flat(row.after()).contains("Frame(\"door.png\", new MigrationTypes.Area(3, 4, 0))"), row.after());
    }

    @Test
    void theAssistantsLinesPlanAsTheWindowsPicksDo(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldFrames"), OLD_FRAMES,
                file(root, "plugins/sdk/Frames"), FRAMES));
        MigrationMapping.Asked asked = MigrationMapping.steps(GRAMMAR, ManagedMigrations.methods(index, GRAMMAR),
                OldFrame.class, Frame.class, "Area.w: fixed 7");
        MigrationSteps.Draft draft = MigrationSteps.plan(index, GRAMMAR, DECLARED, file(root, "Migrations"), "com.bot",
                asked.steps(), OldFrame.class, Frame.class);

        assertInstanceOf(Refactor.Planned.class, draft.outcome(), draft.outcome()::toString);
        assertTrue(flat(draft.rows().getFirst().after()).contains("new MigrationTypes.Area(3, 4, 7)"),
                draft.rows().getFirst().after());
    }

    @Test
    void aMethodTheWindowWroteReadsBackAsItsSteps(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), SHOTS));
        Shape.Parts shape = (Shape.Parts) MigrationSteps.shape(GRAMMAR, OldShot.class, Shot.class);
        Step step = new Step.Calls(OldShot.class, Shot.class, MigrationSteps.matched(GRAMMAR, shape));
        Refactor.Planned plan = (Refactor.Planned) MigrationSteps.plan(index, GRAMMAR, DECLARED,
                file(root, "Migrations"), "com.bot", List.of(step), OldShot.class, Shot.class).outcome();

        Map<Path, String> after = new LinkedHashMap<>(index.sources());
        after.putAll(plan.rewrites());
        BotIndex migrated = index.replacing(after);
        ManagedMigrations.Method method = ManagedMigrations.methods(migrated, GRAMMAR).getFirst();
        assertEquals(step, MigrationSteps.read(GRAMMAR, method).orElseThrow());

        // Applying again replaces the method in place rather than adding a second.
        Step changed = new Step.Calls(OldShot.class, Shot.class, List.of(new Choice.From(0), new Choice.From(1),
                new Choice.Fixed(GRAMMAR.spell(boolean.class, true).orElseThrow())));
        Refactor.Planned again = assertInstanceOf(Refactor.Planned.class, MigrationSteps.plan(migrated, GRAMMAR,
                DECLARED, file(root, "Migrations"), "com.bot", List.of(changed), OldShot.class, Shot.class).outcome());
        String migrations = again.rewrites().get(file(root, "Migrations"));
        assertEquals(1, migrations.split("@Migrate\\(", -1).length - 1, migrations);
        assertTrue(migrations.contains("return new MigrationTypes.Shot(file, threshold, true);"), migrations);

        // Two methods mapping the pair: the migration refuses both, so applying asks for one to go.
        String twins = after.get(file(root, "Migrations")).replace("public static MigrationTypes.Shot shot(",
                "public static MigrationTypes.Shot shot2(String file, int threshold) {\n"
                        + "        return new MigrationTypes.Shot(file, threshold, false);\n    }\n\n"
                        + "    @Migrate(MigrationTypes.OldShot.class)\n    public static MigrationTypes.Shot shot(");
        Map<Path, String> doubled = new LinkedHashMap<>(after);
        doubled.put(file(root, "Migrations"), twins);
        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class, MigrationSteps.plan(
                index.replacing(doubled), GRAMMAR, DECLARED, file(root, "Migrations"), "com.bot", List.of(changed),
                OldShot.class, Shot.class).outcome());
        assertTrue(refused.reason().contains("both migrate OldShot to Shot"), refused.reason());
    }

    @Test
    void aHolderTheBotLacksIsCreatedWithTheMigration(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS));
        assertEquals(Shot.class, MigrationSteps.missingHolder(index, DECLARED, Shot.class).orElseThrow().type());
        Shape.Parts shape = (Shape.Parts) MigrationSteps.shape(GRAMMAR, OldShot.class, Shot.class);
        Step step = new Step.Calls(OldShot.class, Shot.class, MigrationSteps.matched(GRAMMAR, shape));

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, MigrationSteps.plan(index, GRAMMAR, DECLARED,
                file(root, "Migrations"), "com.bot", List.of(step), OldShot.class, Shot.class,
                Map.of(file(root, "plugins/sdk/Shots"), SHOTS)).outcome());
        assertEquals(java.util.Set.of(file(root, "Migrations"), file(root, "plugins/sdk/Shots")),
                plan.rewrites().keySet());
        assertTrue(plan.rewrites().get(file(root, "plugins/sdk/Shots")).contains("GOLD"),
                "the created holder holds the copies");
    }

    @Test
    void theBotsRetiredTypesArePendingWithNoTargetPicked(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), SHOTS));
        assertEquals(List.of(new MigrationSteps.Pending(OldShot.class, null, 2, 0)),
                MigrationSteps.pending(index, GRAMMAR, DECLARED), "the author picks the target, no plugin does");
        assertEquals(List.of(Shot.class, Frame.class), MigrationSteps.targets(GRAMMAR, DECLARED, OldShot.class),
                "any pair of calls, the retired left out");
    }

    @Test
    void aSetTheNextVersionDropsIsStrandedWithItsValues(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), SHOTS));
        List<ManagedValue<?>> next = List.of(DECLARED.get(1), DECLARED.get(3));

        assertEquals(List.of(new MigrationSteps.Stranded(DECLARED.getFirst().id(), OldShot.class, null, 2,
                        true)),
                MigrationSteps.stranded(index, GRAMMAR, DECLARED, DECLARED, next),
                "OldFrames holds nothing; Shots none yet");
        assertEquals(List.of(), MigrationSteps.stranded(index, GRAMMAR, DECLARED, DECLARED, DECLARED),
                "a version that still declares the set strands nothing");
        List<ManagedValue<?>> renamed = List.of(ManagedValue.openSet(TestValue.Id.SHOTS).of(OldShot.class)
                .in("Shots").because("Renamed."));
        assertEquals(1, MigrationSteps.stranded(index, GRAMMAR, DECLARED, DECLARED, renamed).size(),
                "the same type under another id loses the values too");
    }

    @Test
    void aValueWithAMigratedCopyIsNotStranded(@TempDir Path root) {
        String copied = SHOTS.replace("public final class Shots {", """
                public final class Shots {
                    @com.botmaker.plugin.api.meta.Migrated
                    public static final Shot ORE = new Shot("ore.png", 80, false);
                """);
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), copied));

        List<MigrationSteps.Stranded> stranded = MigrationSteps.stranded(index, GRAMMAR, DECLARED, DECLARED,
                List.of(DECLARED.get(1)));
        assertEquals(1, stranded.getFirst().values(), "ORE has its copy; GOLD has none");
    }

    private static String written(Step step) {
        AST ast = AST.newAST(AST.getJLSLatest(), false);
        return MigrationSteps.method(GRAMMAR, ast, step, Character.toLowerCase(step.to().getSimpleName().charAt(0))
                + step.to().getSimpleName().substring(1), new LinkedHashSet<>()).orElseThrow().toString();
    }

    private static BotIndex index(Path root, Map<Path, String> files) {
        return BotIndex.over(new LinkedHashMap<>(files), List.of(locationOf(com.botmaker.plugin.api.meta.Migrate.class),
                locationOf(TestValue.class)), root);
    }

    /** {@code source} with whitespace one space and none around a brace, so a check does not depend on layout. */
    private static String flat(String source) {
        return source.replaceAll("\\s+", " ").replaceAll(" ?([{}]) ?", "$1");
    }

    private static Path file(Path root, String name) {
        return root.resolve("src/main/java/com/bot/" + name + ".java");
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
