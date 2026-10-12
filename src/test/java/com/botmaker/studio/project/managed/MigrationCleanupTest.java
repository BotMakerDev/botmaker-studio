package com.botmaker.studio.project.managed;

import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.plugin.grammar.ValueGrammar;
import com.botmaker.studio.project.managed.MigrationTypes.OldShot;
import com.botmaker.studio.project.managed.MigrationTypes.Shot;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After a migration: uses switched to the copies without ever breaking the bot, originals nothing uses removed, and
 * the {@code @Migrate} methods removed once no old value is left — the class with them when it holds nothing else.
 */
@SuppressWarnings("removal")
class MigrationCleanupTest {

    private static final ValueGrammar GRAMMAR = ValueGrammar.of(MigrationTypes.types(), List.of());

    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.openSet(TestValue.Id.OLD_SHOTS).of(OldShot.class).in("OldShots").because("Old."),
            ManagedValue.openSet(TestValue.Id.SHOTS).of(Shot.class).in("Shots").because("New."));

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

            import com.botmaker.plugin.api.meta.Migrated;
            import com.botmaker.studio.project.managed.TestValue;
            import com.botmaker.studio.project.managed.MigrationTypes.Shot;

            @TestValue(TestValue.Id.SHOTS)
            public final class Shots {
                @Migrated public static final Shot ORE = new Shot("ore.png", 80, false);
                @Migrated public static final Shot GOLD = new Shot("gold.png", 90, false);

                private Shots() {}
            }
            """;

    /** {@code first} only reads the value; {@code held} returns it as the old type, which a Shot does not fit. */
    private static final String MINER = """
            package com.bot;

            import com.bot.plugins.sdk.OldShots;
            import com.botmaker.studio.project.managed.MigrationTypes.OldShot;

            public final class Miner {
                static Object first() {
                    return OldShots.ORE;
                }

                static OldShot held() {
                    return OldShots.GOLD;
                }
            }
            """;

    private static final String MIGRATIONS = """
            package com.bot;

            import com.botmaker.plugin.api.meta.Migrate;
            import com.botmaker.studio.project.managed.MigrationTypes.*;

            public final class Migrations {
                private Migrations() {
                }

                @Migrate(OldShot.class)
                public static Shot shot(String file, int threshold) {
                    return new Shot(file, threshold, false);
                }
            }
            """;

    @Test
    void aUseIsSwitchedOnlyWhereTheCopyFits(@TempDir Path root) {
        BotIndex index = index(root, MINER);
        MigrationCleanup.Switched switched = MigrationCleanup.switchUses(index, GRAMMAR, DECLARED, OldShot.class,
                Shot.class, Set.of(), "Still uses the old value: change what holds it, then switch again.");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, switched.outcome(),
                switched.outcome()::toString);
        String miner = plan.rewrites().get(file(root, "Miner"));
        assertTrue(miner.contains("return Shots.ORE;"), miner);
        assertTrue(miner.contains("return OldShots.GOLD;"), "a Shot is no OldShot: " + miner);
        assertTrue(miner.contains("@Refactor"), "the use left is listed in Review: " + miner);
        assertEquals(1, switched.switched().size(), switched.switched()::toString);
        assertEquals(1, switched.left().size(), switched.left()::toString);
        assertTrue(switched.left().getFirst().contains("OldShots.GOLD"), switched.left()::toString);
    }

    @Test
    void oneValueAloneIsSwitchedWhenNamed(@TempDir Path root) {
        BotIndex index = index(root, MINER);
        MigrationCleanup.Switched switched = MigrationCleanup.switchUses(index, GRAMMAR, DECLARED, OldShot.class,
                Shot.class, Set.of("GOLD"), null);

        assertInstanceOf(Refactor.Refused.class, switched.outcome(), "GOLD's only use does not fit a Shot");
        assertEquals(1, switched.left().size(), switched.left()::toString);
    }

    @Test
    void anOriginalNothingUsesIsRemovableAndRemoved(@TempDir Path root) {
        BotIndex index = after(index(root, MINER), MigrationCleanup.switchUses(index(root, MINER), GRAMMAR, DECLARED,
                OldShot.class, Shot.class, Set.of(), null).outcome());

        List<ManagedSets.Member> removable = MigrationCleanup.removable(index, GRAMMAR, DECLARED, OldShot.class,
                Shot.class);
        assertEquals(List.of("ORE"), removable.stream().map(ManagedSets.Member::name).toList(), "GOLD is still used");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                MigrationCleanup.removeOriginals(index, removable));
        String old = plan.rewrites().get(file(root, "plugins/sdk/OldShots"));
        assertFalse(old.contains(" ORE "), old);
        assertTrue(old.contains("GOLD"), old);
    }

    @Test
    void theMethodsGoOnceNoOldValueIsLeftAndTheEmptyClassWithThem(@TempDir Path root) {
        BotIndex index = index(root, MINER);
        assertEquals(List.of(), MigrationCleanup.unneeded(index, GRAMMAR), "OldShots still has values");

        String emptied = OLD_SHOTS.replace("    public static final OldShot ORE = new OldShot(\"ore.png\", 80);\n", "")
                .replace("    public static final OldShot GOLD = new OldShot(\"gold.png\", 90);\n", "");
        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        sources.put(file(root, "plugins/sdk/OldShots"), emptied);
        sources.remove(file(root, "Miner"));
        BotIndex done = index.replacing(sources);

        List<ManagedMigrations.Method> unneeded = MigrationCleanup.unneeded(done, GRAMMAR);
        assertEquals(List.of("Migrations.shot"), unneeded.stream().map(ManagedMigrations.Method::qualified).toList());
        MigrationCleanup.Removal removal = MigrationCleanup.removeMethods(done, unneeded);
        assertInstanceOf(Refactor.Planned.class, removal.outcome(), removal.outcome()::toString);
        assertEquals(Set.of(file(root, "Migrations")), removal.deleted(), "nothing else was in it");
    }

    @Test
    void aMethodBesideTheOldValuesItMapsIsStillNeeded(@TempDir Path root) {
        String beside = OLD_SHOTS.replace("    private OldShots() {}", """
                    private OldShots() {}

                    @com.botmaker.plugin.api.meta.Migrate(OldShot.class)
                    public static com.botmaker.studio.project.managed.MigrationTypes.Shot shot(String file,
                                                                                         int threshold) {
                        return new com.botmaker.studio.project.managed.MigrationTypes.Shot(file, threshold, false);
                    }
                """);
        Map<Path, String> sources = new LinkedHashMap<>(index(root, MINER).sources());
        sources.put(file(root, "plugins/sdk/OldShots"), beside);
        sources.remove(file(root, "Migrations"));
        sources.remove(file(root, "Miner"));
        BotIndex index = index(root, MINER).replacing(sources);

        assertEquals(1, ManagedMigrations.methods(index, GRAMMAR).size());
        assertEquals(List.of(), MigrationCleanup.unneeded(index, GRAMMAR), "ORE and GOLD are still in its file");
    }

    @Test
    void aValueOfTheSameNameTheAuthorMadeIsNoCopy(@TempDir Path root) {
        Map<Path, String> sources = new LinkedHashMap<>(index(root, MINER).sources());
        sources.put(file(root, "plugins/sdk/Shots"), SHOTS.replace("@Migrated public static final Shot ORE",
                "public static final Shot ORE"));
        BotIndex index = index(root, MINER).replacing(sources);

        MigrationCleanup.Switched switched = MigrationCleanup.switchUses(index, GRAMMAR, DECLARED, OldShot.class,
                Shot.class, Set.of("ORE"), null);
        assertInstanceOf(Refactor.Refused.class, switched.outcome());
        assertTrue(switched.left().getFirst().contains("not marked @Migrated"), switched.left()::toString);
        assertTrue(MigrationCleanup.copyTarget(index, GRAMMAR, DECLARED, OldShot.class, "ORE").isEmpty());
    }

    @Test
    void aStaticImportIsSwitchedAroundAndGoesWithItsOriginal(@TempDir Path root) {
        String miner = """
                package com.bot;

                import static com.bot.plugins.sdk.OldShots.ORE;

                public final class Miner {
                    static Object first() {
                        return ORE;
                    }
                }
                """;
        BotIndex index = after(index(root, miner), MigrationCleanup.switchUses(index(root, miner), GRAMMAR, DECLARED,
                OldShot.class, Shot.class, Set.of(), null).outcome());
        assertTrue(index.sources().get(file(root, "Miner")).contains("return Shots.ORE;"));

        List<ManagedSets.Member> removable = MigrationCleanup.removable(index, GRAMMAR, DECLARED, OldShot.class,
                Shot.class);
        assertEquals(List.of("ORE", "GOLD"), removable.stream().map(ManagedSets.Member::name).toList(),
                "the import is no use that keeps it");
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                MigrationCleanup.removeOriginals(index, removable), () -> "" + MigrationCleanup.removeOriginals(index,
                        removable));
        assertFalse(plan.rewrites().get(file(root, "Miner")).contains("import static"),
                plan.rewrites().get(file(root, "Miner")));
    }

    @Test
    void theCopysTypeIsFoundByItsName(@TempDir Path root) {
        assertEquals(Shot.class, MigrationCleanup.copyTarget(index(root, MINER), GRAMMAR, DECLARED, OldShot.class,
                "ORE").orElseThrow());
        assertTrue(MigrationCleanup.copyTarget(index(root, MINER), GRAMMAR, DECLARED, OldShot.class, "IRON")
                .isEmpty());
    }

    private static BotIndex after(BotIndex index, Refactor.Outcome outcome) {
        Map<Path, String> sources = new LinkedHashMap<>(index.sources());
        sources.putAll(((Refactor.Planned) outcome).rewrites());
        return index.replacing(sources);
    }

    private static BotIndex index(Path root, String miner) {
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(file(root, "plugins/sdk/OldShots"), OLD_SHOTS);
        files.put(file(root, "plugins/sdk/Shots"), SHOTS);
        files.put(file(root, "Miner"), miner);
        files.put(file(root, "Migrations"), MIGRATIONS);
        return BotIndex.over(files, List.of(locationOf(com.botmaker.plugin.api.meta.Migrate.class),
                locationOf(TestValue.class)), root);
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
