package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's values in a bot repaired on upgrade (2026-10-11): a value whose id is gone or that is declared another
 * way now is removed, or kept unmarked while the bot names it; a value that no longer compiles takes its first
 * value, marked for review; one that compiles is left alone. Nothing is converted.
 */
class ManagedRepairsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();
    private static final String NOTE = "Reset by the upgrade.";

    static final ManagedValue<String> GREETING = ManagedValue.method(TestValue.Id.GREETING).in("Values")
            .holds(String.class, "hi").because("New.");
    static final ManagedValue<TestOutcome> ACTIVITIES = ManagedValue.openSet(TestValue.Id.ACTIVITIES)
            .ofEnums(TestOutcome.class, ManagedRepairsTest::inGroup)
            .groupedAs(Group.class, ManagedRepairsTest::group)
            .in("Activities").because("New.");

    /** What the version the bot moves to declares: no {@code GREETING_V1}, and {@code ACTIVITIES} nested now. */
    private static final List<ManagedValue<?>> LIVE = List.of(GREETING, ACTIVITIES);

    record Group(String name) {}

    public static TestOutcome inGroup(String group, String name) {
        return new TestOutcome.Named(group + "." + name);
    }

    public static Group group(String name) {
        return new Group(name);
    }

    private static final String GREETINGS = """
            package %s.plugins.test;

            import com.botmaker.studio.project.managed.TestValue;

            public final class Greetings {
                @TestValue(TestValue.Id.GREETING_V1)
                public static String greeting() {
                    return "hello";
                }

                private Greetings() {}
            }
            """;

    private static final String VALUES = """
            package %s.plugins.test;

            import com.botmaker.studio.project.managed.TestValue;

            public final class Values {
                @TestValue(TestValue.Id.GREETING)
                public static String greeting() {
                    return %s;
                }

                private Values() {}
            }
            """;

    @Test
    void aValueWhoseIdIsGoneIsRemovedWithItsFile(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path greetings = holder(config, "Greetings");
        BotIndex index = index(config, Map.of(greetings, GREETINGS.formatted(config.mainPackage())));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        assertEquals(Set.of(greetings), plan.deleted(), "the holder held nothing else");
        assertTrue(plan.repaired().getFirst().contains("is no longer one of the plugin's values"),
                plan.repaired().toString());
    }

    @Test
    void aValueTheBotStillNamesIsKeptUnmarked(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path greetings = holder(config, "Greetings");
        BotIndex index = index(config, Map.of(
                greetings, GREETINGS.formatted(config.mainPackage()),
                config.mainPackageDir().resolve("Bot.java").toAbsolutePath().normalize(), """
                        package %s;

                        import %s.plugins.test.Greetings;

                        class Bot {
                            String say() {
                                return Greetings.greeting();
                            }
                        }
                        """.formatted(config.mainPackage(), config.mainPackage())));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        assertTrue(plan.deleted().isEmpty(), "Bot.java still calls it");
        assertTrue(plan.left().getFirst().contains("the bot still names it"), plan.left().toString());
        String kept = plan.rewrites().get(greetings);
        assertTrue(kept != null && !kept.contains("@TestValue") && kept.contains("return \"hello\";"),
                "kept as the user's code, unmarked: " + kept);
    }

    /** An enum where the plugin now declares a class of enums is declared another way: removed. */
    @Test
    void aHolderOfAnotherKindIsRemoved(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path activities = holder(config, "Activities");
        BotIndex index = index(config, Map.of(activities, """
                package %s.plugins.test;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.ACTIVITIES)
                public enum Activities implements TestOutcome { WON, LOST }
                """.formatted(config.mainPackage())));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        assertEquals(Set.of(activities), plan.deleted());
        assertTrue(plan.repaired().getFirst().contains("is declared another way now"), plan.repaired().toString());
    }

    /** The kept enum is in the file the plugin's class of enums needs: it is renamed out of the way. */
    @Test
    void aKeptHolderInThePluginsFileIsRenamed(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path activities = holder(config, "Activities");
        Path bot = config.mainPackageDir().resolve("Bot.java").toAbsolutePath().normalize();
        BotIndex index = index(config, Map.of(activities, """
                package %s.plugins.test;

                import com.botmaker.studio.project.managed.TestOutcome;
                import com.botmaker.studio.project.managed.TestValue;

                @TestValue(TestValue.Id.ACTIVITIES)
                public enum Activities implements TestOutcome { WON, LOST }
                """.formatted(config.mainPackage()), bot, """
                package %s;

                import %s.plugins.test.Activities;

                class Bot {
                    Object won() {
                        return Activities.WON;
                    }
                }
                """.formatted(config.mainPackage(), config.mainPackage())));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        String retired = plan.rewrites().get(holder(config, "ActivitiesRetired"));
        assertTrue(retired != null && retired.contains("enum ActivitiesRetired") && !retired.contains("@TestValue"),
                String.valueOf(retired));
        assertTrue(plan.rewrites().get(bot).contains("ActivitiesRetired.WON"), plan.rewrites().get(bot));
        assertTrue(plan.left().getFirst().contains("as ActivitiesRetired"), plan.left().toString());
    }

    /** A method returning another type than the value's is the old shape: removed, never reset. */
    @Test
    void aMethodOfAnotherTypeIsRemoved(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path values = holder(config, "Values");
        BotIndex index = index(config, Map.of(values, VALUES.formatted(config.mainPackage(), "\"hello\"")
                .replace("public static String greeting()", "public static Integer greeting()")
                .replace("return \"hello\";", "return 1;")));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        assertEquals(Set.of(values), plan.deleted(), plan.sentences().toString());
        assertTrue(plan.repaired().getFirst().contains("is declared another way now"), plan.repaired().toString());
    }

    @Test
    void aValueThatNoLongerCompilesTakesItsFirstValueMarked(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path values = holder(config, "Values");
        BotIndex index = index(config, Map.of(values, VALUES.formatted(config.mainPackage(), "Gone.greeting()")));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        String repaired = plan.rewrites().get(values);
        assertTrue(repaired != null && repaired.contains("return \"hi\";"), String.valueOf(repaired));
        assertTrue(repaired.contains("@Refactor") && repaired.contains(NOTE), repaired);
        assertTrue(repaired.contains("@TestValue(TestValue.Id.GREETING)"), "still the plugin's: " + repaired);
        assertTrue(plan.repaired().getFirst().contains("takes its first value again"), plan.repaired().toString());
    }

    @Test
    void aValueThatCompilesIsLeftAlone(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        BotIndex index = index(config, Map.of(holder(config, "Values"),
                VALUES.formatted(config.mainPackage(), "\"hello\"")));

        ManagedRepairs.Planned plan = ManagedRepairs.plan(index, LIVE, GRAMMAR, NOTE);

        assertTrue(plan.isEmpty() && plan.sentences().isEmpty(), plan.sentences().toString());
    }

    /** A plugin's parameter is a marked field, which counts as held (2026-10-11). */
    @Test
    void aMarkedFieldIsHeld(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        BotIndex index = index(config, Map.of(holder(config, "Sdk"), """
                package %s.plugins.test;

                import %s;

                public final class Sdk {
                    @TestValue(TestValue.Id.DO_SETUP)
                    public static boolean doSetup = true;
                }
                """.formatted(config.mainPackage(), TestValue.class.getCanonicalName())));

        assertTrue(ManagedRepairs.held(index).contains(ManagedValue.idOf(TestValue.Id.DO_SETUP)));
    }

    private static Path holder(ProjectConfig config, String className) {
        return config.mainPackageDir().resolve("plugins").resolve("test").resolve(className + ".java")
                .toAbsolutePath().normalize();
    }

    private static BotIndex index(ProjectConfig config, Map<Path, String> sources) {
        return BotIndex.over(sources, List.of(locationOf(com.botmaker.plugin.api.meta.Refactor.class),
                locationOf(TestValue.class)), config.sourceRoot());
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
