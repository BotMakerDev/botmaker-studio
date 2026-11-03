package com.botmaker.studio.ui.app.reports;

import com.botmaker.runlog.ReportTarget;
import com.botmaker.runlog.api.Level;
import com.botmaker.shared.reports.IssueSender;
import com.botmaker.studio.ui.Choosers;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A run's problems before they leave the machine (run reports spec §3, layout A picked 2026-11-03): the problems
 * ticked to send on the left, the exact text of the one picked on the right, editable, how this bot sends from now
 * on, and <i>Export logs…</i>, <i>Later</i> and <i>Send</i>. Shown at a run's end when the bot asks, and from the
 * Trace tab's <i>Submit logs…</i> for any run kept.
 */
public final class ReportsDialog {

    private ReportsDialog() {
    }

    /** One problem as the dialog edits it. */
    private static final class Row {
        final RunProblems.Draft draft;
        final BooleanProperty ticked;
        final StringProperty title;
        final StringProperty body;
        final StringProperty comment;

        Row(RunProblems.Draft draft) {
            this.draft = draft;
            this.ticked = new SimpleBooleanProperty(!draft.sent());
            this.title = new SimpleStringProperty(draft.report().title());
            this.body = new SimpleStringProperty(draft.report().body());
            this.comment = new SimpleStringProperty(draft.report().comment());
        }

        IssueSender.Report edited() {
            IssueSender.Report r = draft.report();
            return new IssueSender.Report(r.fingerprint(), title.get(), body.get(), comment.get(), r.botVersion());
        }
    }

    /**
     * Shows run {@code run}'s {@code drafts}. {@code target} is where they go, null when the bot has none (then only
     * <i>Export logs…</i>). {@code onConsent} gets the consent picked when the dialog closes; {@code send} gets the
     * reports to send, edited, when <i>Send</i> is pressed.
     */
    public static void show(Window owner, Path run, ReportTarget target, List<RunProblems.Draft> drafts,
                            ReportConsent consent, boolean signedIn, Consumer<ReportConsent> onConsent,
                            Consumer<List<IssueSender.Report>> send, Consumer<String> status) {
        List<Row> rows = drafts.stream().map(Row::new).toList();

        Dialog<ButtonType> dialog = new Dialog<>();
        ThemedWindows.apply(dialog);
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Report problems");
        dialog.setHeaderText(target == null
                ? "This run hit " + count(drafts) + ". The bot has no GitHub repository to report to: export its "
                + "logs to send them yourself."
                : "Send this run's " + count(drafts) + " to " + target + ", the bot's author?");

        ListView<Row> list = new ListView<>();
        list.getItems().setAll(rows);
        list.setPrefWidth(260);
        list.setCellFactory(v -> new RowCell());

        TextField title = new TextField();
        TextArea body = new TextArea();
        body.setWrapText(true);
        body.setPrefRowCount(14);
        body.setStyle("-fx-font-family: monospace;");
        TextArea comment = new TextArea();
        comment.setWrapText(true);
        comment.setPrefRowCount(3);
        comment.setStyle("-fx-font-family: monospace;");
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox text = new VBox(4, new Label("New issue · title"), title,
                new Label("Body: exactly what is sent; edit it freely"), body,
                new Label("Comment, sent instead when its issue is already open"), comment);
        HBox.setHgrow(text, Priority.ALWAYS);
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (was != null) {
                title.textProperty().unbindBidirectional(was.title);
                body.textProperty().unbindBidirectional(was.body);
                comment.textProperty().unbindBidirectional(was.comment);
            }
            if (now != null) {
                title.textProperty().bindBidirectional(now.title);
                body.textProperty().bindBidirectional(now.body);
                comment.textProperty().bindBidirectional(now.comment);
            }
        });
        if (!rows.isEmpty()) list.getSelectionModel().selectFirst();

        ToggleGroup consentGroup = new ToggleGroup();
        HBox consentRow = new HBox(10, new Label("This bot from now on:"));
        consentRow.setAlignment(Pos.CENTER_LEFT);
        Map<ReportConsent, RadioButton> radios = new EnumMap<>(ReportConsent.class);
        for (ReportConsent c : ReportConsent.values()) {
            RadioButton radio = new RadioButton(c.displayName());
            radio.setToggleGroup(consentGroup);
            radio.setUserData(c);
            radios.put(c, radio);
            consentRow.getChildren().add(radio);
        }
        radios.get(consent).setSelected(true);

        Button export = new Button("Export logs…");
        export.setOnAction(e -> Choosers.save(owner, "Export logs", null, run.getFileName() + ".zip",
                Choosers.Filter.of("Zip", "zip")).ifPresent(out -> {
            try {
                RunProblems.export(run, out);
                status.accept("Logs exported to " + out + ".");
            } catch (IOException ex) {
                status.accept("Logs not exported: " + ex.getMessage());
            }
        }));
        Label note = new Label(target != null && !signedIn
                ? "Sign in to GitHub (top right of the main window) to send." : "");
        note.setWrapText(true);
        HBox actions = new HBox(10, export, note);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(10, new HBox(10, list, text), consentRow, actions);
        content.setPadding(new Insets(10));
        content.setPrefSize(860, 520);
        dialog.getDialogPane().setContent(content);

        ButtonType sendType = new ButtonType("Send", ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().add(later);
        if (target != null) {
            dialog.getDialogPane().getButtonTypes().add(sendType);
            Button sendButton = (Button) dialog.getDialogPane().lookupButton(sendType);
            Runnable recount = () -> {
                long n = rows.stream().filter(r -> r.ticked.get()).count();
                sendButton.setText("Send " + n);
                sendButton.setDisable(n == 0 || !signedIn);
            };
            rows.forEach(r -> r.ticked.addListener((o, was, now) -> recount.run()));
            recount.run();
        }

        ButtonType pressed = dialog.showAndWait().orElse(later);
        onConsent.accept((ReportConsent) consentGroup.getSelectedToggle().getUserData());
        if (pressed == sendType) {
            send.accept(rows.stream().filter(r -> r.ticked.get()).map(Row::edited).toList());
        }
    }

    private static String count(List<RunProblems.Draft> drafts) {
        return drafts.size() + (drafts.size() == 1 ? " problem" : " problems");
    }

    /** A problem: its tick, its level, its call and bot line, how often, and whether it was sent already. */
    private static final class RowCell extends ListCell<Row> {
        private Row bound;
        private final CheckBox tick = new CheckBox();

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (bound != null) tick.selectedProperty().unbindBidirectional(bound.ticked);
            bound = null;
            if (empty || row == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            bound = row;
            tick.selectedProperty().bindBidirectional(row.ticked);
            var p = row.draft.problem();
            Label what = new Label(p.level().displayName() + "  " + simple(p.calledClass()) + "." + p.method()
                    + "\n" + p.botLine() + "  ×" + p.count() + (row.draft.sent() ? "  · sent" : ""));
            what.setStyle(p.level() == Level.ERROR ? "-fx-text-fill: #d33;" : "");
            HBox graphic = new HBox(6, tick, what);
            graphic.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(graphic);
        }

        private static String simple(String className) {
            int dot = className.lastIndexOf('.');
            return dot < 0 ? className : className.substring(dot + 1);
        }
    }
}
