package com.botmaker.studio.services.trial;

import com.botmaker.studio.plugin.grammar.JavaValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The caller ▶ Try writes compiles beside the bot's classes and runs the statement through the trial entry: its
 * locals declared, a {@code return} reported, the holders handed over. The entry here stands in for the SDK's, so
 * the class is public: the caller is in another package.
 */
public class TrialCallerTest {

    @TempDir
    Path dir;

    /** What the stand-in entry was handed, for the test to check. */
    static final List<String> HOLDERS = new ArrayList<>();

    /** A trial entry of the shape {@code Bot.trial} has: it runs the body once. */
    public static void trial(Runnable body, Class<?>... values) {
        HOLDERS.clear();
        for (Class<?> v : values) HOLDERS.add(v.getSimpleName());
        body.run();
    }

    private static Method entry() throws Exception {
        return TrialCallerTest.class.getMethod("trial", Runnable.class, Class[].class);
    }

    @Test
    void aStatementRunsWithItsLocalsAndReportsItsReturn() throws Exception {
        TrialPlan.Plan plan = new TrialPlan.Plan("com.bot", List.of("import java.util.List;"),
                "if (List.of(a, b).size() == 2) return a + b;", true,
                List.of(new TrialPlan.Local("a", "int", "int", "Integer.parseInt(\"40\")", List.of(), null),
                        new TrialPlan.Local("b", "int", "int", null, List.of(), null)),
                List.of("a", "b"), "if (List.of(a, b)…");
        TrialCaller.Source caller = TrialCaller.write(plan,
                Map.of("b", new TrialCaller.Given(JavaValue.parse("2").orElseThrow())), entry(),
                List.of("com.bot.Values"));

        assertEquals("com.bot.BotMakerTry", caller.className());
        String out = compileAndRun(caller, "package com.bot; public final class Values {}");
        assertEquals("▶ Try: returned = 42", out.strip());
        assertEquals(List.of("Values"), HOLDERS, "the bot's holders reach the entry");
    }

    @Test
    void aStatementThatFallsThroughReportsNothing() throws Exception {
        TrialPlan.Plan plan = TrialPlan.call("com.bot", "com.bot.Values", "quiet", false);
        String out = compileAndRun(TrialCaller.write(plan, Map.of(), entry(), List.of()),
                "package com.bot; public final class Values { public static void quiet() { System.out.print(\"ran\"); } }");
        assertEquals("ran", out);
    }

    @Test
    void aLocalWithNoValueIsNotWritten() throws Exception {
        TrialPlan.Plan plan = new TrialPlan.Plan("", List.of(), "System.out.println(n);", false,
                List.of(new TrialPlan.Local("n", "int", "int", null, List.of(), null)), List.of("n"), "n");
        assertThrows(IllegalArgumentException.class, () -> TrialCaller.write(plan, Map.of(), entry(), List.of()));
    }

    /** Compiles {@code bot} and the caller, runs the caller's {@code main}, and answers what it printed. */
    private String compileAndRun(TrialCaller.Source caller, String bot) throws Exception {
        Path src = dir.resolve("src");
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        Path botFile = src.resolve("com/bot/Values.java");
        Path callerFile = src.resolve(caller.path());
        Files.createDirectories(botFile.getParent());
        Files.writeString(botFile, bot);
        Files.writeString(callerFile, caller.text());
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int code = javac.run(null, null, errors, "-encoding", "UTF-8", "-cp", System.getProperty("java.class.path"),
                "-d", classes.toString(), botFile.toString(), callerFile.toString());
        assertEquals(0, code, errors.toString(StandardCharsets.UTF_8) + "\n" + caller.text());

        PrintStream original = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
            loader.loadClass(caller.className()).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(original);
        }
        return printed.toString(StandardCharsets.UTF_8);
    }
}
