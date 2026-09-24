package com.botmaker.studio.ui.render.menu;

import com.botmaker.studio.palette.BlockCategory;
import com.botmaker.studio.palette.ExpressionCategory;
import javafx.geometry.Pos;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * How one palette entry is drawn in a menu: a dot in its category's colour, its glyph, its name, and what it
 * does — as a tooltip, or as a second line under the name where the list is a flat set of search results and
 * nothing else says where the entry comes from.
 *
 * <p>A {@link CustomMenuItem} because a plain {@code MenuItem} takes no tooltip. Its text is still set: the
 * row draws only its content, and the text is what the menus search and collect leaves by. Styling is the
 * MENU section of {@code blocks.css}; the dot takes {@code -bm-own-accent} from the category class, so it
 * is the colour the block itself is drawn in.
 */
final class MenuRows {

    private MenuRows() {}

    /** A palette row. {@code categoryClass} is a {@code category-*} class, or null for no dot. */
    static CustomMenuItem entry(String glyph, String categoryClass, String name, String description,
                                boolean describeInline, Runnable action) {
        Region dot = new Region();
        dot.getStyleClass().add("menu-cat-dot");
        if (categoryClass != null) dot.getStyleClass().add(categoryClass);
        else dot.setVisible(false);

        Label title = new Label(name);
        title.getStyleClass().add("menu-entry-name");
        VBox text = new VBox(title);
        if (describeInline && description != null) {
            Label detail = new Label(description);
            detail.getStyleClass().add("menu-entry-description");
            text.getChildren().add(detail);
        }
        HBox row = new HBox(8, dot, MenuIcons.node(glyph), text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("menu-entry");
        if (description != null && !describeInline) Tooltip.install(row, new Tooltip(description));

        CustomMenuItem item = new CustomMenuItem(row, true);
        item.setText(name);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** The {@code category-*} class a statement category is drawn in. */
    static String categoryClass(BlockCategory category) {
        return category == null ? null : category.styleClass();
    }

    /**
     * The block colour a value menu section borrows. A value has no category of its own; these follow what
     * the section's blocks are about — a value, a name, a calculation, a shape.
     */
    static String categoryClass(ExpressionCategory category) {
        if (category == null) return null;
        return switch (category) {
            case LITERAL -> BlockCategory.OUTPUT.styleClass();
            case REFERENCE -> BlockCategory.VARIABLES.styleClass();
            case MATH, COMPARISON, LOGIC -> BlockCategory.FLOW.styleClass();
            case STRUCTURE -> BlockCategory.FUNCTIONS.styleClass();
        };
    }

    /** "3 results" above a search's flat list, so an empty-looking menu is never ambiguous. */
    static MenuItem resultCount(int count) {
        return MenuBuilders.sectionHeader(count == 1 ? "1 result" : count + " results");
    }
}
