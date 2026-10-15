package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.JavaValue;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
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
 * A plugin's open set, changed by binding: a use is what resolves to the constant, never a spelling of it —
 * the local {@code ORE} and the string {@code "Pictures.ORE"} below are never touched — and a change that
 * would stop the bot compiling is refused.
 */
class ManagedSetsTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();

    /** The set's id: the class below is marked with it, and resolves through {@link #testClasses()}. */
    private static final String ID = ManagedValue.idOf(TestValue.Id.PICTURES);

    private static final String PICTURES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.PICTURES)
            public final class Pictures {
                public static final String ORE = "images/ore.png";
                public static final String GOLD = "images/gold.png";
                static final String HIDDEN = "images/hidden.png";

                private Pictures() {}
            }
            """;

    private static final String BOT = """
            package com.bot;

            import com.bot.plugins.sdk.Pictures;

            import static com.bot.plugins.sdk.Pictures.ORE;

            class Bot {
                String look() {
                    return Pictures.ORE;
                }

                String again() {
                    return ORE + "Pictures.ORE";
                }

                String other() {
                    String ORE = "local";
                    return ORE;
                }
            }
            """;

    @Test
    void theMembersAreThePublicConstants(@TempDir Path root) {
        assertEquals(List.of("ORE", "GOLD"), ManagedSets.members(index(root), ID,GRAMMAR).stream()
                .map(ManagedSets.Member::name).toList());
        assertTrue(ManagedSets.members(index(root), "flow", GRAMMAR).isEmpty(), "no class carries that id");
    }

    @Test
    void theUsesAreWhatBindsToTheConstant(@TempDir Path root) {
        BotIndex index = index(root);
        List<Integer> lines = ManagedSets.uses(index, member(index, "ORE")).stream()
                .map(use -> use.line()).toList();
        assertEquals(List.of(5, 9, 13), lines, "the static import and both reads, not the local");
    }

    @Test
    void aRenameChangesTheDeclarationAndEveryUse(@TempDir Path root) {
        BotIndex index = index(root);
        ManagedSets.Member ore = member(index, "ORE");
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, ore.file(), ore.start(), "IRON"));

        assertTrue(plan.rewrites().get(pictures(root)).contains("public static final String IRON = "));
        String bot = plan.rewrites().get(bot(root));
        assertTrue(bot.contains("import static com.bot.plugins.sdk.Pictures.IRON;"), bot);
        assertTrue(bot.contains("return Pictures.IRON;"), bot);
        assertTrue(bot.contains("return IRON + \"Pictures.ORE\";"), bot);
        assertTrue(bot.contains("String ORE = \"local\";"), "a local of the same name stays: " + bot);
    }

    @Test
    void aRepointMovesTheUsesAndMarksWhatItGuessed(@TempDir Path root) {
        BotIndex index = index(root);
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.repoint(index, member(index, "ORE"), member(index, "GOLD"), "look again"));

        assertFalse(plan.rewrites().containsKey(pictures(root)), "the declaration stays until removed");
        String bot = plan.rewrites().get(bot(root));
        assertTrue(bot.contains("return Pictures.GOLD;"), bot);
        assertTrue(bot.contains("import static com.bot.plugins.sdk.Pictures.GOLD;"), bot);
        assertTrue(bot.contains("@Refactor"), "the function it guessed in is marked: " + bot);
        assertTrue(bot.contains("String ORE = \"local\";"), bot);

        Refactor.Planned unmarked = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.repoint(index, member(index, "ORE"), member(index, "GOLD"), null));
        assertFalse(unmarked.rewrites().get(bot(root)).contains("@Refactor"));
    }

    @Test
    void aUsedConstantIsNotRemovedAndAnUnusedOneIs(@TempDir Path root) {
        BotIndex index = index(root);
        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class,
                ManagedSets.remove(index, member(index, "ORE")));
        assertTrue(refused.reason().startsWith("Pictures.ORE is still used 3 times"), refused.reason());
        assertEquals(3, refused.uses().size());

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.remove(index, member(index, "GOLD")));
        String pictures = plan.rewrites().get(pictures(root));
        assertFalse(pictures.contains("GOLD"), pictures);
        assertTrue(pictures.contains("public static final String ORE"), pictures);
    }

    @Test
    void anAddedConstantFollowsTheLastFieldAndATakenNameIsRefused(@TempDir Path root) {
        BotIndex index = index(root);
        JavaValue silver = GRAMMAR.spellAny("images/silver.png").orElseThrow();

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.add(index, ID,"SILVER", String.class, silver));
        String pictures = plan.rewrites().get(pictures(root));
        assertTrue(pictures.contains("public static final String SILVER = \"images/silver.png\";"), pictures);
        assertTrue(pictures.indexOf("SILVER") > pictures.indexOf("HIDDEN"), "after the last field: " + pictures);

        assertInstanceOf(Refactor.Refused.class, ManagedSets.add(index, ID,"GOLD", String.class, silver),
                "a second GOLD does not compile");
        assertInstanceOf(Refactor.Refused.class, ManagedSets.add(index, ID,"no good", String.class, silver));
        assertInstanceOf(Refactor.Refused.class, ManagedSets.add(index, "flow", "X", String.class, silver));
    }

    /** Two classes carrying one id: which one a constant belongs in is nobody's guess, so nothing is added. */
    @Test
    void twoClassesCarryingOneIdRefuseAnAdd(@TempDir Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(pictures(root), PICTURES);
        sources.put(root.resolve("com/bot/plugins/sdk/MorePictures.java"),
                PICTURES.replace("class Pictures", "class MorePictures").replace("private Pictures()",
                        "private MorePictures()"));
        BotIndex index = BotIndex.over(sources, List.of(contract(), testClasses()), root);
        JavaValue silver = GRAMMAR.spellAny("images/silver.png").orElseThrow();

        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class,
                ManagedSets.add(index, ID,"SILVER", String.class, silver));
        assertTrue(refused.reason().contains("are both marked @TestValue(TestValue.Id.PICTURES)"), refused.reason());
    }

    private static final String OUTCOMES_ID = ManagedValue.idOf(TestValue.Id.OUTCOMES);

    private static final String OUTCOMES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestOutcome;
            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.OUTCOMES)
            public enum Outcomes implements TestOutcome {
                WON, LOST
            }
            """;

    private static final String REPORTS = """
            package com.bot;

            import com.bot.plugins.sdk.Outcomes;
            import com.botmaker.studio.project.managed.TestOutcome;

            class Reports {
                TestOutcome body() {
                    return Outcomes.WON;
                }
            }
            """;

    /** An enum set: its members are its constants, each a bare name added, renamed and removed as a field is. */
    @Test
    void anEnumSetsConstantsAreAddedRenamedAndRemoved(@TempDir Path root) {
        Path outcomes = root.resolve("com/bot/plugins/sdk/Outcomes.java");
        Path reports = root.resolve("com/bot/Reports.java");
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(outcomes, OUTCOMES);
        sources.put(reports, REPORTS);
        BotIndex index = BotIndex.over(sources, List.of(contract(), testClasses()), root);

        List<ManagedSets.Member> members = ManagedSets.members(index, OUTCOMES_ID, GRAMMAR);
        assertEquals(List.of("WON", "LOST"), members.stream().map(ManagedSets.Member::name).toList());
        assertTrue(members.stream().allMatch(m -> m.initializer() == null), "a constant's name is all it has");

        String added = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.add(index, OUTCOMES_ID, "BAG_FULL", null, null)).rewrites().get(outcomes);
        assertTrue(added.contains("WON, LOST, BAG_FULL"), added);
        assertInstanceOf(Refactor.Refused.class, ManagedSets.add(index, OUTCOMES_ID, "WON", null, null),
                "a second WON does not compile");

        ManagedSets.Member won = members.getFirst();
        Refactor.Planned renamed = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, won.file(), won.start(), "VICTORY"));
        assertTrue(renamed.rewrites().get(outcomes).contains("VICTORY, LOST"), renamed.rewrites().toString());
        assertTrue(renamed.rewrites().get(reports).contains("return Outcomes.VICTORY;"));

        assertInstanceOf(Refactor.Refused.class, ManagedSets.remove(index, won), "WON is still returned");
        String removed = assertInstanceOf(Refactor.Planned.class, ManagedSets.remove(index, members.get(1)))
                .rewrites().get(outcomes);
        assertFalse(removed.contains("LOST"), removed);
        assertTrue(removed.contains("WON"), removed);
    }

    private static final String ACTIVITIES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestOutcome;
            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.OUTCOMES)
            public final class Activities {
                public enum Collect implements TestOutcome { NEXT, WON }
            }
            """;

    private static final String COLLECT = """
            package com.bot;

            import com.bot.plugins.sdk.Activities;

            public final class Collect {
                public static Activities.Collect body() {
                    return Activities.Collect.WON;
                }

                public static void home() {
                }

                public static void main(String[] args) {
                }

                static Activities.Collect hidden() {
                    return Activities.Collect.NEXT;
                }
            }
            """;

    /** A grouped set: whole enums added, removed while unused, and each one's constants addressed "Group.NAME". */
    @Test
    void aGroupedSetsEnumsAndTheirConstantsAreAddedAndRemoved(@TempDir Path root) {
        Path activities = root.resolve("com/bot/plugins/sdk/Activities.java");
        Path collect = root.resolve("com/bot/Collect.java");
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(activities, ACTIVITIES);
        sources.put(collect, COLLECT);
        BotIndex index = BotIndex.over(sources, List.of(contract(), testClasses()), root);

        assertEquals(List.of("Collect.NEXT", "Collect.WON"), ManagedSets.members(index, OUTCOMES_ID, GRAMMAR)
                .stream().map(ManagedSets.Member::memberName).toList());
        assertEquals(List.of("Collect"), ManagedSets.groups(index, OUTCOMES_ID).stream()
                .map(ManagedSets.Group::name).toList());

        String added = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.addGroup(index, OUTCOMES_ID, "Battle", List.of("NEXT", "LOST"), TestOutcome.class))
                .rewrites().get(activities);
        assertTrue(added.contains("public enum Battle implements TestOutcome {"), added);
        assertTrue(added.contains("NEXT, LOST"), added);
        assertTrue(added.contains("    public enum Collect implements TestOutcome { NEXT, WON }"),
                "what was there is kept as written: " + added);
        assertInstanceOf(Refactor.Refused.class,
                ManagedSets.addGroup(index, OUTCOMES_ID, "Collect", List.of("NEXT"), TestOutcome.class));
        assertInstanceOf(Refactor.Refused.class,
                ManagedSets.addGroup(index, OUTCOMES_ID, "Empty", List.of(), TestOutcome.class));

        String constant = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.add(index, OUTCOMES_ID, "Collect.BAG_FULL", null, null)).rewrites().get(activities);
        assertTrue(constant.contains("NEXT, WON, BAG_FULL"), constant);

        ManagedSets.Group group = ManagedSets.group(index, OUTCOMES_ID, "Collect").orElseThrow();
        assertInstanceOf(Refactor.Refused.class, ManagedSets.removeGroup(index, group), "Collect is returned");
        ManagedSets.Member next = ManagedSets.member(index, OUTCOMES_ID, "Collect.NEXT", GRAMMAR).orElseThrow();
        assertInstanceOf(Refactor.Refused.class, ManagedSets.remove(index, next), "hidden() returns it");

        assertEquals(List.of("Collect::body"), BotMethods.returning(index, "com.bot.plugins.sdk.Activities.Collect"));
        assertEquals(List.of("Collect::home"), BotMethods.returning(index, null), "main is no way home");
    }

    @Test
    void aMethodIsWrittenIntoAClassOrANewOne(@TempDir Path root) {
        Path activities = root.resolve("com/bot/plugins/sdk/Activities.java");
        Path collect = root.resolve("com/bot/Collect.java");
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(activities, ACTIVITIES);
        sources.put(collect, COLLECT);
        BotIndex index = BotIndex.over(sources, List.of(contract(), testClasses()), root);
        assertEquals(List.of("Collect"), BotMethods.classes(index, root.resolve("com/bot/plugins")),
                "the classes a method can go in, the plugins' holders left out");

        String into =assertInstanceOf(Refactor.Planned.class, BotMethods.create(index, "com.bot",
                root.resolve("com/bot/Collect.java"), "Collect", "again", "Activities.Collect",
                "com.bot.plugins.sdk.Activities", "NEXT")).rewrites().get(collect);
        assertTrue(into.contains("public static Activities.Collect again() {"), into);
        assertTrue(into.contains("return Activities.Collect.NEXT;"), into);
        assertInstanceOf(Refactor.Refused.class, BotMethods.create(index, "com.bot", collect, "Collect", "body",
                "Activities.Collect", "com.bot.plugins.sdk.Activities", "NEXT"), "body is taken");

        Path rest = root.resolve("com/bot/Rest.java");
        String created = assertInstanceOf(Refactor.Planned.class, BotMethods.create(index, "com.bot", rest, "Rest",
                "home", null, null, null)).rewrites().get(rest);
        assertTrue(created.startsWith("package com.bot;"), created);
        assertTrue(created.contains("public static void home() {"), created);

        String condition = assertInstanceOf(Refactor.Planned.class, BotMethods.create(index, "com.bot", collect,
                "Collect", "eventOn", BotMethods.BOOLEAN, null, null)).rewrites().get(collect);
        assertTrue(condition.contains("public static boolean eventOn() {"), condition);
        assertTrue(condition.contains("return false;"), condition);
        assertEquals(List.of("Collect::eventOn"), BotMethods.returning(BotIndex.over(Map.of(collect, condition),
                List.of(contract(), testClasses()), root), BotMethods.BOOLEAN));
    }

    private static final String ROUTES = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.PICTURES)
            public final class Pictures {
                public static final String MAIN = "main";

                public static final String A = "a", B = "b";

                public static final String MORE = "more";

                /** The daily round. */
                @Deprecated
                public static final String DAILY = "daily";

                public static final String GOLD = MORE + "/gold";

                private Pictures() {}
            }
            """;

    /**
     * A constant moved above another, so the other may read it: the declaration moves whole, javadoc and
     * annotation with it, and nothing is written when it is already above. A move that would make its own
     * initialiser read a constant below it is refused, as is one out of a shared declaration.
     */
    @Test
    void aConstantMovesAboveAnotherWithItsJavadoc(@TempDir Path root) {
        BotIndex index = BotIndex.over(Map.of(pictures(root), ROUTES), List.of(contract(), testClasses()), root);

        String moved = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.moveBefore(index, member(index, "DAILY"), member(index, "MAIN")))
                .rewrites().get(pictures(root));
        int daily = moved.indexOf("/** The daily round. */");
        assertTrue(daily >= 0 && daily < moved.indexOf("@Deprecated") && moved.indexOf("@Deprecated")
                < moved.indexOf("DAILY = ") && moved.indexOf("DAILY = ") < moved.indexOf("MAIN = "), moved);
        assertEquals(1, moved.split("DAILY = ", -1).length - 1, "moved, not copied: " + moved);

        assertTrue(assertInstanceOf(Refactor.Planned.class,
                ManagedSets.moveBefore(index, member(index, "MAIN"), member(index, "DAILY")))
                .rewrites().isEmpty(), "already above: nothing to write");

        Refactor.Refused forward = assertInstanceOf(Refactor.Refused.class,
                ManagedSets.moveBefore(index, member(index, "GOLD"), member(index, "MAIN")));
        assertTrue(forward.reason().contains("would stop the bot compiling"), forward.reason());
        assertInstanceOf(Refactor.Refused.class,
                ManagedSets.moveBefore(index, member(index, "B"), member(index, "MAIN")), "B shares A's declaration");
        assertInstanceOf(Refactor.Refused.class,
                ManagedSets.moveBefore(index, member(index, "MAIN"), member(index, "MAIN")));
    }

    /** An enum constant stays where it is: moving it would change its ordinal, and it reads no other constant. */
    @Test
    void anEnumConstantIsNeverMoved(@TempDir Path root) {
        Path outcomes = root.resolve("com/bot/plugins/sdk/Outcomes.java");
        BotIndex index = BotIndex.over(Map.of(outcomes, OUTCOMES), List.of(contract(), testClasses()), root);
        List<ManagedSets.Member> members = ManagedSets.members(index, OUTCOMES_ID, GRAMMAR);

        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class,
                ManagedSets.moveBefore(index, members.get(1), members.getFirst()));
        assertTrue(refused.reason().contains("enum's constant"), refused.reason());
    }

    private static ManagedSets.Member member(BotIndex index, String name) {
        return ManagedSets.member(index, ID,name, GRAMMAR).orElseThrow();
    }

    private static Path pictures(Path root) {
        return root.resolve("com/bot/plugins/sdk/Pictures.java");
    }

    private static Path bot(Path root) {
        return root.resolve("com/bot/Bot.java");
    }

    private static BotIndex index(Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(pictures(root), PICTURES);
        sources.put(bot(root), BOT);
        return BotIndex.over(sources, List.of(contract(), testClasses()), root);
    }

    /** The contract, as a bot's classpath carries it — so {@code @Refactor} compiles. */
    private static String contract() {
        return locationOf(com.botmaker.plugin.api.meta.Refactor.class);
    }

    /** The test classes, carrying {@link TestValue} as a plugin's jar carries its marker — so the mark resolves. */
    private static String testClasses() {
        return locationOf(TestValue.class);
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
