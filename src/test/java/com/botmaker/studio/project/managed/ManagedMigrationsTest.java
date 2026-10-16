package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationTypes.Frame;
import com.botmaker.studio.project.managed.MigrationTypes.OldFrame;
import com.botmaker.studio.project.managed.MigrationTypes.OldPaint;
import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.Paint;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The author's {@code @Migrate} methods, substituted and never run: every constant of the old type's open set is
 * copied into the new type's beside its original, marked; a rerun rewrites only what is still marked; and a
 * method the host cannot use is refused with why.
 */
@SuppressWarnings("removal")
class ManagedMigrationsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(MigrationTypes.types(), List.of());

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.openSet(TestValue.Id.OLD_SHOTS).of(OldShot.class).in("OldShots").because("Old."),
            ManagedValue.openSet(TestValue.Id.SHOTS).of(Shot.class).in("Shots").because("New."),
            ManagedValue.openSet(TestValue.Id.OLD_FRAMES).of(OldFrame.class).in("OldFrames").because("Old."),
            ManagedValue.openSet(TestValue.Id.FRAMES).of(Frame.class).in("Frames").because("New."),
            ManagedValue.openSet(TestValue.Id.OLD_PAINTS).of(OldPaint.class).in("OldPaints").because("Old."),
            ManagedValue.openSet(TestValue.Id.PAINTS).of(Paint.class).in("Paints").because("New."));

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

    /** GOLD is the author's own, so it is kept; ORE is new. */
    private static final String SHOTS = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.Shot;

            @TestValue(TestValue.Id.SHOTS)
            public final class Shots {
                public static final Shot GOLD = new Shot("mine.png", 1, false);

                private Shots() {}
            }
            """;

    private static final String MIGRATIONS = """
            package com.bot;

            import com.botmaker.plugin.api.meta.Migrate;
            import com.botmaker.studio.project.managed.MigrationTypes.*;

            public final class Migrations {
                @Migrate(kind = Migrate.Kind.TYPE, value = OldShot.class)
                public static Shot shot(String file, int threshold) {
                    return new Shot(file, threshold, true);
                }

                @Migrate(kind = Migrate.Kind.TYPE, value = OldArea.class)
                public static Area area(int x, int y) {
                    return new Area(x, y, 10);
                }

                @Migrate(kind = Migrate.Kind.TYPE, value = OldFrame.class)
                public static Frame frame(String file, Area area) {
                    return new Frame(file, area);
                }

                @Migrate(kind = Migrate.Kind.TYPE, value = OldTint.class)
                public static Tint tint(OldTint old) {
                    return switch (old) {
                        case RED -> Tint.CRIMSON;
                        case BLUE -> Tint.NAVY;
                    };
                }

                @Migrate(kind = Migrate.Kind.TYPE, value = OldPaint.class)
                public static Paint paint(Tint tint) {
                    return new Paint(tint);
                }
            }
            """;

    @Test
    void everyValueIsCopiedBesideItsOriginalAndMarked(@TempDir Path root) {
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), SHOTS, file(root, "Migrations"), MIGRATIONS));
        ManagedMigrations.Run run = ManagedMigrations.migrate(index, GRAMMAR, DECLARED, OldShot.class, Shot.class);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, run.outcome(), run.outcome()::toString);
        assertEquals(List.of("Shots.ORE"), run.report().written());
        assertEquals(List.of("Shots.GOLD"), run.report().kept(), "the author's own GOLD");
        assertEquals(List.of(), run.report().failed());
        assertEquals(List.of(file(root, "plugins/sdk/Shots")), List.copyOf(plan.rewrites().keySet()),
                "the originals are never touched");
        String shots = plan.rewrites().get(file(root, "plugins/sdk/Shots"));
        assertTrue(flat(shots).contains("@Migrated public static final MigrationTypes.Shot ORE ="
                + " new MigrationTypes.Shot(\"ore.png\", 80, true);"), shots);
        assertTrue(shots.contains("import com.botmaker.plugin.api.meta.Migrated;"), shots);
        assertTrue(shots.contains("new Shot(\"mine.png\", 1, false)"), "GOLD kept as the author wrote it");
    }

    @Test
    void aRerunRewritesOnlyWhatIsStillMarked(@TempDir Path root) {
        String shots = SHOTS.replace("public static final Shot GOLD = new Shot(\"mine.png\", 1, false);",
                "@com.botmaker.plugin.api.meta.Migrated public static final Shot ORE = new Shot(\"stale.png\", 1, false);"
                        + "\n    public static final Shot GOLD = new Shot(\"mine.png\", 1, false);");
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), shots, file(root, "Migrations"), MIGRATIONS));
        ManagedMigrations.Run run = ManagedMigrations.migrate(index, GRAMMAR, DECLARED, OldShot.class, Shot.class);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, run.outcome(), run.outcome()::toString);
        assertEquals(List.of("Shots.ORE"), run.report().written());
        assertEquals(List.of("Shots.GOLD"), run.report().kept());
        String rewritten = plan.rewrites().get(file(root, "plugins/sdk/Shots"));
        assertTrue(flat(rewritten).contains("ORE = new MigrationTypes.Shot(\"ore.png\", 80, true)"), rewritten);
        assertFalse(rewritten.contains("stale.png"), rewritten);
    }

    @Test
    void aPartWhoseTypeMovedTooGoesThroughItsOwnMethod(@TempDir Path root) {
        String old = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;
                import com.botmaker.studio.project.managed.MigrationTypes.*;

                @TestValue(TestValue.Id.OLD_FRAMES)
                public final class OldFrames {
                    public static final OldFrame TOP = new OldFrame("top.png", new OldArea(1, 2));
                }
                """;
        String frames = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.FRAMES)
                public final class Frames {
                }
                """;
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldFrames"), old,
                file(root, "plugins/sdk/Frames"), frames, file(root, "Migrations"), MIGRATIONS));
        ManagedMigrations.Run run = ManagedMigrations.migrate(index, GRAMMAR, DECLARED, OldFrame.class, Frame.class);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, run.outcome(), run.outcome()::toString);
        String written = plan.rewrites().get(file(root, "plugins/sdk/Frames"));
        assertTrue(flat(written).contains(
                "TOP = new MigrationTypes.Frame(\"top.png\", new MigrationTypes.Area(1, 2, 10))"), written);
    }

    @Test
    void anEnumIsMappedByItsSwitch(@TempDir Path root) {
        String old = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;
                import com.botmaker.studio.project.managed.MigrationTypes.*;

                @TestValue(TestValue.Id.OLD_PAINTS)
                public final class OldPaints {
                    public static final OldPaint WALL = new OldPaint(OldTint.BLUE);
                }
                """;
        String paints = """
                package com.bot.plugins.sdk;

                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.PAINTS)
                public final class Paints {
                }
                """;
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldPaints"), old,
                file(root, "plugins/sdk/Paints"), paints, file(root, "Migrations"), MIGRATIONS));
        ManagedMigrations.Run run = ManagedMigrations.migrate(index, GRAMMAR, DECLARED, OldPaint.class, Paint.class);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, run.outcome(), run.outcome()::toString);
        String written = plan.rewrites().get(file(root, "plugins/sdk/Paints"));
        assertTrue(flat(written).contains("WALL = new MigrationTypes.Paint(MigrationTypes.Tint.NAVY)"), written);
    }

    @Test
    void aMethodTheHostCannotUseIsRefusedWithWhy(@TempDir Path root) {
        String broken = MIGRATIONS.replace("public static Shot shot(", "public Shot shot(");
        BotIndex index = index(root, Map.of(file(root, "plugins/sdk/OldShots"), OLD_SHOTS,
                file(root, "plugins/sdk/Shots"), SHOTS, file(root, "Migrations"), broken));
        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class,
                ManagedMigrations.migrate(index, GRAMMAR, DECLARED, OldShot.class, Shot.class).outcome());
        assertEquals("Migrations.shot is not static.", refused.reason());

        Refactor.Refused none = assertInstanceOf(Refactor.Refused.class,
                ManagedMigrations.migrate(index, GRAMMAR, DECLARED, Shot.class, OldShot.class).outcome());
        assertEquals("No @Migrate method maps Shot to OldShot.", none.reason());
    }

    @Test
    void twoMethodsForOnePairAreBothRefused(@TempDir Path root) {
        String twice = MIGRATIONS.replace("@Migrate(kind = Migrate.Kind.TYPE, value = OldArea.class)", "@Migrate(kind = Migrate.Kind.TYPE, value = OldShot.class)")
                .replace("public static Area area(int x, int y) {\n        return new Area(x, y, 10);",
                        "public static Shot again(String file, int threshold) {\n        return new Shot(file, 0, true);");
        BotIndex index = index(root, Map.of(file(root, "Migrations"), twice));
        List<String> problems = ManagedMigrations.methods(index, GRAMMAR).stream()
                .filter(m -> m.name().equals("shot") || m.name().equals("again"))
                .map(ManagedMigrations.Method::problem).toList();
        assertEquals(2, problems.size());
        assertTrue(problems.stream().allMatch(p -> p != null && p.contains("keep one")), problems::toString);
    }

    @Test
    void aBodyDeclaringAParameterNameIsNotFilledIn() {
        Map<String, org.eclipse.jdt.core.dom.Expression> bound = Map.of("file",
                com.botmaker.studio.plugin.grammar.JavaExpressions.parse("\"a.png\""));
        assertTrue(ManagedMigrations.substituted("java.util.List.of(1).stream().map(file -> file)", bound).isEmpty());
        assertEquals("new Shot(\"a.png\",1)", ManagedMigrations.substituted("new Shot(file, 1)", bound)
                .orElseThrow().toString());
    }

    @Test
    void anEnumMethodMustBeASwitch(@TempDir Path root) {
        String other = MIGRATIONS.replace("return switch (old) {\n            case RED -> Tint.CRIMSON;\n"
                + "            case BLUE -> Tint.NAVY;\n        };", "return Tint.CRIMSON;");
        BotIndex index = index(root, Map.of(file(root, "Migrations"), other));
        ManagedMigrations.Method tint = ManagedMigrations.methods(index, GRAMMAR).stream()
                .filter(m -> m.name().equals("tint")).findFirst().orElseThrow();
        assertTrue(tint.problem().contains("returns a switch over the constant"), tint::toString);
    }

    @Test
    void anEditTakesTheMarkOff() {
        String marked = """
                import com.botmaker.plugin.api.meta.Migrated;

                class Shots {
                    @Migrated
                    public static final Shot ORE = new Shot("ore.png", 80, true);
                }
                """;
        String edited = ManagedMigrations.unmarked(marked, "Shots", "ORE");
        assertFalse(edited.contains("@Migrated"), edited);
        assertTrue(edited.contains("public static final Shot ORE"), edited);
        assertEquals(edited, ManagedMigrations.unmarked(edited, "Shots", "ORE"), "nothing left to take off");
    }

    private static BotIndex index(Path root, Map<Path, String> files) {
        return BotIndex.over(new LinkedHashMap<>(files), List.of(locationOf(com.botmaker.plugin.api.meta.Migrate.class),
                locationOf(TestValue.class)), root);
    }

    /** {@code source} with every run of whitespace one space, so a check does not depend on line breaks. */
    private static String flat(String source) {
        return source.replaceAll("\\s+", " ");
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
