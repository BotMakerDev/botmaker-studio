package com.botmaker.studio.project.managed;

import com.botmaker.core.project.managed.TestValue;
import com.botmaker.plugin.api.source.ManagedValue;
import com.botmaker.plugin.host.PluginCoordinate;
import com.botmaker.java.index.nav.Refactor;
import com.botmaker.core.project.PluginFiles;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.index.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A plugin's holders known by their mark outside the folder Studio writes them in, and moved into it on request. */
class HolderMoveTest {

    private static final PluginCoordinate SDK = new PluginCoordinate("com.github.BotMakerDev", "botmaker-sdk");
    private static final List<ManagedValue<?>> DECLARED = List.of(
            ManagedValue.method(TestValue.Id.GREETING).in(TestValue.Holder.SDK).holds(String.class, null).because("Mine."));
    private static final List<PluginFiles.Holder> HOLDERS = List.of(
            new PluginFiles.Holder(SDK, "SDK", "com_github_botmakerdev.botmaker_sdk", "Sdk", DECLARED));

    private static final String MARKED = """
            package com.bot.plugins.sdk;

            import %s;

            public final class Sdk {

                @TestValue(TestValue.Id.GREETING)
                public static String greeting() {
                    return "hi";
                }
            }
            """.formatted(TestValue.class.getCanonicalName());

    private static final String BATTLE = """
            package com.bot;

            import com.bot.plugins.sdk.Sdk;

            public final class Battle {

                static String both() {
                    return Sdk.greeting() + com.bot.plugins.sdk.Sdk.greeting();
                }
            }
            """;

    @TempDir
    Path dir;

    private ProjectConfig config() {
        return ProjectConfig.forDirectory(dir.resolve("bot"));
    }

    private Path java(String relative) {
        return config().mainPackageDir().resolve(relative).toAbsolutePath().normalize();
    }

    private BotIndex index(Map<Path, String> sources) {
        return BotIndex.over(new LinkedHashMap<>(sources),
                Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)), dir.resolve("bot"));
    }

    @Test
    void aHolderInAnOldFolderIsThePluginsByItsMark() {
        assertEquals(Optional.of(HOLDERS.getFirst()), PluginFiles.owner(java("plugins/sdk/Sdk.java"), MARKED, HOLDERS));
        assertEquals(Optional.empty(), PluginFiles.owner(java("plugins/sdk/Sdk.java"),
                "package com.bot.plugins.sdk;\npublic final class Sdk {}\n", HOLDERS));
        assertEquals(Optional.empty(), PluginFiles.owner(java("Battle.java"), BATTLE, HOLDERS));
    }

    @Test
    void theMoveTakesTheHolderToItsFolderAndEveryNameOfItWithIt() {
        Path from = java("plugins/sdk/Sdk.java");
        Path battle = java("Battle.java");
        Path to = java("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                HolderMove.plan(index(Map.of(from, MARKED, battle, BATTLE)), config(), HOLDERS).orElseThrow());

        assertEquals(Map.of(from, to), plan.moves());
        String newName = "com.bot.plugins.com_github_botmakerdev.botmaker_sdk";
        assertTrue(plan.rewrites().get(from).startsWith("package " + newName + ";"), plan.rewrites().get(from));
        String rewritten = plan.rewrites().get(battle);
        assertTrue(rewritten.contains("import " + newName + ".Sdk;"), rewritten);
        assertTrue(rewritten.contains("Sdk.greeting() + " + newName + ".Sdk.greeting()"), rewritten);
        assertTrue(HolderMove.offer(config(), plan).startsWith("A plugin file is in plugins/sdk/"),
                HolderMove.offer(config(), plan));
    }

    @Test
    void aClassLeftInTheOldPackageIsImportedOnBothSides() {
        Path from = java("plugins/sdk/Sdk.java");
        Path helper = java("plugins/sdk/Helper.java");
        String usesHelper = MARKED.replace("return \"hi\";", "return Helper.word();");
        String helperText = """
                package com.bot.plugins.sdk;

                public final class Helper {
                    public static String word() { return Sdk.class.getSimpleName(); }
                }
                """;

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                HolderMove.plan(index(Map.of(from, usesHelper, helper, helperText)), config(), HOLDERS).orElseThrow());

        assertTrue(plan.rewrites().get(from).contains("import com.bot.plugins.sdk.Helper;"), plan.rewrites().get(from));
        assertTrue(plan.rewrites().get(helper).contains(
                "import com.bot.plugins.com_github_botmakerdev.botmaker_sdk.Sdk;"), plan.rewrites().get(helper));
    }

    @Test
    void anOnDemandImportOfTheEmptiedPackageNamesTheNewOne() {
        Path from = java("plugins/sdk/Sdk.java");
        Path battle = java("Battle.java");
        String star = BATTLE.replace("import com.bot.plugins.sdk.Sdk;", "import com.bot.plugins.sdk.*;");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                HolderMove.plan(index(Map.of(from, MARKED, battle, star)), config(), HOLDERS).orElseThrow());

        String rewritten = plan.rewrites().get(battle);
        assertTrue(rewritten.contains("import com.bot.plugins.com_github_botmakerdev.botmaker_sdk.*;"), rewritten);
        assertTrue(!rewritten.contains("import com.bot.plugins.sdk.*;"), rewritten);
        assertTrue(HolderMove.anyOutside(Map.of(from, MARKED, battle, star), HOLDERS));
    }

    @Test
    void aHolderInItsFolderIsNotMoved() {
        Path at = java("plugins/com_github_botmakerdev/botmaker_sdk/Sdk.java");
        String text = MARKED.replace("com.bot.plugins.sdk", "com.bot.plugins.com_github_botmakerdev.botmaker_sdk");

        assertEquals(Optional.empty(), HolderMove.plan(index(Map.of(at, text)), config(), HOLDERS));
    }
}
