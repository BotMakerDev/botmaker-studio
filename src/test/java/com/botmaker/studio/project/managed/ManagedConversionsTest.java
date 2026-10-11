package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.Conversion;
import com.botmaker.plugin.api.source.Converted;
import com.botmaker.plugin.api.source.Converting;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plugin's declared conversions run over a bot (2026-10-11): the old values read through the new jar's retired
 * declarations, the plugin's answer written as an edit in its window would write it, the retired holder taken
 * out only when nothing names it, and nothing planned at all when the conversion fails.
 */
class ManagedConversionsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();
    private static final String PLUGIN = "com.botmaker.test";

    static final ManagedValue<String> GREETING_V1 = ManagedValue.method(TestValue.Id.GREETING_V1).in("Greetings")
            .holds(String.class, "").because("Old.").retired();
    static final ManagedValue<String> GREETING = ManagedValue.method(TestValue.Id.GREETING).in("Values")
            .holds(String.class, "").because("New.");
    static final ManagedValue<TestOutcome> OUTCOMES_V1 = ManagedValue.openSet(TestValue.Id.OUTCOMES_V1)
            .ofEnum(TestOutcome.class, TestOutcome::named).in("Outcomes").because("Old.").retired();
    static final ManagedValue<TestOutcome> ACTIVITIES = ManagedValue.openSet(TestValue.Id.ACTIVITIES)
            .ofEnums(TestOutcome.class, ManagedConversionsTest::inGroup)
            .groupedAs(Group.class, ManagedConversionsTest::group)
            .in("Activities").because("New.");

    private static final List<ManagedValue<?>> DECLARED = List.of(GREETING_V1, GREETING, OUTCOMES_V1, ACTIVITIES);

    record Group(String name) {}

    public static TestOutcome inGroup(String group, String name) {
        return new TestOutcome.Named(group + "." + name);
    }

    public static Group group(String name) {
        return new Group(name);
    }

    /** The greeting shouted. */
    public static Converted shout(Converting old) {
        String greeting = old.value(GREETING_V1);
        return Converted.empty().value(GREETING, greeting == null ? "" : greeting.toUpperCase());
    }

    /** Each old outcome becomes an outcome of the one activity {@code Battle}. */
    public static Converted battle(Converting old) {
        Map<String, TestOutcome> members = new LinkedHashMap<>();
        old.members(OUTCOMES_V1).keySet().forEach(name -> members.put("Battle." + name, inGroup("Battle", name)));
        return Converted.empty().members(ACTIVITIES, members);
    }

    public static Converted fails(Converting old) {
        throw new IllegalStateException("cannot");
    }

    private static final Conversion SHOUT = Conversion.from(GREETING_V1).to(GREETING)
            .by(ManagedConversionsTest::shout).because("The greeting is shouted now.");
    private static final Conversion BATTLE = Conversion.from(OUTCOMES_V1).to(ACTIVITIES)
            .by(ManagedConversionsTest::battle).because("Each activity is now its own enum of outcomes.");

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

    private static final String OUTCOMES = """
            package %s.plugins.test;

            import com.botmaker.studio.project.managed.TestOutcome;
            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.OUTCOMES_V1)
            public enum Outcomes implements TestOutcome { WON, LOST }
            """;

    @Test
    void aMethodMovesToANewHolderAndTheOldFileGoes(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path greetings = holder(config, "Greetings");
        BotIndex index = index(config, Map.of(greetings, GREETINGS.formatted(config.mainPackage())));

        ManagedConversions.Planned plan = assertInstanceOf(ManagedConversions.Planned.class,
                ManagedConversions.plan(index, config, PLUGIN, DECLARED, List.of(SHOUT), GRAMMAR));

        assertEquals(Set.of(greetings), plan.deleted(), "the old holder held nothing else");
        String values = plan.rewrites().get(holder(config, "Values"));
        assertTrue(values != null && values.contains("return \"HELLO\";"), String.valueOf(values));
        assertTrue(values.contains("@TestValue(TestValue.Id.GREETING)"), values);
        assertEquals(List.of("The greeting is shouted now."),
                plan.steps().stream().map(ManagedConversions.Step::reason).toList());
    }

    @Test
    void anOldHolderTheBotStillNamesIsKept(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path greetings = holder(config, "Greetings");
        Path bot = config.mainPackageDir().resolve("Bot.java");
        BotIndex index = index(config, Map.of(
                greetings, GREETINGS.formatted(config.mainPackage()),
                bot, """
                        package %s;

                        import %s.plugins.test.Greetings;

                        class Bot {
                            String say() {
                                return Greetings.greeting();
                            }
                        }
                        """.formatted(config.mainPackage(), config.mainPackage())));

        ManagedConversions.Planned plan = assertInstanceOf(ManagedConversions.Planned.class,
                ManagedConversions.plan(index, config, PLUGIN, DECLARED, List.of(SHOUT), GRAMMAR));

        assertTrue(plan.deleted().isEmpty(), "Bot.java still calls it");
        assertTrue(plan.left().getFirst().contains("still named by the bot"), plan.left().toString());
        assertTrue(plan.rewrites().get(holder(config, "Values")).contains("return \"HELLO\";"));
        String kept = plan.rewrites().get(greetings);
        assertTrue(kept != null && !kept.contains("@TestValue") && kept.contains("return \"hello\";"),
                "kept as the user's code, unmarked: " + kept);
    }

    /** The old enum and the new class of enums share a name: the kept enum is renamed out of the way. */
    @Test
    void aKeptHolderInTheNewHoldersFileIsRenamed(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        ManagedValue<TestOutcome> oldActivities = ManagedValue.openSet(TestValue.Id.OUTCOMES_V1)
                .ofEnum(TestOutcome.class, TestOutcome::named).in("Activities").because("Old.").retired();
        Conversion conversion = Conversion.from(oldActivities).to(ACTIVITIES)
                .by(ManagedConversionsTest::battleFromActivities).because("Nested now.");
        Path activities = holder(config, "Activities");
        Path bot = config.mainPackageDir().resolve("Bot.java").toAbsolutePath().normalize();
        BotIndex index = index(config, Map.of(
                activities, OUTCOMES.formatted(config.mainPackage()).replace("Outcomes", "Activities"),
                bot, """
                        package %s;

                        import %s.plugins.test.Activities;

                        class Bot {
                            Object won() {
                                return Activities.WON;
                            }
                        }
                        """.formatted(config.mainPackage(), config.mainPackage())));

        ManagedConversions.Planned plan = assertInstanceOf(ManagedConversions.Planned.class,
                ManagedConversions.plan(index, config, PLUGIN, List.of(oldActivities, ACTIVITIES),
                        List.of(conversion), GRAMMAR));

        assertTrue(plan.rewrites().get(activities).contains("@TestValue(TestValue.Id.ACTIVITIES)"),
                plan.rewrites().get(activities));
        String retired = plan.rewrites().get(holder(config, "ActivitiesRetired"));
        assertTrue(retired != null && retired.contains("enum ActivitiesRetired") && !retired.contains("@TestValue"),
                String.valueOf(retired));
        assertTrue(plan.rewrites().get(bot).contains("ActivitiesRetired.WON"), plan.rewrites().get(bot));
    }

    public static Converted battleFromActivities(Converting old) {
        Map<String, TestOutcome> members = new LinkedHashMap<>();
        old.members(oldActivitiesOf()).keySet()
                .forEach(name -> members.put("Battle." + name, inGroup("Battle", name)));
        return Converted.empty().members(ACTIVITIES, members);
    }

    private static ManagedValue<TestOutcome> oldActivitiesOf() {
        return ManagedValue.openSet(TestValue.Id.OUTCOMES_V1).ofEnum(TestOutcome.class, TestOutcome::named)
                .in("Activities").because("Old.").retired();
    }

    @Test
    void anEnumSetBecomesNestedEnums(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        Path outcomes = holder(config, "Outcomes");
        BotIndex index = index(config, Map.of(outcomes, OUTCOMES.formatted(config.mainPackage())));

        ManagedConversions.Planned plan = assertInstanceOf(ManagedConversions.Planned.class,
                ManagedConversions.plan(index, config, PLUGIN, DECLARED, List.of(BATTLE), GRAMMAR));

        assertEquals(Set.of(outcomes), plan.deleted());
        String activities = plan.rewrites().get(holder(config, "Activities"));
        assertTrue(activities != null && activities.contains("@TestValue(TestValue.Id.ACTIVITIES)"),
                String.valueOf(activities));
        assertTrue(activities.replaceAll("\\s+", " ").contains("enum Battle implements TestOutcome { WON, LOST }"),
                activities);
    }

    @Test
    void aConversionThatFailsPlansNothing(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        BotIndex index = index(config, Map.of(holder(config, "Greetings"), GREETINGS.formatted(config.mainPackage())));
        Conversion failing = Conversion.from(GREETING_V1).to(GREETING).by(ManagedConversionsTest::fails)
                .because("Fails.");

        ManagedConversions.Refused refused = assertInstanceOf(ManagedConversions.Refused.class,
                ManagedConversions.plan(index, config, PLUGIN, DECLARED, List.of(failing), GRAMMAR));
        assertTrue(refused.reason().contains("cannot"), refused.reason());
    }

    @Test
    void aBotHoldingNoneOfItRunsNothing(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        BotIndex index = index(config, Map.of(config.mainPackageDir().resolve("Bot.java"),
                "package %s;\n\nclass Bot {}\n".formatted(config.mainPackage())));

        ManagedConversions.Planned plan = assertInstanceOf(ManagedConversions.Planned.class,
                ManagedConversions.plan(index, config, PLUGIN, DECLARED, List.of(SHOUT, BATTLE), GRAMMAR));
        assertTrue(plan.isEmpty() && plan.steps().isEmpty());
    }

    /** A value the old jar declared, the bot holds, and the new jar neither declares nor converts. */
    @Test
    void aValueNothingConvertsIsNamed(@TempDir Path dir) {
        ProjectConfig config = ProjectConfig.forDirectory(dir);
        BotIndex index = index(config, Map.of(holder(config, "Greetings"), GREETINGS.formatted(config.mainPackage())));
        Set<String> before = Set.of(GREETING_V1.id());

        assertEquals(List.of(GREETING_V1.id()),
                ManagedConversions.unconverted(index, before, List.of(GREETING), List.of()));
        assertTrue(ManagedConversions.unconverted(index, before, DECLARED, List.of(SHOUT)).isEmpty());
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
