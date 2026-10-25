package com.botmaker.studio.ui.app.botinfo;

import com.botmaker.plugin.api.StyleClasses;
import com.botmaker.studio.project.BotIcon;
import com.botmaker.studio.project.PomInfo;
import com.botmaker.studio.services.capture.ScreenOverlay;
import com.botmaker.studio.services.capture.TargetCapture;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Project Settings' <i>Bot</i> section (2026-10-25, spec § 1a, plan phase 4): the bot's name and description,
 * the pom's {@code <name>} and {@code <description>}, and its icon, chosen from a file or cut from a window of the
 * game, then cropped square. Nothing is written until {@link #save}: the dialog's Apply.
 */
public final class BotInfoSection {

    /** The icon's side in the section. */
    private static final double PREVIEW = 64;

    private final Path projectDir;
    private final Window owner;
    private final String folderName;
    private final TextField name = new TextField();
    private final TextArea description = new TextArea();
    private final StackPane preview = new StackPane();
    private final Label note = new Label();
    private final Button remove = new Button("Remove");

    /** The icon chosen in this dialog, not yet written; empty for none chosen. */
    private Optional<BufferedImage> chosen = Optional.empty();
    /** Whether <i>Remove</i> was pressed since, so Apply deletes the file. */
    private boolean removed;

    public BotInfoSection(Path projectDir, Window owner) {
        this.projectDir = projectDir;
        this.owner = owner;
        this.folderName = projectDir.getFileName() == null ? "" : projectDir.getFileName().toString();
        PomInfo.Info info = PomInfo.read(projectDir);
        name.setText(info.name().orElse(""));
        name.setPromptText(folderName);
        description.setText(info.description().orElse(""));
        description.setPromptText("What the bot does, in a sentence or two.");
        description.setPrefRowCount(2);
        description.setWrapText(true);
    }

    public TitledPane node() {
        Button file = new Button("Choose file…");
        file.setOnAction(e -> chooseFile());
        Button cut = new Button("Cut from game…");
        cut.setOnAction(e -> cutFromGame());
        remove.setOnAction(e -> {
            chosen = Optional.empty();
            removed = true;
            showPreview();
        });
        note.getStyleClass().add(StyleClasses.DIALOG_HINT);
        note.setWrapText(true);
        HBox iconRow = new HBox(10, preview, new VBox(6, new HBox(6, file, cut, remove), note));
        iconRow.setAlignment(Pos.CENTER_LEFT);
        showPreview();

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.addRow(0, new Label("Name:"), name);
        grid.addRow(1, new Label("Description:"), description);
        grid.addRow(2, new Label("Icon:"), iconRow);
        TitledPane pane = new TitledPane("Bot", grid);
        pane.setCollapsible(false);
        return pane;
    }

    /** What Apply writes, read off the section on the FX thread so the write can run off it. */
    public record Pending(Path projectDir, String name, String description, Optional<BufferedImage> icon,
                          boolean removeIcon) {

        /** The pom's name and description (only those two elements), then the icon. Blocking. */
        public void save() throws IOException {
            PomInfo.write(projectDir, name, description);
            if (icon.isPresent()) {
                BotIcon.write(projectDir, icon.get());
            } else if (removeIcon) {
                BotIcon.remove(projectDir);
            }
        }
    }

    /** What is to be written, as the section shows it now. FX thread. */
    public Pending pending() {
        return new Pending(projectDir, name.getText(), description.getText(), chosen, removed);
    }

    /** The name the letter is drawn from: the one typed, else the folder's. */
    private String shownName() {
        return name.getText().isBlank() ? folderName : name.getText().strip();
    }

    private void showPreview() {
        Node icon;
        if (chosen.isPresent()) {
            icon = BotIconView.picture(ScreenOverlay.toFxImage(chosen.get()), PREVIEW);
        } else if (removed) {
            icon = BotIconView.letter(shownName(), PREVIEW);
        } else {
            icon = BotIconView.local(shownName(), projectDir, PREVIEW);
        }
        preview.getChildren().setAll(icon);
        boolean hasIcon = chosen.isPresent() || (!removed && BotIcon.of(projectDir).isPresent());
        remove.setDisable(!hasIcon);
        note.setText(hasIcon ? "Saved as icon.png, " + BotIcon.SIZE + "×" + BotIcon.SIZE + ", at the project's root."
                : "No icon: the first letter shows, on a colour taken from the name.");
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose the bot's icon");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Images", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File picked = chooser.showOpenDialog(owner);
        if (picked == null) return;
        BufferedImage image;
        try {
            image = ImageIO.read(picked);
        } catch (IOException e) {
            image = null;
        }
        if (image == null) {
            note.setText(picked.getName() + " is not a picture Studio can read.");
            return;
        }
        crop(image);
    }

    /**
     * Grabs a window the user names (the game's: Studio does not read what the bot watches, a plugin's value),
     * off the FX thread, then crops it.
     */
    private void cutFromGame() {
        note.setText("Listing the open windows…");
        Thread list = new Thread(() -> {
            List<String> titles = TargetCapture.listWindowTitles().stream()
                    .filter(t -> t != null && !t.isBlank()).map(String::strip).distinct().toList();
            Platform.runLater(() -> askWindow(titles));
        }, "bot-icon-windows");
        list.setDaemon(true);
        list.start();
    }

    private void askWindow(List<String> titles) {
        if (titles.isEmpty()) {
            note.setText("No window is open to cut from: start the game first.");
            return;
        }
        showPreview();
        ChoiceDialog<String> dialog = new ChoiceDialog<>(titles.getFirst(), titles);
        dialog.setTitle("Cut from game");
        dialog.setHeaderText("Which window shows the game?");
        dialog.setContentText("Window:");
        dialog.initOwner(owner);
        ThemedWindows.apply(dialog);
        Optional<String> title = dialog.showAndWait();
        if (title.isEmpty()) return;
        note.setText("Grabbing " + title.get() + "…");
        Thread grab = new Thread(() -> {
            TargetCapture.WindowShot shot = new TargetCapture().captureWindow(new TargetCapture.WindowRef(title.get()));
            Platform.runLater(() -> {
                if (shot == null || shot.image() == null) {
                    note.setText("That window could not be captured.");
                    return;
                }
                showPreview();
                crop(shot.image());
            });
        }, "bot-icon-grab");
        grab.setDaemon(true);
        grab.start();
    }

    private void crop(BufferedImage image) {
        SquareCrop.show(owner, image, square -> square.ifPresent(s -> {
            chosen = Optional.of(s);
            removed = false;
            showPreview();
        }));
    }
}
