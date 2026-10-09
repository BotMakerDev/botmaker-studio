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
