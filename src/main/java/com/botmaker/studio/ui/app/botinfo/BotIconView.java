package com.botmaker.studio.ui.app.botinfo;

import com.botmaker.studio.project.BotIcon;
import com.botmaker.studio.services.capture.ScreenOverlay;
import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A bot's icon as a node (2026-10-25, plan phase 4), the same everywhere a bot shows: My bots and Get more bots
 * cards, New Project's cards, the Run window's header. The letter ({@link BotIcon#letter}) shows first and stays
 * when there is no icon, the file cannot be read or GitHub answers 404. Every picture loads in the background,
 * at most twice the size shown: the cards are rebuilt on every keystroke of a search.
 */
public final class BotIconView {

    /** Icons GitHub served this session, by URL; one that failed is dropped, so the next card asks again. */
    private static final Map<String, Image> REMOTE = new ConcurrentHashMap<>();

    /** Letters drawn this session, by name and side. */
    private static final Map<String, Image> LETTERS = new ConcurrentHashMap<>();

    private BotIconView() {}

    /** {@code projectDir}'s icon, else {@code name}'s letter; {@code size} pixels a side. */
    public static Node local(String name, Path projectDir, double size) {
        StackPane pane = letterPane(name, size);
        BotIcon.of(projectDir).ifPresent(file -> showWhenLoaded(pane,
                new Image(file.toUri().toString(), size * 2, size * 2, true, true, true), size, null));
        return pane;
    }

    /**
     * The icon GitHub serves for {@code owner/repo} at {@code commit}, loaded in the background over
     * {@code name}'s letter; the letter alone when the commit is unknown.
     */
    public static Node remote(String name, String owner, String repo, String commit, double size) {
        StackPane pane = letterPane(name, size);
        if (commit == null || commit.isBlank()) return pane;
        String url = BotIcon.rawUrl(owner, repo, commit);
        Image image = REMOTE.computeIfAbsent(url, u -> new Image(u, BotIcon.SIZE, BotIcon.SIZE, true, true, true));
        showWhenLoaded(pane, image, size, url);
        return pane;
    }

    /** {@code name}'s letter alone. */
    public static Node letter(String name, double size) {
        return letterPane(name, size);
    }

    /** A picture not yet saved, shown as an icon is. */
    static Node picture(Image image, double size) {
        StackPane pane = new StackPane(view(image, size));
        pane.getStyleClass().add("bot-icon");
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    /**
     * Puts {@code image} over the letter once it has loaded; one that failed leaves the letter and, when it came
     * from {@code remoteUrl}, leaves the cache. The listener removes itself, so a card thrown away is not kept.
     */
    private static void showWhenLoaded(StackPane pane, Image image, double size, String remoteUrl) {
        Runnable done = () -> {
            if (image.isError()) {
                if (remoteUrl != null) REMOTE.remove(remoteUrl, image);
                return;
            }
            pane.getChildren().add(view(image, size));
        };
        if (image.getProgress() >= 1 || image.isError()) {
            done.run();
            return;
        }
        // A failed load sets error without always reaching full progress: both end the wait, once.
        InvalidationListener[] wait = new InvalidationListener[1];
        wait[0] = o -> {
            if (image.getProgress() < 1 && !image.isError()) return;
            image.progressProperty().removeListener(wait[0]);
            image.errorProperty().removeListener(wait[0]);
            done.run();
        };
        image.progressProperty().addListener(wait[0]);
        image.errorProperty().addListener(wait[0]);
    }

    private static StackPane letterPane(String name, double size) {
        int side = (int) Math.ceil(size * 2);   // drawn at twice the size: sharp on a scaled screen
        Image letter = LETTERS.computeIfAbsent(name + "\u0000" + side,
                k -> ScreenOverlay.toFxImage(BotIcon.letter(name, side)));
        StackPane pane = new StackPane(view(letter, size));
        pane.getStyleClass().add("bot-icon");
        pane.setMinSize(size, size);
        pane.setMaxSize(size, size);
        return pane;
    }

    private static ImageView view(Image image, double size) {
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        return view;
    }
}
