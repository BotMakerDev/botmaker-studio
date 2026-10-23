package com.botmaker.studio.ui.app.gallery;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * How one gallery listing reads: its name, author and description, its tags, and a link to its repository.
 *
 * <p>One builder for two windows. Browse Bots draws a row from it, with its own star and install buttons
 * beside; the publish dialog draws its preview from it. Two copies would be a preview that promises one card
 * and a gallery that shows another the first time either changes.
 */
public final class GalleryCard {

    private GalleryCard() {}

    /** The card's text column for {@code entry}. */
    public static VBox of(GalleryEntry entry) {
        Label name = new Label(entry.name().isBlank() ? "(no name)" : entry.name());
        name.getStyleClass().add("gallery-card-name");
        // No tier badge and no "Tested on" line since 2026-10-23: no listing is vouched for above another, and
        // what a bot launches is the SDK's business.
        Label meta = new Label("by " + (entry.owner().isBlank() ? "you" : entry.owner())
                + (entry.description().isBlank() ? "" : " — " + entry.description()));
        meta.getStyleClass().add("gallery-card-note");
        meta.setWrapText(true);
        VBox text = new VBox(2, name, meta);

        if (!entry.tags().isEmpty()) {
            text.getChildren().add(note("Tags: " + String.join(", ", entry.tags())));
        }

        // The one way to read what you are about to run before running it. A Hyperlink rather than a button: it
        // leaves Studio, and a button reads as something done to the bot.
        Hyperlink repoLink = new Hyperlink("Open on GitHub");
        repoLink.getStyleClass().add("gallery-repo-link");
        repoLink.setTooltip(new Tooltip(entry.htmlUrl()));
        repoLink.setOnAction(e -> BrowserLauncher.open(entry.htmlUrl()));
        repoLink.setDisable(entry.owner().isBlank() || entry.repo().isBlank());
        text.getChildren().add(repoLink);
        return text;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }
}
