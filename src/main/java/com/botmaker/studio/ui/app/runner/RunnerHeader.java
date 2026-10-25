package com.botmaker.studio.ui.app.runner;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.plugin.api.run.Readiness;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The Run window's header (2026-10-25, spec § 1a, mockup A): the bot's icon, its name, "by owner · version", one
 * status line saying whether it can start, and ▶ Start and ⏹ Stop on the right; under it, when the catalog has a
 * newer release, the update strip. Holds no project: what it shows is handed in, what it does is called out.
 */
public final class RunnerHeader {

    /**
     * What the header says of the bot.
     *
     * @param owner     the author of a bot got from someone, empty for the user's own
     * @param version   the release the bot is at, empty for one never released
     * @param available the catalog's newer release, empty when there is none
     */
    public record About(String name, Optional<String> owner, Optional<String> version,
                        Optional<String> available) {

        /** "by LiQiyeDev · 1.0.0", "mine · 0.2.0", or "mine". */
        public String byLine() {
            String who = owner.map(o -> "by " + o).orElse("mine");
            return version.map(v -> who + " · " + v).orElse(who);
        }
    }

    /** What the header's buttons do; the window supplies them. */
    public interface Actions {
        void home();

        void start();

        void stop();

        void update();

        void whatChanged();

        void editInStudio();
    }

    /** The side of the header's icon. */
    public static final double ICON_SIZE = 48;

    private final About about;
    private final Node icon;
    private final Actions actions;
    private final String stopKey;
    private final List<Node> extra;

    private final Label status = new Label();
    private final Button start = new Button("▶ Start");
    private final Button stop = new Button();
    private final VBox updateStrip = new VBox(4);
    private final Hyperlink update = new Hyperlink("Update");
    private final Label updateNote = new Label();
    private final Button editInStudio = new Button("Edit in Studio");

    private Optional<Readiness> readiness = Optional.empty();
    private boolean running;
    /** An update rewrites the bot's files: no run starts during one, and no update while it runs. */
    private boolean updating;

    /**
     * @param icon    the bot's icon, {@link #ICON_SIZE} a side
     * @param stopKey the key that stops a run, by name ({@code "Pause"}, {@code "F12"})
     * @param extra   the window's own buttons, after the titles: the way back to editing, or a look at the code
     */
    public RunnerHeader(About about, Node icon, String stopKey, List<Node> extra, Actions actions) {
        this.about = about;
        this.icon = icon;
        this.stopKey = stopKey;
        this.extra = List.copyOf(extra);
        this.actions = actions;
    }

    public Node node() {
        Button home = new Button("← My bots");
        home.setOnAction(e -> actions.home());

        Label name = new Label(about.name());
        name.getStyleClass().add("runner-title");
        Label by = new Label(about.byLine());
        by.getStyleClass().add("runner-subtitle");
        status.getStyleClass().add("runner-status");
        VBox titles = new VBox(2, name, by, status);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        start.getStyleClass().add(StyleClasses.PRIMARY_BUTTON);
        start.setOnAction(e -> actions.start());
        stop.setText("⏹ Stop (" + stopKey + ")");
        stop.setOnAction(e -> actions.stop());

        HBox bar = new HBox(10, home, icon, titles, spacer);
        bar.getChildren().addAll(extra);
        bar.getChildren().addAll(start, stop);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("runner-header");

        VBox header = new VBox(bar, updateStrip());
        showStatus();
        return header;
    }

    private Node updateStrip() {
        String tag = about.available().orElse("");
        Label available = new Label(tag + " is available —");
        update.setOnAction(e -> actions.update());
        Hyperlink changes = new Hyperlink("What changed");
        changes.setOnAction(e -> actions.whatChanged());
        Hyperlink later = new Hyperlink("Later");
        later.setOnAction(e -> updateStrip.setVisible(false));
        HBox line = new HBox(4, available, update, new Label("·"), changes, new Label("·"), later);
        line.setAlignment(Pos.CENTER_LEFT);

        updateNote.getStyleClass().add(StyleClasses.DIALOG_HINT);
        updateNote.setWrapText(true);
        updateNote.managedProperty().bind(updateNote.visibleProperty());
        updateNote.setVisible(false);
        editInStudio.setOnAction(e -> actions.editInStudio());
        editInStudio.managedProperty().bind(editInStudio.visibleProperty());
        editInStudio.setVisible(false);

        updateStrip.getChildren().setAll(line, updateNote, editInStudio);
        updateStrip.getStyleClass().add("runner-update");
        updateStrip.managedProperty().bind(updateStrip.visibleProperty());
        updateStrip.setVisible(about.available().isPresent());
        return updateStrip;
    }

    /** What the plugins say of whether the bot can start; shown while it is not running. FX thread. */
    public void readiness(Optional<Readiness> said) {
        readiness = said;
        showStatus();
    }

    /** Whether the bot runs now: Start and Stop swap, and the status line says so. FX thread. */
    public void running(boolean isRunning) {
        running = isRunning;
        showStatus();
    }

    /** The update is under way: Update and Start are off until {@link #updateSaid}. FX thread. */
    public void updating() {
        updating = true;
        updateNote.setText("Updating…");
        updateNote.setVisible(true);
        editInStudio.setVisible(false);
        showStatus();
    }

    /** How the update ended; {@code needsStudio} offers <i>Edit in Studio</i> beside it. FX thread. */
    public void updateSaid(String message, boolean needsStudio) {
        updating = false;
        updateNote.setText(message);
        updateNote.setVisible(!message.isEmpty());
        editInStudio.setVisible(needsStudio);
        showStatus();
    }

    private void showStatus() {
        start.setDisable(running || updating);
        stop.setDisable(!running);
        update.setDisable(running || updating);
        status.getStyleClass().removeIf(c -> c.startsWith("runner-status--"));
        if (running) {
            status.setText("● Running — " + stopKey + " stops it");
            status.getStyleClass().add("runner-status--running");
            return;
        }
        Readiness.Level level = readiness.map(Readiness::level).orElse(Readiness.Level.READY);
        status.setText("● " + readiness.map(Readiness::line).orElse(level.displayName()));
        status.getStyleClass().add("runner-status--" + level.name().toLowerCase(Locale.ROOT));
    }
}
