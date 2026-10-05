package com.botmaker.studio.services.overlay;

import com.botmaker.plugin.api.overlay.OverlayPart;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.source.BotIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targets are the bot's methods passed by reference where a declared type is expected — found by binding,
 * whatever the call around them is called, and never a lambda or a reference of another type.
 */
class OverlayTargetsTest {

    @TempDir
    Path root;

    private ProjectConfig config;
    private String pkg;

    @BeforeEach
    void writeBot() throws IOException {
        config = ProjectConfig.forProject("MyFarmer", root);
        pkg = config.mainPackage();
        Path dir = config.mainSourceFile().getParent();
        Files.createDirectories(dir);
        write(dir, "Body", "public interface Body { void run(); }");
        write(dir, "Flow", """
                public final class Flow {
                    static void activity(String name, Body body) {}
                    static void step(Body body) {}
                    static void other(Runnable task) {}

                    static void build() {
                        activity("Collect", Collect::body);
                        step(Battle::fight);
                        activity("Again", Collect::body);
                        activity("Inline", () -> {});
                        other(Collect::body);
                        step(Battle::rest);
                        step(Thread::dumpStack);
                    }
                }""");
        write(dir, "Collect", "public final class Collect { public static void body() {} }");
        write(dir, "Battle", "public final class Battle { static void fight() {} static void rest() {} }");
    }

    private void write(Path dir, String name, String body) throws IOException {
        Files.writeString(dir.resolve(name + ".java"), "package " + pkg + ";\n\n" + body + "\n");
    }

    private List<OverlayTargets.Target> find(String group) {
        return OverlayTargets.find(BotIndex.of(config, null),
                List.of(new OverlayPart.TargetType(pkg + ".Body", group)));
    }

    @Test
    void everyReferenceOfTheTypeOnceInSourceOrder() {
        List<OverlayTargets.Target> found = find("Activities");
        assertEquals(List.of("Collect", "Battle.fight", "Battle.rest"),
                found.stream().map(OverlayTargets.Target::label).toList(),
                "a lambda, a Runnable and a library method are not targets; two methods of one class are named");
        OverlayTargets.Target collect = found.getFirst();
        assertEquals("Activities", collect.group());
        assertEquals(pkg + ".Collect#body", collect.key());
        assertTrue(collect.file().endsWith("Collect.java"));
    }

    @Test
    void anUndeclaredTypeFindsNothing() {
        assertEquals(List.of(), OverlayTargets.find(BotIndex.of(config, null),
                List.of(new OverlayPart.TargetType(pkg + ".Missing", "Activities"))));
        assertEquals(List.of(), OverlayTargets.find(BotIndex.of(config, null), List.of()));
    }
}
