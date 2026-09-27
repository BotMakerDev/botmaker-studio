package com.botmaker.studio.ui.app;

import com.botmaker.studio.nav.Refactor;
import com.botmaker.studio.nav.Usages;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>The one way Studio says "not like that"</b> (2026-09-27): why, where, and what to do instead.
 *
 * <p>Each refactor used to refuse in its own voice — an alert for a function still called, a status line under
 * the Parameters list for a parameter still read, the status bar for a class rename, a count in the Delete
 * Variable window — and only some of them said where, and fewer offered anything. Every refusal is now the
 * same three parts ({@link Refactor.Refused}):
 *
 * <ul>
 *   <li><b>the reason</b>, naming the file where there is one;</li>
 *   <li><b>the uses</b>, one link each, that close this window and land on the block;</li>
 *   <li><b>the fixes</b>, one button each, every one a whole edit the refactor already checked compiles — never
 *       a promise the click would then have to keep.</li>
 * </ul>
 *
 * <p>Close is always there and changes nothing.
 */
public final class RefusalDialog {

    private RefusalDialog() {}

    /** One way through: what its button says, and what pressing it does. */
    public record Choice(String label, Runnable run) {}

    /** How many uses are listed before the rest are counted — enough to act on, never a wall. */
    private static final int LISTED = 12;

    /**
     * Shows a refactor's refusal, applying a fix through {@code apply} — the caller's own write, so the fix
     * lands the way its first attempt would have (one ↶ on the canvas, the Parameters window's own history).
     */
    public static void show(Window owner, String title, Refactor.Refused refused, Consumer<Usages.Usage> reveal,
                            Consumer<Refactor.Planned> apply) {
        List<Choice> choices = new ArrayList<>();
        for (Refactor.Fix fix : refused.fixes()) choices.add(new Choice(fix.label(), () -> apply.accept(fix.plan())));
        show(owner, title, refused.reason(), refused.uses(), choices, reveal);
    }

    /**
     * Shows a refusal and waits.
     *
     * @param title   what did not happen, as the window's headline — {@code clickAt wasn't deleted}
     * @param reason  why, in a sentence or two
     * @param uses    where the thing is still used; each is a link, and none makes the list disappear
     * @param choices the fixes, in the order to offer them; each closes the window before it runs
     * @param reveal  lands on a use; null shows the uses as plain text
     */
    public static void show(Window owner, String title, String reason, List<Usages.Usage> uses,
                            List<Choice> choices, Consumer<Usages.Usage> reveal) {
        Stage stage = new Stage();
        if (owner != null) stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("This change can't be made yet");

        Label headline = new Label(title);
        headline.getStyleClass().add("dialog-headline");
        headline.setWrapText(true);

        Label because = new Label(reason);
        because.setWrapText(true);

        VBox body = new VBox(8, because);
        if (!uses.isEmpty()) {
            Label section = new Label(uses.size() == 1 ? "Where it is used:" : "Where it is used ("
                    + uses.size() + "):");
            section.getStyleClass().add("dialog-section-label");
            VBox list = new VBox(2);
            for (Usages.Usage use : uses.stream().limit(LISTED).toList()) list.getChildren().add(row(stage, use, reveal));
            if (uses.size() > LISTED) list.getChildren().add(new Label("… and " + (uses.size() - LISTED) + " more"));
            ScrollPane scroll = new ScrollPane(list);
            scroll.setFitToWidth(true);
            scroll.setMaxHeight(220);
            scroll.getStyleClass().add("refusal-uses");
            body.getChildren().addAll(section, scroll);
        }

        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bar.getChildren().add(spacer);
        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setOnAction(e -> stage.close());
        bar.getChildren().add(close);
        for (Choice choice : choices) {
            Button button = new Button(choice.label());
            button.getStyleClass().add("primary-button");
            button.setOnAction(e -> {
                stage.close();
                choice.run().run();
            });
            bar.getChildren().add(button);
        }
        if (choices.isEmpty()) close.setDefaultButton(true);

        VBox root = new VBox(14, headline, body, bar);
        root.setPadding(new Insets(18));
        VBox.setVgrow(body, Priority.ALWAYS);
        stage.setScene(ThemedWindows.scene(root, 560, uses.isEmpty() ? 200 : 360));
        stage.setMinWidth(460);
        stage.setMinHeight(180);
        stage.showAndWait();
    }

    /** {@code Base.java:21 · main — System.out.println(Parameters.j);}, a link when there is somewhere to go. */
    private static Region row(Stage stage, Usages.Usage use, Consumer<Usages.Usage> reveal) {
        String text = use.file().getFileName() + ":" + use.line()
                + (use.enclosing().isEmpty() ? "" : " · " + use.enclosing())
                + (use.text().isEmpty() ? "" : " — " + use.text());
        if (reveal == null) {
            Label label = new Label(text);
            label.setWrapText(true);
            return label;
        }
        Hyperlink link = new Hyperlink(text);
        link.setWrapText(true);
        link.setOnAction(e -> {
            stage.close();
            reveal.accept(use);
        });
        return link;
    }
}
