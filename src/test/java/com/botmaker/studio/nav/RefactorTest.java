package com.botmaker.studio.nav;

import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The one way a name changes: whatever it names, every use found by binding and nothing that only looks the
 * same, and never a plan that would stop the bot compiling.
 *
 * <p>No file here is on disk: the index parses the buffers, which is the point of it — a file's references
 * to another resolve against that other file's text as the editor holds it, not as it was last saved.
 */
class RefactorTest {

    private static final String PARAMETERS = """
            package com.bot;

            public final class Parameters {
                public static int j = 5;
                public static int k = 1;
                private Parameters() {}
            }
            """;

    private static final String BOT = """
            package com.bot;

            import static com.bot.Parameters.j;

            class Bot {
                enum Mode { FAST, SLOW }

                int run(Mode mode) {
                    int local = Parameters.j + j;
                    String text = "Parameters.j";
                    switch (mode) {
                        case FAST -> local++;
                        case SLOW -> local--;
                    }
                    return mode == Mode.FAST ? local : 0;
                }
            }
            """;

    private static final String FLOW = """
            package com.bot;

            import java.util.function.IntUnaryOperator;

            class Flow {
                static int body(int x) { return x; }
                IntUnaryOperator step = Flow::body;
                int twice() { return body(2) + Flow.body(3); }
                int j() { int j = 7; return j; }
            }
            """;

    @Test
    void aFieldIsRenamedEverywhereItIsReadAndNowhereElse(@TempDir Path root) {
        BotIndex index = index(root);
        Path parameters = root.resolve("com/bot/Parameters.java");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, parameters, PARAMETERS.indexOf("j = 5"), "jumps"));

        assertTrue(plan.rewrites().get(parameters).contains("public static int jumps = 5;"));
        String bot = plan.rewrites().get(root.resolve("com/bot/Bot.java"));
        assertTrue(bot.contains("import static com.bot.Parameters.jumps;"), bot);
        assertTrue(bot.contains("int local = Parameters.jumps + jumps;"), bot);
        assertTrue(bot.contains("String text = \"Parameters.j\";"), "a string holds no binding: " + bot);
        assertFalse(plan.rewrites().containsKey(root.resolve("com/bot/Flow.java")),
                "Flow's own method j() and local j are other things with the same spelling");
        assertTrue(plan.moves().isEmpty());
    }

    @Test
    void aMethodIsRenamedAtEveryCallAndEveryReference(@TempDir Path root) {
        BotIndex index = index(root);
        Path flow = root.resolve("com/bot/Flow.java");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, flow, FLOW.indexOf("body(int"), "work"));

        String rewritten = plan.rewrites().get(flow);
        assertTrue(rewritten.contains("static int work(int x)"), rewritten);
        assertTrue(rewritten.contains("IntUnaryOperator step = Flow::work;"),
                "the reference the flow names an activity by follows the rename: " + rewritten);
        assertTrue(rewritten.contains("return work(2) + Flow.work(3);"), rewritten);
    }

    @Test
    void anEnumConstantIsRenamedInItsCaseLabelsToo(@TempDir Path root) {
        BotIndex index = index(root);
        Path bot = root.resolve("com/bot/Bot.java");

        Refactor.Planned plan = assertInstanceOf(Refactor.Planned.class,
                Refactor.rename(index, bot, BOT.indexOf("FAST"), "QUICK"));

        String rewritten = plan.rewrites().get(bot);
        assertTrue(rewritten.contains("enum Mode { QUICK, SLOW }"), rewritten);
        assertTrue(rewritten.contains("case QUICK -> local++;"), rewritten);
        assertTrue(rewritten.contains("mode == Mode.QUICK"), rewritten);
    }

    @Test
    void aRenameThatWouldNotCompileIsRefusedWithAFreeNameOffered(@TempDir Path root) {
        BotIndex index = index(root);
        Path parameters = root.resolve("com/bot/Parameters.java");

        Refactor.Refused refused = assertInstanceOf(Refactor.Refused.class,
                Refactor.rename(index, parameters, PARAMETERS.indexOf("j = 5"), "k"));

        assertTrue(refused.reason().contains("would stop the bot compiling"), refused.reason());
        assertEquals(1, refused.fixes().size(), "k2 is free, so it is offered");
        Refactor.Fix fix = refused.fixes().getFirst();
        assertEquals("Rename to k2 instead", fix.label());
        assertTrue(fix.plan().rewrites().get(parameters).contains("public static int k2 = 5;"));
    }

    @Test
    void aNameJavaRefusesIsRefusedBeforeAnythingIsPlanned(@TempDir Path root) {
        Refactor.Outcome outcome = Refactor.rename(index(root), root.resolve("com/bot/Parameters.java"),
                PARAMETERS.indexOf("j = 5"), "class");
        assertEquals("“class” is not a name Java accepts.",
                assertInstanceOf(Refactor.Refused.class, outcome).reason());
    }

    @Test
    void theUsesOfADeclarationLeaveTheDeclarationOut(@TempDir Path root) {
        BotIndex index = index(root);

        Optional<List<Usages.Usage>> uses = Refactor.uses(index, root.resolve("com/bot/Parameters.java"),
                PARAMETERS.indexOf("j = 5"));

        assertEquals(3, uses.orElseThrow().size(), "the static import, Parameters.j and the bare j");
        assertTrue(uses.get().stream().allMatch(use -> use.file().endsWith("Bot.java")));
    }

    @Test
    void anEditThatBreaksAnotherFileIsSeenBeforeItIsWritten(@TempDir Path root) {
        BotIndex index = index(root);
        Path parameters = root.resolve("com/bot/Parameters.java");

        BotIndex retyped = index.with(Map.of(parameters, PARAMETERS.replace("int j = 5", "String j = \"5\"")));

        String error = index.firstNewError(retyped).orElseThrow();
        assertTrue(error.startsWith("Bot.java, line "), "the file that breaks is named, not the one edited: " + error);
        assertTrue(index.firstNewError(index.with(Map.of())).isEmpty(), "an unchanged bot adds no error");
    }

    @Test
    void theIndexReadsTheBuffersNotTheDisk(@TempDir Path root) throws Exception {
        Path parameters = root.resolve("com/bot/Parameters.java");
        Files.createDirectories(parameters.getParent());
        Files.writeString(parameters, PARAMETERS.replace("int j = 5", "int renamedOnDisk = 5"));

        BotIndex index = index(root);

        assertTrue(Refactor.uses(index, parameters, PARAMETERS.indexOf("j = 5")).isPresent(),
                "the buffer declares j, whatever the saved file says");
        assertTrue(index.errors().get(root.resolve("com/bot/Bot.java")).isEmpty(),
                "Bot's uses of j resolve against the buffer: " + index.errors());
    }

    private static BotIndex index(Path root) {
        Map<Path, String> sources = new LinkedHashMap<>();
        sources.put(root.resolve("com/bot/Parameters.java"), PARAMETERS);
        sources.put(root.resolve("com/bot/Bot.java"), BOT);
        sources.put(root.resolve("com/bot/Flow.java"), FLOW);
        return BotIndex.over(sources, List.of(), root);
    }
}
