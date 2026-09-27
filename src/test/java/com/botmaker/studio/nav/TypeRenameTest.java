package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void theClassItsConstructorAndEveryUseAreRenamedAndNothingElse(@TempDir Path root) {
        Path helper = root.resolve("com/mybot/Helper.java");

        Refactor.Outcome result = TypeRename.plan(index(root), helper, "Maths", Set.of());

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class, result);
        assertEquals(Map.of(helper, root.resolve("com/mybot/Maths.java")), plan.moves());
        assertEquals(Set.of(helper, root.resolve("com/mybot/Bot.java")), plan.rewrites().keySet(),
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
    void aNameThatIsNotAClassNameOrIsTakenIsRefused(@TempDir Path root) {
        BotIndex index = index(root);
        Path helper = root.resolve("com/mybot/Helper.java");

        assertInstanceOf(Refactor.Refused.class, TypeRename.plan(index, helper, "not a name", Set.of()));
        assertInstanceOf(Refactor.Refused.class, TypeRename.plan(index, helper, "class", Set.of()));
        Refactor.Outcome taken = TypeRename.plan(index, helper, "Bot", Set.of());
        assertEquals("Bot.java already exists.", ((Refactor.Refused) taken).reason());
        assertInstanceOf(Refactor.Refused.class, TypeRename.plan(index, helper, "Helper", Set.of()));
    }

    @Test
    void aFileOnDiskTheBotDoesNotListIsNotOverwritten(@TempDir Path root) {
        Path helper = root.resolve("com/mybot/Helper.java");
        Refactor.Outcome result = TypeRename.plan(index(root), helper, "Maths",
                Set.of(root.resolve("com/mybot/Maths.java")));
        assertEquals("Maths.java already exists.", assertInstanceOf(Refactor.Refused.class, result).reason());
    }

    private static BotIndex index(Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("com/mybot/Helper.java"), HELPER);
        sources.put(root.resolve("com/mybot/Bot.java"), BOT);
        sources.put(root.resolve("com/mybot/other/Stuff.java"), OTHER);
        return BotIndex.over(sources, List.of(), root);
    }
}
