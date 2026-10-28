package com.botmaker.studio.migrate;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A plugin's own {@code @Migrate} rules, read from its sources jar (2026-10-28): a fixture plugin whose release N
 * marks {@code Screen.marker} deprecated and ships its rule, and whose release N+1 removes both. The bot is
 * migrated on N, where the element still compiles, and from N to N+1, where it is gone.
 */
class PluginRulesTest {

    @TempDir
    static Path dir;

    private static Path deprecatedJar;
    private static Path deprecatedSources;
    private static Path twinsJar;
    private static Path twinsSources;
    private static Path removedJar;
    private static String contract;

    private static final String SCREEN_DEPRECATED = """
            package lib;
            public class Screen {
                public static Screen named(String name) { return new Screen(); }
                @Deprecated(forRemoval = true)
                public Screen marker(String marker) { return this; }
                public Screen markers(java.util.List<String> markers) { return this; }
            }
            """;
    private static final String SCREEN_REMOVED = """
            package lib;
            public class Screen {
                public static Screen named(String name) { return new Screen(); }
                public Screen markers(java.util.List<String> markers) { return this; }
            }
            """;
    private static final String RULES = """
            package lib;

            import com.botmaker.plugin.api.meta.Migrate;
            import java.util.List;

            final class Migrations {
                @Migrate(Screen.class)
                static Screen marker(@Migrate.Self Screen self, String marker) {
                    return self.markers(List.of(marker));
                }
            }
            """;
    private static final String TWIN = """
            package lib;

            import com.botmaker.plugin.api.meta.Migrate;

            final class MoreMigrations {
                @Migrate(Screen.class)
                static Screen marker(@Migrate.Self Screen self, String marker) {
                    return self;
                }
            }
            """;

    private static final String BOT = """
            package bot;

            import lib.Screen;

            public class Bot {
                static Screen HOME = Screen.named("home").marker("a");
            }
            """;
    private static final String NO_RULES = "package bot;\n\nfinal class Migrations {}\n";
    private static final String OWN_RULE = """
            package bot;

            import com.botmaker.plugin.api.meta.Migrate;
            import lib.Screen;

            final class Migrations {
                @Migrate(Screen.class)
                static Screen marker(@Migrate.Self Screen self, String marker) {
                    return self;
                }
            }
            """;

    @BeforeAll
    static void jars() throws IOException {
        contract = TestSupport.contractOnClasspath();
        deprecatedJar = plugin("deprecated", Map.of("Screen", SCREEN_DEPRECATED, "Migrations", RULES));
        deprecatedSources = sources("deprecated", Map.of("Screen", SCREEN_DEPRECATED, "Migrations", RULES));
        twinsJar = plugin("twins", Map.of("Screen", SCREEN_DEPRECATED, "Migrations", RULES, "MoreMigrations", TWIN));
        twinsSources = sources("twins", Map.of("Screen", SCREEN_DEPRECATED, "Migrations", RULES,
                "MoreMigrations", TWIN));
        removedJar = plugin("removed", Map.of("Screen", SCREEN_REMOVED));
    }

    /** Each plugin jar's sources jar, as the fixture built it; none for the release that removed the rule. */
    private static PluginRules shipping() {
        return new PluginRules((jar, coordinate) -> Optional.ofNullable(Map.of(
                deprecatedJar, deprecatedSources, twinsJar, twinsSources).get(jar)));
    }

    @Test
    void aDeprecatedUseIsListedWithItsPluginsRuleAndRewritten() throws IOException {
        BotIndex now = bot(NO_RULES, deprecatedJar);
        Migration migration = Migration.of(now, now.classpath(), shipping());
        assertTrue(migration.unchanged());

        List<Broken> broken = migration.broken();
        assertEquals(1, broken.size(), broken.toString());
        Broken marker = broken.getFirst();
        assertEquals(Broken.State.WRITTEN, marker.state());
        assertTrue(marker.rule().shipped());
        assertEquals("Fixture deprecated", marker.rule().plugin().name());
        assertEquals("fixture:deprecated", marker.rule().plugin().coordinate().toString());

        Refactor.Planned planned = assertInstanceOf(Refactor.Planned.class, migration.plan(false).outcome());
        String bot = planned.rewrites().values().iterator().next();
        assertTrue(bot.contains("Screen.named(\"home\").markers(List.of(\"a\"))"), bot);
        assertTrue(bot.contains("import java.util.List;"), bot);
        assertEquals(1, planned.rewrites().size(), "only the bot's file is rewritten: " + planned.rewrites().keySet());
    }

    @Test
    void anUpgradePastTheReleaseIsMigratedByTheRuleInTheJarItLeaves() throws IOException {
        BotIndex now = bot(NO_RULES, removedJar);
        Migration migration = Migration.of(now, List.of(deprecatedJar.toString(), contract), shipping());

        List<Broken> broken = migration.broken();
        assertEquals(1, broken.size(), broken.toString());
        assertEquals(Broken.State.WRITTEN, broken.getFirst().state());
        assertTrue(broken.getFirst().rule().shipped());

        Refactor.Planned planned = assertInstanceOf(Refactor.Planned.class, migration.plan(false).outcome());
        Map<Path, String> after = new LinkedHashMap<>(now.sources());
        after.putAll(planned.rewrites());
        assertEquals(List.of(), now.replacing(after).errors().values().stream().flatMap(List::stream).toList());
    }

    @Test
    void theAuthorsRuleWinsOverThePluginsForTheSameElement() throws IOException {
        BotIndex now = bot(OWN_RULE, deprecatedJar);
        Migration migration = Migration.of(now, now.classpath(), shipping());

        assertEquals(1, migration.rules().size(), migration.rules().toString());
        Rule only = migration.rules().getFirst();
        assertFalse(only.shipped());
        assertNull(only.problem());
        assertEquals(Broken.State.WRITTEN, migration.broken().getFirst().state());
        assertFalse(migration.broken().getFirst().rule().shipped());
    }

    @Test
    void twoPluginRulesForOneElementAreBothRefused() throws IOException {
        BotIndex now = bot(NO_RULES, twinsJar);
        Migration migration = Migration.of(now, now.classpath(), shipping());

        assertEquals(2, migration.rules().size(), migration.rules().toString());
        for (Rule rule : migration.rules()) {
            assertTrue(rule.shipped());
            assertNotNull(rule.problem());
            assertTrue(rule.problem().contains("names the same element"), rule.problem());
        }
        assertEquals(Broken.State.REFUSED, migration.broken().getFirst().state());
    }

    @Test
    void aPluginWithNoSourcesJarShipsNoRule() throws IOException {
        BotIndex now = bot(NO_RULES, removedJar);
        PluginRules none = new PluginRules((jar, coordinate) -> Optional.empty());
        Migration migration = Migration.of(now, List.of(deprecatedJar.toString(), contract), none);

        assertEquals(List.of(), migration.rules());
        assertEquals(Broken.State.NONE, migration.broken().getFirst().state());
    }

    @Test
    void aClasspathIsWorthACheckUntilItsJarsAreReadAndNoneShipsARule() {
        PluginRules rules = shipping();
        List<String> without = List.of(removedJar.toString(), contract);
        assertTrue(rules.mayShip(without), "a jar not read yet may ship one");
        assertFalse(rules.ships(without));
        assertFalse(rules.mayShip(without), "read, and none does");
        assertTrue(rules.ships(List.of(deprecatedJar.toString())));
        assertTrue(rules.mayShip(List.of(deprecatedJar.toString())));

        PluginRules fresh = shipping();
        assertTrue(fresh.ships(List.of(deprecatedJar.toString(), removedJar.toString())));
        assertFalse(fresh.mayShip(List.of(removedJar.toString())),
                "ships reads every jar, not only up to the first that ships");
    }

    @Test
    void anEntryThatClimbsOutOfItsPluginsFolderIsNotRead() throws IOException {
        Path escaping = dir.resolve("escaping-1.0-sources.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(escaping))) {
            out.putNextEntry(new JarEntry("../../../src/main/java/bot/Bot.java"));
            out.write(RULES.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        PluginRules rules = new PluginRules((jar, coordinate) -> Optional.of(escaping));
        assertFalse(rules.ships(List.of(deprecatedJar.toString())));
    }

    @Test
    void aShippedRuleIsNeverFinishedNorWrittenFromTheBot() throws IOException {
        BotIndex now = bot(NO_RULES, deprecatedJar);
        Migration migration = Migration.of(now, now.classpath(), shipping());
        Rule shipped = migration.rules().getFirst();

        assertEquals(List.of(), migration.done(List.of()));
        assertInstanceOf(Refactor.Refused.class, migration.write(shipped, "self", List.of()));
    }

    // --- the fixture ----------------------------------------------------------------------------------------------

    private static BotIndex bot(String rules, Path pluginJar) throws IOException {
        Path root = Files.createTempDirectory(dir, "bot").resolve("src/main/java");
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("bot/Bot.java").toAbsolutePath().normalize(), BOT);
        sources.put(root.resolve("bot/Migrations.java").toAbsolutePath().normalize(), rules);
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            Files.writeString(entry.getKey(), entry.getValue());
        }
        return BotIndex.over(sources, List.of(pluginJar.toString(), contract), root);
    }

    /** The plugin's jar: its classes compiled against the contract, and a pom that names it a plugin. */
    private static Path plugin(String label, Map<String, String> classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jars cannot be built");
        Path src = written(label, classes);
        Path out = Files.createDirectories(dir.resolve("classes-" + label));
        List<String> args = new ArrayList<>(List.of("-cp", contract, "-d", out.toString()));
        try (Stream<Path> files = Files.list(src)) {
            files.forEach(file -> args.add(file.toString()));
        }
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), label + " must compile");
        Path pom = out.resolve("META-INF/maven/fixture/" + label + "/pom.xml");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>fixture</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                  <name>Fixture %s</name>
                  <properties><botmaker.plugin>lib.Plugin</botmaker.plugin></properties>
                </project>
                """.formatted(label, label));
        return jarred(out, dir.resolve(label + "-1.0.jar"));
    }

    /** The plugin's sources jar: each class's {@code .java} under its package. */
    private static Path sources(String label, Map<String, String> classes) throws IOException {
        Path src = written(label, classes);
        return jarred(src.getParent(), dir.resolve(label + "-1.0-sources.jar"));
    }

    private static Path written(String label, Map<String, String> classes) throws IOException {
        Path src = Files.createDirectories(dir.resolve("src-" + label + "/lib"));
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            Files.writeString(src.resolve(entry.getKey() + ".java"), entry.getValue(), StandardCharsets.UTF_8);
        }
        return src;
    }

    private static Path jarred(Path from, Path jar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(from)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(from.relativize(file).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(file));
                out.closeEntry();
            }
        }
        return jar;
    }
}
