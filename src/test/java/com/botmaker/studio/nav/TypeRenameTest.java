package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renaming a file from the explorer renames its class and every name bound to it — and nothing that only
 * looks the same.
 */
class TypeRenameTest {

    private static final String HELPER = """
            package com.mybot;

            /** Helper does the sums. */
            public class Helper {
                public Helper() {}
                public static int twice(int x) { return x * 2; }
                static Helper make() { return new Helper(); }
            }
            """;

    private static final String BOT = """
            package com.mybot;

            import com.mybot.other.Stuff;

            class Bot {
                void run() {
                    Helper h = Helper.make();
                    int a = Helper.twice(1);
                    String Helper2 = "Helper";
                    Stuff s = null;
                }
            }
            """;

    private static final String OTHER = """
            package com.mybot.other;

            public class Stuff {
                static class Helper {}
                Helper local = new Helper();
            }
            """;

    @Test
    void theClassItsConstructorAndEveryUseAreRenamedAndNothingElse(@TempDir Path root) throws Exception {
        Map<Path, String> sources = project(root);
        Path helper = root.resolve("com/mybot/Helper.java");

        TypeRename.Result result = TypeRename.plan(helper, "Maths", sources, Set.of(), parser(root));

        TypeRename.Result.Plan plan = assertInstanceOf(TypeRename.Result.Plan.class, result);
        assertEquals(root.resolve("com/mybot/Maths.java"), plan.to());
        assertEquals(List.of(helper, root.resolve("com/mybot/Bot.java")), List.copyOf(plan.rewrites().keySet()),
                "Stuff's own nested Helper is another class, so its file is untouched");
        String renamed = plan.rewrites().get(helper);
        assertTrue(renamed.contains("public class Maths {"), renamed);
        assertTrue(renamed.contains("public Maths() {}"), "the constructor goes with the class: " + renamed);
        assertTrue(renamed.contains("static Maths make() { return new Maths(); }"), renamed);
        assertTrue(renamed.contains("/** Helper does the sums. */"), "a comment has no binding and is left");
        String bot = plan.rewrites().get(root.resolve("com/mybot/Bot.java"));
        assertTrue(bot.contains("Maths h = Maths.make();"), bot);
        assertTrue(bot.contains("int a = Maths.twice(1);"), bot);
        assertTrue(bot.contains("String Helper2 = \"Helper\";"), "a string and a look-alike name are left: " + bot);
    }

    @Test
    void aNameThatIsNotAClassNameOrIsTakenIsRefused(@TempDir Path root) throws Exception {
        Map<Path, String> sources = project(root);
        Path helper = root.resolve("com/mybot/Helper.java");

        assertInstanceOf(TypeRename.Result.Refused.class,
                TypeRename.plan(helper, "not a name", sources, Set.of(), parser(root)));
        assertInstanceOf(TypeRename.Result.Refused.class,
                TypeRename.plan(helper, "class", sources, Set.of(), parser(root)));
        TypeRename.Result taken = TypeRename.plan(helper, "Bot", sources, Set.of(), parser(root));
        assertEquals("Bot.java already exists.", ((TypeRename.Result.Refused) taken).reason());
        assertInstanceOf(TypeRename.Result.Refused.class,
                TypeRename.plan(helper, "Helper", sources, Set.of(), parser(root)));
    }

    @Test
    void withoutBindingsNothingIsGuessed(@TempDir Path root) throws Exception {
        Map<Path, String> sources = project(root);
        TypeRename.Result result = TypeRename.plan(root.resolve("com/mybot/Helper.java"), "Maths", sources,
                Set.of(), BotParser.SYNTAX);
        assertInstanceOf(TypeRename.Result.Refused.class, result);
    }

    private static Map<Path, String> project(Path root) throws Exception {
        Map<Path, String> sources = new LinkedHashMap<>();
        for (var entry : Map.of("com/mybot/Helper.java", HELPER, "com/mybot/Bot.java", BOT,
                "com/mybot/other/Stuff.java", OTHER).entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        // In a fixed order, so the plan's order can be asserted.
        for (String name : List.of("com/mybot/Helper.java", "com/mybot/Bot.java", "com/mybot/other/Stuff.java")) {
            sources.put(root.resolve(name), Files.readString(root.resolve(name)));
        }
        return sources;
    }

    private static BotParser parser(Path root) {
        return new BotParser(List.of(), root);
    }
}
