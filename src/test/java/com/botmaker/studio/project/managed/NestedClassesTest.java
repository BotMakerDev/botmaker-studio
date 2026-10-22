package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedClasses;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.ConstantValues;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A set of nested classes ({@code ManagedValue.ofClasses}, 2026-10-16): each class is a member with its constant,
 * its fields members of their own, its setup a value — read, written and renamed by binding.
 */
class NestedClassesTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.empty();

    private static final ManagedClasses<TestScreen> SET = ManagedValue.openSet(TestValue.Id.SCREENS)
            .ofClasses(TestScreen.class)
            .fields(TestScreen.Field.class, TestScreen::field)
            .setup(TestScreen::setup)
            .referredToAs(TestScreen::named)
            .in("Screens").because("Mine.");

    private static final String ID = SET.id();

    private static final String SCREENS = """
            package com.bot.plugins.sdk;

            import com.botmaker.studio.project.managed.TestScreen;
            import com.botmaker.studio.project.managed.TestValue;

            @TestValue(TestValue.Id.SCREENS)
            public final class Screens {
                public static final class Home extends TestScreen {
                    public final String market = "market.png";

                    @Override
                    public String setup() {
                        return "Home";
                    }
                }

                public static final Home HOME = new Home();

                public static final class Market extends TestScreen {
                    public final String buy = "buy.png";
                    public final String close = "close.png";

                    @Override
                    public String setup() {
                        return "Market" + Screens.MARKET.close;
                    }
                }

                public static final Market MARKET = new Market();
            }
            """;

    private static final String BOT = """
            package com.bot;

            import com.bot.plugins.sdk.Screens;

            class Bot {
                String chore() {
                    return Screens.MARKET.buy;
                }
            }
            """;

    @Test
    void theMembersAreEachClassesFieldsAndTheGroupsItsConstants(@TempDir Path root) {
        BotIndex index = index(root);
        assertEquals(List.of("HOME.market", "MARKET.buy", "MARKET.close"),
                ManagedSets.members(index, ID, GRAMMAR).stream().map(ManagedSets.Member::memberName).toList());
        List<NestedClasses.Nested> classes = NestedClasses.all(index, ID, GRAMMAR, "setup");
        assertEquals(List.of("HOME", "MARKET"), classes.stream().map(NestedClasses.Nested::constant).toList());
        assertEquals(List.of("Home", "Market"), classes.stream().map(NestedClasses.Nested::className).toList());
        assertEquals("\"Home\"", classes.getFirst().setup());
        assertEquals("\"buy.png\"", classes.get(1).fields().getFirst().initializer());
        assertEquals("Screens.MARKET.buy", classes.get(1).fields().getFirst().qualified());
    }

    /** A plain set whose constants include a helper class's instance is still a plain set. */
    @Test
    void aHolderWithAnyOtherConstantIsNoSetOfClasses(@TempDir Path root) {
        String mixed = SCREENS.replace("public static final Home HOME = new Home();",
                "public static final Home HOME = new Home();\n    public static final String TITLE = \"t\";");
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(screens(root), mixed);
        BotIndex index = BotIndex.over(sources, List.of(locationOf(TestValue.class)), root);
        assertEquals(List.of("HOME", "TITLE", "MARKET"),
                ManagedSets.members(index, ID, GRAMMAR).stream().map(ManagedSets.Member::name).toList());
        assertTrue(NestedClasses.all(index, ID, GRAMMAR, "setup").isEmpty());
    }

    @Test
    void aFieldIsAddedRemovedAndRenamedInItsClass(@TempDir Path root) {
        BotIndex index = index(root);
        Path screens = screens(root);
        JavaValue sell = GRAMMAR.spellAny("sell.png").orElseThrow();
        String added = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.add(index, ID, "MARKET.sell", String.class, sell)).rewrites().get(screens);
        assertTrue(added.contains("public final String close = \"close.png\";\n        public final String sell = "
                + "\"sell.png\";"), added);
        assertInstanceOf(Refactor.Refused.class, ManagedSets.add(index, ID, "MARKET.buy", String.class, sell),
                "a second buy does not compile");

        Refactor.Refused used = assertInstanceOf(Refactor.Refused.class, ManagedSets.remove(index, field(index,
                "MARKET.buy")));
        assertTrue(used.reason().contains("is still used 1 time"), used.reason());
        String removed = assertInstanceOf(Refactor.Planned.class,
                ManagedSets.remove(index, field(index, "HOME.market"))).rewrites().get(screens);
        assertFalse(removed.contains("market.png"), removed);

        ManagedSets.Member buy = field(index, "MARKET.buy");
        Map<Path, String> renamed = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, buy.file(), buy.start(), "purchase")).rewrites();
        assertTrue(renamed.get(root.resolve("Bot.java")).contains("Screens.MARKET.purchase"), renamed.toString());
        assertInstanceOf(Refactor.Refused.class, ManagedSets.moveBefore(index, field(index, "MARKET.close"), buy),
                "a field keeps its class's order");
    }

    /** Studio's rename of a set's member, asked from a use: planned as the plugin's own rename (2026-10-22). */
    @Test
    void aRenameAskedFromAUseIsTheSetsOwn(@TempDir Path root) {
        BotIndex index = index(root);
        Path bot = root.resolve("Bot.java");
        Map<Path, String> renamed = assertInstanceOf(Refactor.Planned.class, ManagedRenames.plan(index, bot,
                BOT.indexOf("MARKET"), "BAZAAR", List.of(SET), GRAMMAR).orElseThrow()).rewrites();
        assertTrue(renamed.get(screens(root)).contains("public static final Bazaar BAZAAR = new Bazaar();"),
                "the class is renamed with its constant");
        assertTrue(renamed.get(bot).contains("Screens.BAZAAR.buy"), renamed.toString());

        Map<Path, String> field = assertInstanceOf(Refactor.Planned.class, ManagedRenames.plan(index, bot,
                BOT.indexOf("buy"), "purchase", List.of(SET), GRAMMAR).orElseThrow()).rewrites();
        assertTrue(field.get(bot).contains("Screens.MARKET.purchase"), field.toString());

        assertTrue(ManagedRenames.plan(index, bot, BOT.indexOf("chore"), "task", List.of(SET), GRAMMAR).isEmpty(),
                "the bot's own method is no member of a set");
        assertTrue(ManagedRenames.plan(index, bot, BOT.indexOf("MARKET"), "BAZAAR", List.of(), GRAMMAR).isEmpty(),
                "no plugin declares the set: an ordinary rename");
    }

    @Test
    void aClassIsAddedWithItsConstantAndSetup(@TempDir Path root) {
        BotIndex index = index(root);
        JavaValue setup = GRAMMAR.spellAny("Shop").orElseThrow();
        String added = assertInstanceOf(Refactor.Planned.class, NestedClasses.add(index, ID, "FAVORED_SHOP",
                TestScreen.class, SET.setup(), setup, GRAMMAR)).rewrites().get(screens(root));
        assertTrue(added.contains("public static final class FavoredShop extends TestScreen"), added);
        assertTrue(added.contains("public static final FavoredShop FAVORED_SHOP = new FavoredShop();"), added);
        assertTrue(added.contains("@Override"), added);
        assertTrue(added.contains("return \"Shop\";"), added);
        assertTrue(added.indexOf("FAVORED_SHOP") > added.indexOf("MARKET = new Market()"), "after the last class");
        assertInstanceOf(Refactor.Refused.class, NestedClasses.add(index, ID, "MARKET", TestScreen.class,
                SET.setup(), setup, GRAMMAR), "MARKET is taken");
    }

    @Test
    void aClassIsRenamedWithItsConstantAndRemovedOnlyWhenNothingOutsideUsesIt(@TempDir Path root) {
        BotIndex index = index(root);
        NestedClasses.Nested market = NestedClasses.named(index, ID, "MARKET", GRAMMAR, "setup").orElseThrow();
        Map<Path, String> renamed = assertInstanceOf(Refactor.Planned.class,
                NestedClasses.rename(index, market, "BAZAAR")).rewrites();
        String screens = renamed.get(screens(root));
        assertTrue(screens.contains("public static final Bazaar BAZAAR = new Bazaar();"), screens);
        assertTrue(screens.contains("class Bazaar extends TestScreen"), screens);
        assertTrue(screens.contains("Screens.BAZAAR.close"), screens);
        assertTrue(renamed.get(root.resolve("Bot.java")).contains("Screens.BAZAAR.buy"), renamed.toString());

        Refactor.Refused used = assertInstanceOf(Refactor.Refused.class, NestedClasses.remove(index, market));
        // Screens.MARKET.buy uses the constant and the field: two uses, one line.
        assertTrue(used.reason().contains("Screens.MARKET is still used 2 times, in Bot.java:7"), used.reason());
        NestedClasses.Nested home = NestedClasses.named(index, ID, "HOME", GRAMMAR, "setup").orElseThrow();
        String removed = assertInstanceOf(Refactor.Planned.class, NestedClasses.remove(index, home)).rewrites()
                .get(screens(root));
        assertFalse(removed.contains("Home"), removed);
        assertTrue(removed.contains("class Market"), removed);
    }

    @Test
    void aSetupAndAFieldAreWrittenInPlace() {
        JavaValue shop = GRAMMAR.spellAny("Shop").orElseThrow();
        String setup = NestedClasses.setupSet(SCREENS, "Screens", "HOME", "setup", shop);
        assertTrue(setup.contains("return \"Shop\";"), setup);
        assertTrue(setup.contains("return \"Market\" + Screens.MARKET.close;"), "the other class is untouched");
        String field = NestedClasses.fieldSet(SCREENS, "Screens", "MARKET", "close", shop);
        assertTrue(field.contains("public final String close = \"Shop\";"), field);
        assertEquals(SCREENS, NestedClasses.setupSet(SCREENS, "Screens", "NOWHERE", "setup", shop));
        assertEquals("FavoredShop", NestedClasses.className("FAVORED_SHOP"));
    }

    @Test
    void aFieldReferenceReadsAsThePluginsValueAndIsWrittenBack() {
        List<ManagedConstants.Constant> constants = ManagedConstants.read(SCREENS, List.of(SET));
        ManagedConstants.Constant buy = constants.stream().filter(c -> "buy".equals(c.field())).findFirst()
                .orElseThrow();
        assertEquals("MARKET.buy", buy.memberName());
        assertEquals(new TestScreen.Field("MARKET", "buy"), buy.member());
        assertEquals(new TestScreen.Named("MARKET"), constants.stream().filter(c -> "MARKET".equals(c.field()))
                .findFirst().orElseThrow().member(), "a class's constant is read by its name");
        assertNull(constants.stream().filter(c -> "setup".equals(c.field())).findFirst().orElse(null));

        Object read = ConstantValues.read(GRAMMAR, () -> constants, TestScreen.Field.class,
                "com.bot.plugins.sdk.Screens.MARKET.buy").orElseThrow();
        assertEquals(new TestScreen.Field("MARKET", "buy"), read);
        JavaValue written = ConstantValues.write(GRAMMAR, () -> constants, TestScreen.Field.class,
                new TestScreen.Field("HOME", "market")).orElseThrow();
        assertEquals("Screens.HOME.market", written.source());
        assertTrue(written.imports().contains("com.bot.plugins.sdk.Screens"), written.imports().toString());
        assertEquals(new TestScreen.Named("MARKET"), ConstantValues.read(GRAMMAR, () -> constants,
                TestScreen.class, "com.bot.plugins.sdk.Screens.MARKET").orElseThrow());
    }

    private static ManagedSets.Member field(BotIndex index, String name) {
        return ManagedSets.member(index, ID, name, GRAMMAR).orElseThrow();
    }

    private static Path screens(Path root) {
        return root.resolve("Screens.java").toAbsolutePath().normalize();
    }

    private static BotIndex index(Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(screens(root), SCREENS);
        sources.put(root.resolve("Bot.java").toAbsolutePath().normalize(), BOT);
        return BotIndex.over(sources, List.of(locationOf(TestValue.class)), root);
    }

    private static String locationOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
