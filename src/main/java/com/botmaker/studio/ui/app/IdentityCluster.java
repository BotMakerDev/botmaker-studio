package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.BlockTheme;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.util.StringConverter;

import java.util.function.Consumer;

/**
 * The far-right toolbar cluster: the theme dropdown and the (still disabled) Google button.
 *
 * <p>The ⑂ VCS and GitHub buttons stood here until 2026-09-26. VCS only raised the Versions tab, which has its
 * own tab; the GitHub account is used by the Versions tab alone (sharing, publishing, the commit author), so
 * its button is there now ({@code VersionsPane}'s account button).
 *
 * <p>Extracted from {@code UIManager} with its {@link BlockTheme} listener, which is the point: that listener
 * list is <b>static</b>, so a cluster that registered one and was then thrown away with its project kept the
 * whole dead scene graph alive and went on being called on every later theme change. Owning the registration
 * here means {@link #dispose()} can drop it.
 */
final class IdentityCluster {

    private final HBox node;
    /** Kept so {@link #dispose()} can unregister it from {@link BlockTheme}'s static listener list. */
    private final Consumer<BlockTheme.ThemeType> themeListener;

    IdentityCluster() {
        ComboBox<BlockTheme.ThemeType> theme = themeDropdown();
        this.themeListener = type -> {
            if (theme.getValue() != type) theme.setValue(type);
        };
        BlockTheme.addThemeChangeListener(themeListener);

        this.node = new HBox(6, theme, googleButton());
        this.node.setAlignment(Pos.CENTER_RIGHT);
    }

    Node node() {
        return node;
    }

    /** Drops the static theme registration. Idempotent — removing an absent listener is a no-op. */
    void dispose() {
        BlockTheme.removeThemeChangeListener(themeListener);
    }

    /**
     * The round Google button — <em>disabled</em>, with the reason as its tooltip. It used to be clickable and
     * its only action was an alert apologising that the feature doesn't exist; a greyed control with a reason
     * reads as "not yet", a clickable one that only apologises reads as broken. The sign-in plumbing behind it
     * ({@code sharing/GoogleAuth}, {@code GoogleAccountBar}) is finished and correct — it just has no client id
     * and no backend yet (see {@code sharing/GoogleConfig}).
     *
     * <p>Returned wrapped in a container because a disabled JavaFX control receives no mouse events, so a
     * tooltip installed on the button itself would never show; it goes on the (enabled) wrapper instead.
     */
    private static Node googleButton() {
        Button google = new Button("G");
        google.setStyle("-fx-background-radius: 14; -fx-min-width: 28; -fx-min-height: 28; "
                + "-fx-max-width: 28; -fx-max-height: 28; -fx-padding: 0; -fx-font-size: 10px; "
                + "-fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: #4285F4;");
        google.setDisable(true);
        HBox holder = new HBox(google);
        Tooltip.install(holder, new Tooltip(
                "Google sign-in isn't available yet — reserved for future Tailscale/Drive features."));
        return holder;
    }

    /**
     * The toolbar's theme picker — a dropdown of all four themes, wired straight to {@link BlockTheme}. Kept
     * in sync with the <b>View ▸ Theme</b> menu ({@link MenuBarManager}) via {@link BlockTheme}'s own listener
     * list (registered by the constructor, dropped by {@link #dispose()}), since both controls read and write
     * the same static state.
     */
    private static ComboBox<BlockTheme.ThemeType> themeDropdown() {
        ComboBox<BlockTheme.ThemeType> box =
                new ComboBox<>(FXCollections.observableArrayList(BlockTheme.ThemeType.values()));
        box.setConverter(new StringConverter<>() {
            @Override public String toString(BlockTheme.ThemeType type) {
                return switch (type) {
                    case DEFAULT -> "Default";
                    case DARK -> "Dark";
                    case BLACK -> "Black";
                    case HIGH_CONTRAST -> "High Contrast";
                };
            }
            @Override public BlockTheme.ThemeType fromString(String s) { return null; }
        });
        box.setValue(BlockTheme.getCurrentThemeType());
        box.setOnAction(e -> BlockTheme.setTheme(box.getValue()));
        box.setTooltip(new Tooltip("Theme"));
        return box;
    }
}
