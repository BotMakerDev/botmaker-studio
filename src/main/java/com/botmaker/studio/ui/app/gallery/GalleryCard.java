package com.botmaker.studio.ui.app.gallery;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.stream.Collectors;

/**
 * How one gallery listing reads: its name and tier, author and description, what it requires, what it was
 * tested on, and a link to its repository.
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
        HBox title = new HBox(8, name, tierBadge(entry));
        title.setAlignment(Pos.CENTER_LEFT);

        Label meta = new Label("by " + (entry.owner().isBlank() ? "you" : entry.owner())
                + (entry.description().isBlank() ? "" : " — " + entry.description()));
        meta.getStyleClass().add("gallery-card-note");
        meta.setWrapText(true);
        VBox text = new VBox(2, title, meta);

        // Only when the entry says: an empty list is every entry written before the field existed, and reads as
        // "unknown", which is not worth a line saying "Requires: nothing".
        if (!entry.requires().isEmpty()) {
            text.getChildren().add(note("Requires: " + entry.requires().stream()
                    .map(GalleryEntry.Requirement::describe)
                    .collect(Collectors.joining(", "))));
        }
        // Only when the author declared something: "tested on: any launch target" would be noise on every entry
        // published before the field existed. "Tested on" rather than "runs on" because installing never
        // restricts what you may launch — see LaunchTargetDialog.
        if (entry.launchTargets().declared()) {
            text.getChildren().add(note("Tested on: " + entry.launchTargets().describe()));
        }
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

    /**
     * The tier as a small badge, with the sentence behind it on hover. A Vetted badge names the release that was
     * looked at, because that — not the bot — is what a maintainer vouched for.
     */
    public static Label tierBadge(GalleryEntry entry) {
        Label badge = new Label(entry.tier().displayName());
        badge.getStyleClass().addAll("gallery-tier-badge",
                entry.isVetted() ? "gallery-tier-vetted" : "gallery-tier-community");
        String tip = entry.isVetted()
                ? "A maintainer looked at "
                        + (entry.vettedVersion().isEmpty() ? "a release" : "release " + entry.vettedVersion())
                        + " and chose to list it. Not a security review."
                : "Listed automatically: its author owns the repository and its release downloads. "
                        + "Nobody reviewed its code.";
        badge.setTooltip(new Tooltip(tip));
        return badge;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }
}
