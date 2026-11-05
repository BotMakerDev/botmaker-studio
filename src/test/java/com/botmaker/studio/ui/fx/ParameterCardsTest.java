package com.botmaker.studio.ui.fx;

import com.botmaker.core.project.ProjectConfig;
import com.botmaker.java.grammar.ParamsFitter;
import com.botmaker.studio.ui.app.params.ParametersDialog;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Parameters window as cards (layout A, 2026-11-05), driven as a user would: the When and From chips and the
 * search box narrow the cards, and <i>+ New group…</i> writes a group and its starting copy.
 */
class ParameterCardsTest extends FxHeadlessTest {

    @TempDir Path root;
    private ProjectConfig config;

    @Override
    public void start(Stage stage) {
    }

    /** Opens the window over a bot with two groups: Account (server, outside a run) and Progress (gold, during). */
    private void open() throws IOException {
        config = ProjectConfig.forProject("refbot", root);
        Files.createDirectories(config.mainPackageDir());
        writeGroup("Account", "    public String server() { return \"Cloudwold\"; }\n");
        writeGroup("Progress", """
                    @Param.Shown(changes = Param.Changes.DURING_RUN)
                    public int gold() { return 100; }
                """);
        interact(() -> new ParametersDialog(null, config, null, null).show());
    }

    @AfterEach
    void closeTheWindow() {
        interact(() -> List.copyOf(Window.getWindows()).forEach(w -> {
            if (w instanceof Stage stage) stage.close();
        }));
    }

    @Test
    void cardsShowOnlyTheChosenFilters() throws IOException {
        open();
        // In the order the scan walked the files, which the disk decides: compared as a set.
        assertEquals(List.of("Account.server", "Progress.gold"), cards().stream().sorted().toList());
        assertTrue(chip("When", "During a run").getText().endsWith("1"), chip("When", "During a run").getText());

        interact(() -> chip("When", "During a run").fire());
        assertEquals(List.of("Progress.gold"), cards());

        interact(() -> chip("When", "Outside a run").fire());
        assertEquals(List.of("Account.server"), cards());

        interact(() -> {
            chip("When", "All").fire();
            ((TextField) find(n -> n instanceof TextField tf && "Search…".equals(tf.getPromptText()))).setText("gold");
        });
        assertEquals(List.of("Progress.gold"), cards());
    }

    @Test
    void newGroupWritesBothCopies() throws IOException {
        open();
        Button button = (Button) find(n -> n instanceof Button b && "+ New group…".equals(b.getText()));
        Platform.runLater(button::fire);
        WaitForAsyncUtils.waitForFxEvents();

        DialogPane ask = Window.getWindows().stream().filter(Window::isShowing)
                .map(w -> w.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast)
                .reduce((a, b) -> b).orElseThrow();
        interact(() -> {
            ((TextField) first(ask, TextField.class::isInstance)).setText("Achievements");
            ((Button) ask.lookupButton(ButtonType.OK)).fire();
        });
        WaitForAsyncUtils.waitForFxEvents();

        Path params = config.parametersPackageDir().resolve("bot/Achievements.java");
        Path starting = config.projectPath().resolve(ParamsFitter.STARTING).resolve("bot/Achievements.java");
        assertTrue(Files.readString(params).contains("class Achievements"));
        assertEquals(Files.readString(params), Files.readString(starting));
        assertNotNull(find(n -> n instanceof Label l && "Achievements".equals(l.getText())
                && l.getStyleClass().contains("param-group-heading")), "the empty group is headed");
    }

    private void writeGroup(String name, String methods) throws IOException {
        String source = """
                package com.refbot.params.bot;

                import com.botmaker.plugin.api.params.Param;

                @Param
                public class %s {
                %s}
                """.formatted(name, methods);
        for (Path file : List.of(config.parametersPackageDir().resolve("bot/" + name + ".java"),
                config.projectPath().resolve(ParamsFitter.STARTING).resolve("bot/" + name + ".java"))) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        }
    }

    /** The cards on screen, by {@code Group.name}, in order. */
    private static List<String> cards() {
        List<String> out = new ArrayList<>();
        walk(window().getScene().getRoot(), n -> {
            if (n.getStyleClass().contains("param-card")) out.add((String) n.getUserData());
        });
        return out;
    }

    /** The chip of row {@code caption} whose text starts with {@code label}. */
    private static ToggleButton chip(String caption, String label) {
        Node row = find(n -> n instanceof Parent p && p.getChildrenUnmodifiable().stream()
                .anyMatch(c -> c instanceof Label l && caption.equals(l.getText())));
        return (ToggleButton) first(row, n -> n instanceof ToggleButton t && t.getText().startsWith(label + " "));
    }

    private static Window window() {
        return Window.getWindows().stream().filter(Window::isShowing)
                .filter(w -> !(w.getScene().getRoot() instanceof DialogPane)).reduce((a, b) -> b).orElseThrow();
    }

    private static Node find(Predicate<Node> test) {
        return first(window().getScene().getRoot(), test);
    }

    private static Node first(Node from, Predicate<Node> test) {
        Node[] hit = new Node[1];
        walk(from, n -> {
            if (hit[0] == null && test.test(n)) hit[0] = n;
        });
        return hit[0];
    }

    private static void walk(Node node, Consumer<Node> visit) {
        visit.accept(node);
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) walk(child, visit);
    }
}
