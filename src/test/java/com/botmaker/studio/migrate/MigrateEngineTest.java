package com.botmaker.studio.migrate;

import com.botmaker.studio.TestSupport;
import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Uses;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The author's migration over two real jars — the old API and the new, compiled here — and a bot that uses every
 * kind of element the new one breaks: an instance method, a method gone with nothing in its place, a static one
 * called as a statement, a constructor, a constant and a whole type.
 */
class MigrateEngineTest {

    @TempDir
    static Path dir;

    private static Path oldJar;
    private static Path newJar;

    private static final String SCREEN_OLD = """
            package lib;
            public class Screen {
                public static Screen named(String name) { return new Screen(); }
                public Screen marker(String marker) { return this; }
                public Screen markers(java.util.List<String> markers) { return this; }
            }
            """;
    private static final String SCREEN_NEW = """
            package lib;
            public class Screen {
                public static Screen named(String name) { return new Screen(); }
                public Screen markers(java.util.List<String> markers) { return this; }
            }
            """;
    private static final String RUN_OLD = """
            package lib;
            public class Run {
                public static Run of() { return new Run(); }
                public Run home(Runnable home) { return this; }
                public Run popups(Runnable popups) { return this; }
            }
            """;
    private static final String RUN_NEW = """
            package lib;
            public class Run {
                public static Run of() { return new Run(); }
                public Run home(Runnable home) { return this; }
            }
            """;
    private static final String FINDER_OLD = """
            package lib;
            public final class Finder {
                public static void untilFind(String picture, Runnable step) {}
                public static void repeatUntil(String picture, Runnable step) {}
            }
            """;
    private static final String FINDER_NEW = """
            package lib;
            public final class Finder {
                public static void repeatUntil(String picture, Runnable step) {}
            }
            """;
    private static final String RECT_OLD = """
            package lib;
            public class Rect {
                public Rect(int x, int y) {}
                public Rect(int x, int y, int width, int height) {}
            }
            """;
    private static final String RECT_NEW = """
            package lib;
            public class Rect {
                public Rect(int x, int y, int width, int height) {}
            }
            """;
    private static final String STATE_OLD = "package lib; public enum State { OFF, ON, DISABLED }";
    private static final String STATE_NEW = "package lib; public enum State { ON, DISABLED }";
    private static final String AREA_OLD = """
            package lib;
            public class Area {
                public Area(int x, int y, int width, int height) {}
            }
            """;

    private static final String BOT = """
            package bot;

            import lib.Area;
            import lib.Finder;
            import lib.Rect;
            import lib.Run;
            import lib.Screen;
            import lib.State;

            public class Bot {
                static Screen HOME = Screen.named("home").marker("a").marker("b");

                static Run run() {
                    Runnable r = () -> {};
                    return Run.of().home(r).popups(r);
                }

                static void step() {
                    Finder.untilFind("p", () -> {});
                }

                static Rect rect() {
                    return new Rect(1, 2);
                }

                static State state() {
                    return State.OFF;
                }

                static Area area() {
                    Area a = new Area(1, 2, 3, 4);
                    return a;
                }
            }
            """;

    private static final String RULES = """
            package bot;

            import com.botmaker.plugin.api.meta.Migrate;
            import java.util.List;
            import lib.Finder;
            import lib.Rect;
            import lib.Run;
            import lib.Screen;
            import lib.State;

            public class Migrations {
                @Migrate(Screen.class)
                static Screen marker(@Migrate.Self Screen self, String marker) {
                    return self.markers(List.of(marker));
                }

                @Migrate(Run.class)
                static Run popups(@Migrate.Self Run self, Runnable popups) {
                    return self;
                }

                @Migrate(Finder.class)
                static void untilFind(String picture, Runnable step) {
                    Finder.repeatUntil(picture, step);
                }

                @Migrate(value = Rect.class, kind = Migrate.Kind.CONSTRUCTOR)
                static Rect rect(int x, int y) {
                    return new Rect(x, y, 0, 0);
                }

                @Migrate(value = State.class, kind = Migrate.Kind.FIELD)
                static State OFF() {
                    return State.DISABLED;
                }

                @Migrate(name = "lib.Area", kind = Migrate.Kind.TYPE)
                static Rect area(int x, int y, int width, int height) {
                    return new Rect(x, y, width, height);
                }
            }
            """;

    @BeforeAll
    static void jars() throws IOException {
        oldJar = jar("old", Map.of("Screen", SCREEN_OLD, "Run", RUN_OLD, "Finder", FINDER_OLD, "Rect", RECT_OLD,
                "State", STATE_OLD, "Area", AREA_OLD));
        newJar = jar("new", Map.of("Screen", SCREEN_NEW, "Run", RUN_NEW, "Finder", FINDER_NEW, "Rect", RECT_NEW,
                "State", STATE_NEW));
    }

    @Test
    void everyKindOfUseIsRewrittenThroughItsRuleAndTheBotCompiles() throws IOException {
        Bot bot = bot(BOT, RULES);
        Migration migration = Migration.of(bot.now(), bot.old());
        migration.rules().forEach(rule -> assertNull(rule.problem(), rule.qualified() + " " + rule.problem()));

        Rewrite.Result result = migration.plan(true);
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, result.outcome(),
                () -> result.outcome() + " left " + result.left());
        assertTrue(result.left().isEmpty(), () -> "left " + result.left());
        String text = plan.rewrites().get(bot.botFile());

        assertTrue(text.contains("Screen.named(\"home\").markers(List.of(\"a\")).markers(List.of(\"b\"))"), text);
        assertTrue(text.contains("return Run.of().home(r);"), text);
        assertTrue(text.contains("Finder.repeatUntil(\"p\", () -> {});"), text);
        assertTrue(text.contains("return new Rect(1, 2, 0, 0);"), text);
        assertTrue(text.contains("return State.DISABLED;"), text);
        assertTrue(text.contains("static Rect area()"), text);
        assertTrue(text.contains("Rect a = new Rect(1, 2, 3, 4);"), text);
        assertTrue(text.contains("import java.util.List;"), text);
        assertTrue(!text.contains("import lib.Area;"), text);

        BotIndex after = bot.now().with(plan.rewrites());
        assertEquals(List.of(), after.errors().get(bot.botFile()), text);
    }

    @Test
    void aPreviewShowsEachOutermostUseBeforeAndAfter() throws IOException {
        Bot bot = bot(BOT, RULES);
        Rewrite.Result result = Migration.of(bot.now(), bot.old()).plan(false);
        List<String> changes = result.changes().stream().map(c -> c.before() + " => " + c.after()).toList();
        assertTrue(changes.contains("Screen.named(\"home\").marker(\"a\").marker(\"b\") => "
                + "Screen.named(\"home\").markers(List.of(\"a\")).markers(List.of(\"b\"))"), changes.toString());
        assertTrue(result.changes().stream().noneMatch(c -> c.before().equals("Screen.named(\"home\").marker(\"a\")")),
                changes.toString());
    }

    @Test
    void onceNoUseIsLeftEveryRuleIsFinishedAndRemovingThemDeletesTheirFile() throws IOException {
        Bot bot = bot(BOT, RULES);
        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                Migration.of(bot.now(), bot.old()).plan(false).outcome());
        BotIndex migrated = bot.now().replacing(merged(bot.now().sources(), plan.rewrites()));
        Migration after = Migration.of(migrated, bot.old());
        List<Broken> broken = after.broken();
        assertEquals(List.of(), broken.stream().map(b -> b.element().display()).toList());
        List<Rule> done = after.done(broken);
        assertEquals(after.rules().size(), done.size());

        Migration.Removal removal = after.remove(done);
        assertInstanceOf(Refactor.Planned.class, removal.outcome(), () -> removal.outcome().toString());
        assertEquals(java.util.Set.of(bot.botFile().resolveSibling("Migrations.java")), removal.deleted());
        // Before the uses are rewritten, no rule is finished.
        Migration before = Migration.of(bot.now(), bot.old());
        assertEquals(List.of(), before.done(before.broken()).stream().map(Rule::qualified).toList());
    }

    @Test
    void withNoRuleNothingIsRewrittenAndEveryBreakIsListed() throws IOException {
        Bot bot = bot(BOT, "package bot; public class Migrations {}");
        Migration migration = Migration.of(bot.now(), bot.old());

        assertInstanceOf(Refactor.Refused.class, migration.plan(true).outcome());
        List<String> broken = migration.broken().stream().map(b -> b.element().display() + " " + b.state()).toList();
        assertTrue(broken.contains("Screen#marker(String) NONE"), broken.toString());
        assertTrue(broken.contains("Run#popups(Runnable) NONE"), broken.toString());
        assertTrue(broken.contains("Finder#untilFind(String, Runnable) NONE"), broken.toString());
        assertTrue(broken.contains("new Rect(int, int) NONE"), broken.toString());
        assertTrue(broken.contains("State#OFF NONE"), broken.toString());
        assertTrue(broken.contains("Area NONE"), broken.toString());
        Broken marker = migration.broken().stream().filter(b -> b.element().name().equals("marker")).findFirst()
                .orElseThrow();
        assertEquals(2, marker.sites().size());
    }

    @Test
    void aRuleIsRefusedWhenItNamesNothingOrTwiceOrBothWays() throws IOException {
        String rules = """
                package bot;

                import com.botmaker.plugin.api.meta.Migrate;
                import lib.Run;
                import lib.Screen;

                public class Migrations {
                    @Migrate(value = Screen.class, name = "lib.Screen")
                    static Screen marker(@Migrate.Self Screen self, String marker) { return self; }

                    @Migrate(Screen.class)
                    static Screen marker(String marker) { return null; }

                    @Migrate(Run.class)
                    static Run popups(@Migrate.Self Run self, Runnable popups) { return self; }

                    @Migrate(Run.class)
                    static Run popups(@Migrate.Self Run self, Runnable popups, int twice) { return self; }

                    @Migrate(Run.class)
                    static Run nothing(@Migrate.Self Run self) { return self; }

                    @Migrate(value = lib.Area.class, kind = Migrate.Kind.TYPE)
                    static lib.Rect area(int x, int y, int width, int height) { return null; }
                }
                """;
        Bot bot = bot(BOT, rules);
        Map<String, String> problems = new LinkedHashMap<>();
        Migration.of(bot.now(), bot.old()).rules()
                .forEach(rule -> problems.put(rule.method() + rule.params().size(), String.valueOf(rule.problem())));

        assertTrue(problems.get("marker2").contains("both a class and a name"), problems.toString());
        assertTrue(problems.get("marker1").contains("@Migrate.Self"), problems.toString());
        assertEquals("null", problems.get("popups2"), problems.toString());
        assertTrue(problems.get("popups3").contains("matches no method popups"), problems.toString());
        assertTrue(problems.get("nothing1").contains("matches no method nothing"), problems.toString());
        assertTrue(problems.get("area4").contains("@Migrate(name ="), problems.toString());
    }

    @Test
    void twoRulesForOneElementAreBothRefused() throws IOException {
        String rules = """
                package bot;

                import com.botmaker.plugin.api.meta.Migrate;
                import lib.Run;

                public class Migrations {
                    @Migrate(Run.class)
                    static Run popups(@Migrate.Self Run self, Runnable popups) { return self; }
                }
                """;
        String more = """
                package bot;

                import com.botmaker.plugin.api.meta.Migrate;
                import lib.Run;

                public class MoreMigrations {
                    @Migrate(Run.class)
                    static Run popups(@Migrate.Self Run run, Runnable ignored) { return run; }
                }
                """;
        Bot bot = bot(BOT, rules, Map.of("MoreMigrations", more));
        List<Rule> read = Migration.of(bot.now(), bot.old()).rules();
        assertEquals(2, read.size());
        read.forEach(rule -> assertTrue(String.valueOf(rule.problem()).contains("names the same element"),
                rule.qualified() + " " + rule.problem()));
    }

    @Test
    void aUseNoRuleCanStandAtIsLeftAndItsFunctionMarked() throws IOException {
        String bot = """
                package bot;

                import java.util.function.BiFunction;
                import lib.Screen;

                public class Bot {
                    static BiFunction<Screen, String, Screen> mark() {
                        return Screen::marker;
                    }

                    static Screen home() {
                        return Screen.named("home").marker("a");
                    }
                }
                """;
        Bot project = bot(bot, RULES);
        Rewrite.Result result = Migration.of(project.now(), project.old()).plan(true);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, result.outcome());
        assertEquals(1, result.applied().size());
        assertEquals(1, result.left().size());
        assertEquals(Uses.Shape.REFERENCE, result.left().getFirst().site().shape());
        String text = plan.rewrites().get(project.botFile());
        assertTrue(text.contains("@Refactor(\"Migrations.marker could not rewrite Screen#marker(String) here"), text);
        assertTrue(text.contains("return Screen.named(\"home\").markers(List.of(\"a\"));"), text);
    }

    @Test
    void aSeededRuleIsListedAndNeverApplied() throws IOException {
        String rules = """
                package bot;

                import com.botmaker.plugin.api.meta.Migrate;
                import com.botmaker.plugin.api.meta.Refactor;
                import lib.Run;

                public class Migrations {
                    @Refactor("write the replacement")
                    @Migrate(Run.class)
                    static Run popups(@Migrate.Self Run self, Runnable popups) { return self; }
                }
                """;
        Bot bot = bot(BOT, rules);
        Migration migration = Migration.of(bot.now(), bot.old());
        Broken popups = migration.broken().stream().filter(b -> b.element().name().equals("popups")).findFirst()
                .orElseThrow();
        assertEquals(Broken.State.SEEDED, popups.state());
        assertInstanceOf(Refactor.Refused.class, migration.plan(true).outcome());
    }

    /**
     * The last resort, an upgrade the author finishes anyway: each broken element no rule names gets a default rule
     * seeded into Migrations and applied — the receiver for a method giving its owner back, nothing for a call that
     * returns nothing, the type's default otherwise, a {@code new} of a gone type too — and a whole type, which has
     * no default, is left and marked.
     */
    @Test
    void finishingAnywaySeedsADefaultRuleForEachElementNoRuleCovers() throws IOException {
        String rules = """
                package bot;

                import com.botmaker.plugin.api.meta.Migrate;
                import java.util.List;
                import lib.Screen;

                public class Migrations {
                    @Migrate(Screen.class)
                    static Screen marker(@Migrate.Self Screen self, String marker) {
                        return self.markers(List.of(marker));
                    }
                }
                """;
        Bot bot = bot(BOT, rules);
        Path file = bot.botFile().resolveSibling("Migrations.java");
        Migration.Finished finished = Migration.of(bot.now(), bot.old()).finished(file, "bot", true);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, finished.result().outcome(),
                () -> finished.result().outcome() + " left " + finished.result().left());
        List<String> seeded = finished.seeded().stream().map(OldElement::display).toList();
        assertTrue(seeded.containsAll(List.of("Run#popups(Runnable)", "Finder#untilFind(String, Runnable)",
                "new Rect(int, int)", "State#OFF", "new Area(int, int, int, int)")), seeded.toString());
        assertTrue(finished.unseeded().keySet().stream().anyMatch(e -> e.display().equals("Area")),
                finished.unseeded().toString());

        String migrations = plan.rewrites().get(file);
        assertTrue(migrations.contains("@Refactor(\"" + Seed.MARK + "\")"), migrations);
        assertTrue(migrations.contains("static Run popups(@Migrate.Self Run self, Runnable runnable)"), migrations);
        assertTrue(migrations.contains("return self;"), migrations);
        assertTrue(migrations.contains("static void untilFind(String string, Runnable runnable)"), migrations);
        assertTrue(migrations.contains("@Migrate(name = \"lib.Area\", kind = Migrate.Kind.CONSTRUCTOR)"), migrations);

        String text = plan.rewrites().get(bot.botFile());
        assertTrue(text.contains("Screen.named(\"home\").markers(List.of(\"a\")).markers(List.of(\"b\"))"), text);
        assertTrue(text.contains("return Run.of().home(r);"), text);
        assertTrue(!text.contains("untilFind"), "a call that returns nothing is deleted:\n" + text);
        assertTrue(!text.contains("new Rect(1, 2)") && !text.contains("State.OFF"), text);
        assertTrue(text.contains("@Refactor(\"Area is gone and was left as written here"), text);

        BotIndex after = bot.now().replacing(merged(bot.now().sources(), plan.rewrites()));
        assertEquals(List.of(), after.errors().getOrDefault(file, List.of()), migrations);
        assertTrue(after.errors().getOrDefault(bot.botFile(), List.of()).stream()
                .allMatch(problem -> problem.message().contains("Area")), () -> after.errors().toString());
    }

    /**
     * A removal is the same last resort with the plugin's jar gone: a call is deleted or defaulted, an import of a
     * type nothing names any more goes, and a type the bot writes down is left as written and marked.
     */
    @Test
    void aRemovalDeletesCallsDropsDeadImportsAndMarksWhatItLeaves() throws IOException {
        String source = """
                package bot;

                import lib.Finder;
                import lib.Rect;
                import lib.State;

                public class Bot {
                    static void step() {
                        Finder.untilFind("p", () -> {});
                    }

                    static int count() {
                        Rect r = new Rect(1, 2);
                        return 1;
                    }
                }
                """;
        Bot bot = bot(source, "package bot; public class Migrations {}", Map.of(), false);
        Path file = bot.botFile().resolveSibling("Migrations.java");
        Migration.Finished finished = Migration.of(bot.now(), bot.old()).finished(file, "bot", true);

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, finished.result().outcome(),
                () -> finished.result().outcome() + " left " + finished.result().left());
        String text = plan.rewrites().get(bot.botFile());
        assertTrue(!text.contains("untilFind"), text);
        assertTrue(!text.contains("import lib.Finder;"), "its only use went with the call:\n" + text);
        assertTrue(!text.contains("import lib.State;"), "an import of a gone class nothing uses goes too:\n" + text);
        assertTrue(text.contains("import lib.Rect;") && text.contains("Rect r = null;"), text);
        assertTrue(text.contains("@Refactor(\"Rect is gone and was left as written here"), text);
        assertTrue(finished.result().left().stream().allMatch(left -> left.site().element().display().equals("Rect")),
                finished.result().left().toString());
        String migrations = plan.rewrites().get(file);
        assertTrue(migrations.contains("@Migrate(name = \"lib.Finder\")"), migrations);
    }

    private static Map<Path, String> merged(Map<Path, String> sources, Map<Path, String> rewrites) {
        Map<Path, String> out = new LinkedHashMap<>(sources);
        out.putAll(rewrites);
        return out;
    }

    @Test
    void theCacheKeepsASnapshotJarsBytesFromBeforeItsRebuild() throws IOException {
        Path cache = dir.resolve("api-cache");
        Path folder = Files.createDirectories(dir.resolve("m2/lib/1.0-SNAPSHOT"));
        Path jar = folder.resolve("lib-1.0-SNAPSHOT.jar");
        Path project = dir.resolve("project");
        Files.copy(oldJar, jar);
        ApiCache api = new ApiCache(cache);
        api.loaded(project, List.of(jar.toString()));
        assertEquals(List.of(jar.toString()), api.previous(project, List.of(jar.toString())));

        Files.copy(newJar, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.setLastModifiedTime(jar, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        api.loaded(project, List.of(jar.toString()));
        api.loaded(project, List.of(jar.toString()));
        Path before = Path.of(api.previous(project, List.of(jar.toString())).getFirst());
        assertTrue(before.startsWith(cache), before.toString());
        assertTrue(java.util.Arrays.equals(Files.readAllBytes(oldJar), Files.readAllBytes(before)));
    }

    @Test
    void theClasspathBeforeAnUpgradeIsTheOneLoadedBeforeIt() throws IOException {
        ApiCache api = new ApiCache(dir.resolve("api-cache-upgrade"));
        Path project = dir.resolve("upgraded");
        String before = Files.copy(oldJar, Files.createDirectories(dir.resolve("m2/lib/1.0")).resolve("lib-1.0.jar"))
                .toString();
        String after = Files.copy(newJar, Files.createDirectories(dir.resolve("m2/lib/2.0")).resolve("lib-2.0.jar"))
                .toString();
        api.loaded(project, List.of(before));
        // The pom moved and the new classpath is not loaded yet: the one recorded is the one before.
        assertEquals(List.of(before), api.previous(project, List.of(after)));
        api.loaded(project, List.of(after));
        api.loaded(project, List.of(after));
        assertEquals(List.of(before), api.previous(project, List.of(after)));
        // A project never loaded before has nothing to compare with.
        assertEquals(List.of(after), api.previous(dir.resolve("new"), List.of(after)));
    }

    // --- the bot ------------------------------------------------------------------------------------------------

    private record Bot(BotIndex now, List<String> old, Path botFile) {}

    private static Bot bot(String bot, String rules) throws IOException {
        return bot(bot, rules, Map.of());
    }

    private static Bot bot(String bot, String rules, Map<String, String> more) throws IOException {
        return bot(bot, rules, more, true);
    }

    /** @param upgraded whether the new jar takes the old one's place, else the library is removed */
    private static Bot bot(String bot, String rules, Map<String, String> more, boolean upgraded) throws IOException {
        Path root = Files.createTempDirectory(dir, "bot").resolve("src/main/java");
        Map<Path, String> sources = new LinkedHashMap<>();
        Path botFile = root.resolve("bot/Bot.java").toAbsolutePath().normalize();
        sources.put(botFile, bot);
        sources.put(root.resolve("bot/Migrations.java").toAbsolutePath().normalize(), rules);
        more.forEach((name, text) -> sources.put(root.resolve("bot/" + name + ".java").toAbsolutePath().normalize(),
                text));
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            Files.writeString(entry.getKey(), entry.getValue());
        }
        String contract = TestSupport.contractOnClasspath();
        BotIndex now = BotIndex.over(sources, upgraded ? List.of(newJar.toString(), contract) : List.of(contract),
                root);
        return new Bot(now, List.of(oldJar.toString(), contract), botFile);
    }

    private static Path jar(String label, Map<String, String> classes) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "no platform compiler on this JRE; the fixture jars cannot be built");
        Path src = dir.resolve("src-" + label + "/lib");
        Files.createDirectories(src);
        List<String> args = new ArrayList<>();
        Path out = dir.resolve("classes-" + label);
        Files.createDirectories(out);
        args.addAll(List.of("-d", out.toString()));
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            Path file = src.resolve(entry.getKey() + ".java");
            Files.writeString(file, entry.getValue());
            args.add(file.toString());
        }
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)), label + " must compile");
        Path jar = dir.resolve("lib-" + label + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> walk = Files.walk(out)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                jos.putNextEntry(new JarEntry(out.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
        return jar;
    }
}
