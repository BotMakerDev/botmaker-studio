// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app;

import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;

/**
 * The last row of the project window (2026-11-13): the bot's name and SDK version, the status line, and on the right
 * the badge <i>⚠ n problems · ⚑ n to review ▴</i>. A click on the badge opens one list above it: <b>Problems</b>, the
 * {@link DiagnosticsPanel} that was the Errors tab, and <b>To review</b>, the {@link ReviewPanel} that was the Review
 * tab — their own nodes, so their filters, rows and menus are the same ones.
 */
final class ProjectStatusBar {

    static final String BADGE_ID = "status-badge";
    /** The badge's colour: problems first, then something to review (blocks.css). */
    private static final PseudoClass HAS_PROBLEMS = PseudoClass.getPseudoClass("problems");
    private static final PseudoClass HAS_REVIEW = PseudoClass.getPseudoClass("review");

    private final Label identity = new Label();
    private final Button badge = new Button();
    private final Popup popup = new Popup();
    /** The popup's content: Problems over To review. */
    private final VBox list;
    private final HBox node;
    private final String botName;
    private final Runnable onOpen;
    private int problems;
    private int toReview;

    /**
     * @param status   the status line, which keeps its own id and its {@code StatusMessageEvent}s
     * @param problems {@link DiagnosticsPanel#node()}
     * @param review   {@link ReviewPanel#node()}
     * @param onOpen   run each time the list opens, before it shows: the review list is a scan, re-read then
     */
    ProjectStatusBar(String botName, Label status, Node problems, Node review, Runnable onOpen) {
        this.botName = botName;
        this.onOpen = onOpen;
        identity.getStyleClass().add("status-identity");
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        badge.setId(BADGE_ID);
        badge.getStyleClass().add("status-badge");
        badge.setTooltip(new Tooltip("What does not compile, and what BotMaker changed for you to look at"));
        badge.setOnAction(e -> toggle());
        node = new HBox(8, identity, status, badge);
        node.setAlignment(Pos.CENTER_LEFT);
        // The band's fill, hairline and padding, as the status label had them alone.
        node.getStyleClass().add("status-bar");

        Label problemsTitle = new Label("Problems");
        Label reviewTitle = new Label("To review");
        problemsTitle.getStyleClass().add("nav-popup-title");
        reviewTitle.getStyleClass().add("nav-popup-title");
        VBox.setVgrow(problems, Priority.ALWAYS);
        VBox.setVgrow(review, Priority.ALWAYS);
        list = new VBox(6, problemsTitle, problems, reviewTitle, review);
        list.getStyleClass().addAll("nav-popup", "status-list");
        list.setPrefSize(620, 460);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(list);
        ThemedWindows.addStylesheet(popup.getScene());
        ThemedWindows.applyThemeClass(list);

        sdk("");
        render();
    }

    HBox node() {
        return node;
    }

    /** The SDK version the bot's pom pins; blank when it names none (a bot without the SDK). */
    void sdk(String version) {
        identity.setText(version == null || version.isBlank() ? botName : botName + " · SDK " + version);
    }

    /** Errors and warnings now — {@link DiagnosticsPanel}'s count. */
    void problems(int count) {
        problems = count;
        render();
    }

    /** Rows to review now — {@link ReviewPanel}'s count. */
    void toReview(int count) {
        toReview = count;
        render();
    }

    String badgeText() {
        return badge.getText();
    }

    /** The badge's words: {@code ⚠ 2 problems · ⚑ 1 to review ▴}. */
    static String badge(int problems, int toReview) {
        return "⚠ " + problems + (problems == 1 ? " problem" : " problems") + " · ⚑ " + toReview + " to review ▴";
    }

    private void render() {
        badge.setText(badge(problems, toReview));
        badge.pseudoClassStateChanged(HAS_PROBLEMS, problems > 0);
        badge.pseudoClassStateChanged(HAS_REVIEW, toReview > 0);
    }

    /** Opens the list above the badge, as a click on it does; Project ▸ Review Changes and the plugins window too. */
    void open() {
        if (popup.isShowing() || badge.getScene() == null || badge.getScene().getWindow() == null) return;
        onOpen.run();
        ThemedWindows.applyThemeClass(list);
        Bounds at = badge.localToScreen(badge.getBoundsInLocal());
        popup.show(badge, at.getMaxX(), at.getMinY());
        // Its size is known once shown: right edge on the badge's, bottom on its top, inside the badge's own
        // screen — which may sit left of or above the primary one, at negative coordinates.
        Rectangle2D screen = Screen.getScreensForRectangle(at.getMinX(), at.getMinY(), at.getWidth(), at.getHeight())
                .stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        popup.setX(Math.max(screen.getMinX(), at.getMaxX() - popup.getWidth()));
        popup.setY(Math.max(screen.getMinY(), at.getMinY() - popup.getHeight()));
    }

    boolean isOpen() {
        return popup.isShowing();
    }

    void close() {
        popup.hide();
    }

    private void toggle() {
        if (popup.isShowing()) close();
        else open();
    }
}
