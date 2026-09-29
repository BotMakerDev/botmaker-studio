package com.botmaker.studio.ui.app;

import javafx.beans.value.ObservableValue;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Records every change to a window's position, size, maximize and fullscreen, and which code made it
 * (2026-09-29). Off unless {@code BOTMAKER_TRACE_GEOMETRY=1} is in the environment or
 * {@code -Dbotmaker.trace.geometry=true} is passed.
 *
 * <p>Why it exists: "the window resizes for no reason" has been answered three times in this codebase
 * ({@code StudioWindow.pinOwner}, {@code BotMakerStudio.setScenePreservingGeometry}, {@code fillStage}), each
 * from a theory about which of X11, GTK, the window manager and JavaFX moved it, and none from a record of what
 * actually did. This is the record. Each line names the property, old and new value, whether the window had
 * focus, and the first BotMaker frames on the stack — or <i>outside BotMaker</i> when there are none, which is
 * the answer that the window manager or JavaFX itself made the change.
 *
 * <p>Lines go to stderr and to {@link #file()}.
 */
public final class GeometryTrace {

    private static final boolean ON = "1".equals(System.getenv("BOTMAKER_TRACE_GEOMETRY"))
            || Boolean.getBoolean("botmaker.trace.geometry");

    private GeometryTrace() {
    }

    /** Where the lines are appended. */
    static Path file() {
        return Path.of(System.getProperty("java.io.tmpdir"), "botmaker-geometry.log");
    }

    /** Starts recording {@code stage} as {@code name}; nothing when tracing is off. */
    public static void install(Stage stage, String name) {
        if (!ON) return;
        write("---- tracing " + name + " (" + file() + ")");
        watch(stage, name, "x", stage.xProperty());
        watch(stage, name, "y", stage.yProperty());
        watch(stage, name, "width", stage.widthProperty());
        watch(stage, name, "height", stage.heightProperty());
        watch(stage, name, "maximized", stage.maximizedProperty());
        watch(stage, name, "fullScreen", stage.fullScreenProperty());
        watch(stage, name, "minWidth", stage.minWidthProperty());
        watch(stage, name, "minHeight", stage.minHeightProperty());
        stage.sceneProperty().addListener((o, was, now) ->
                line(stage, name, "scene", describe(was), describe(now)));
    }

    private static void watch(Stage stage, String name, String property, ObservableValue<?> value) {
        value.addListener((o, was, now) -> line(stage, name, property, String.valueOf(was), String.valueOf(now)));
    }

    private static void line(Stage stage, String name, String property, String was, String now) {
        write(LocalTime.now() + " " + name + " " + property + " " + was + " -> " + now
                + " [focused=" + stage.isFocused() + " max=" + stage.isMaximized() + " full=" + stage.isFullScreen()
                + "] by " + caller());
    }

    private static String describe(Scene scene) {
        if (scene == null) return "none";
        String root = scene.getRoot() == null ? "?" : scene.getRoot().getClass().getSimpleName();
        return root + "@" + (int) scene.getWidth() + "x" + (int) scene.getHeight();
    }

    /** The first BotMaker frames that led here, or a note that none did. */
    static String caller() {
        List<String> frames = Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(f -> f.getClassName().startsWith("com.botmaker")
                        && !f.getClassName().equals(GeometryTrace.class.getName()))
                .limit(4)
                .map(f -> f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1)
                        + "." + f.getMethodName() + ":" + f.getLineNumber())
                .toList();
        return frames.isEmpty() ? "outside BotMaker (window manager or JavaFX)"
                : frames.stream().collect(Collectors.joining(" < "));
    }

    private static void write(String text) {
        System.err.println("[geometry] " + text);
        try {
            Files.writeString(file(), text + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // stderr has it
        }
    }
}
