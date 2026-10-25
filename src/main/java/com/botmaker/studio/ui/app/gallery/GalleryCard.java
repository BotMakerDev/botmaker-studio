package com.botmaker.studio.ui.app.gallery;

import com.botmaker.studio.sharing.GalleryEntry;
import com.botmaker.studio.sharing.PublisherRank;
import com.botmaker.studio.util.BrowserLauncher;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * How one gallery listing reads: its name, author and description, its tags, and a link to its repository.
 *
 * <p>One builder for two windows. The home's <i>Get more bots</i> draws a card from it, with its own ★ count and
 * buttons under it (Browse Bots did until 2026-10-25); the publish dialog draws its preview from it. Two copies would be a preview that promises one card
 * and a gallery that shows another the first time either changes.
 */
public final class GalleryCard {

    private GalleryCard() {}

    /** The card's text column for {@code entry}. */
    public static VBox of(GalleryEntry entry) {
        Label name = new Label(entry.name().isBlank() ? "(no name)" : entry.name());
        name.getStyleClass().add("gallery-card-name");
        // The publisher's rank beside the name (2026-10-23), lowered to its plugins' when one of theirs is lower:
        // the badge says how far the whole bot is trusted. No "Tested on" line: what a bot launches is the SDK's.
        HBox title = new HBox(8, name, badge(entry.trust()));
        title.setAlignment(Pos.CENTER_LEFT);
        Label meta = new Label("by " + (entry.owner().isBlank() ? "you" : entry.owner())
                + (entry.description().isBlank() ? "" : " — " + entry.description()));
        meta.getStyleClass().add("gallery-card-note");
        meta.setWrapText(true);
        VBox text = new VBox(2, title, meta);
        if (!entry.basedOnLine().isBlank()) {
            // A fork that still follows its author: the author's releases reach it, so say whose they are.
            text.getChildren().add(note(entry.basedOnLine()));
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

    /** A catalog rank as a badge — Official, Trusted, Community — one style class per rank. */
    public static Label badge(PublisherRank rank) {
        Label badge = new Label(rank.displayName());
        badge.getStyleClass().addAll("rank-badge", "rank-badge-" + rank.id());
        badge.setTooltip(new Tooltip(switch (rank) {
            case ADMIN -> "Published by BotMaker";
            case TRUSTED -> "Published by a vetted developer";
            case COMMUNITY -> "Listed automatically; nobody reviewed its code";
        }));
        return badge;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("gallery-card-note");
        label.setWrapText(true);
        return label;
    }
}
