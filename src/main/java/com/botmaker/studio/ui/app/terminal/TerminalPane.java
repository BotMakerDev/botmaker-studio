package com.botmaker.studio.ui.app.terminal;

import com.botmaker.studio.services.terminal.PtySession;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The Terminal tab: shells in the project's directory, one sub-tab each.
 *
 * <p>Nothing is started until the tab is first shown ({@link #activate()}), so a Studio that never opens it
 * never spawns a shell. Closing a sub-tab ends its shell; {@link #dispose()} ends them all, and must be called
 * by the window, which is rebuilt on every project open and reload.
 */
public final class TerminalPane {

    private final Path directory;
    private final TabPane tabs = new TabPane();
    private final BorderPane view = new BorderPane();
    private int opened;

    public TerminalPane(Path directory) {
        this.directory = directory;
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now != null && now.getUserData() instanceof TerminalView v) v.focus();
        });

        Button add = new Button("+ New shell");
        add.setTooltip(new Tooltip("A shell in " + directory));
        add.setOnAction(e -> open());
        Label where = new Label(directory.toString());
        where.getStyleClass().add("terminal-directory");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox bar = new HBox(6, where, gap, add);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(2, 6, 2, 6));
        bar.getStyleClass().add("terminal-bar");

        view.setTop(bar);
        view.setCenter(tabs);
    }

    public Node node() {
        return view;
    }

    /** The tab was shown: start the first shell if there is none, and give the current one the keyboard. */
    public void activate() {
        if (tabs.getTabs().isEmpty()) open();
        else if (tabs.getSelectionModel().getSelectedItem().getUserData() instanceof TerminalView v) v.focus();
    }

    /** Opens a new shell and selects it. */
    public void open() {
        String title = "shell " + ++opened;
        Tab tab = new Tab(title);
        TerminalView terminal = new TerminalView(
                new TerminalView.Launch(PtySession.defaultShell(), directory, Map.of()),
                code -> tab.setText(title + " (exited)"));
        tab.setContent(terminal.node());
        tab.setUserData(terminal);
        tab.setOnClosed(e -> terminal.dispose());
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
    }

    /** Ends every shell. */
    public void dispose() {
        for (Tab tab : List.copyOf(tabs.getTabs())) {
            if (tab.getUserData() instanceof TerminalView v) v.dispose();
        }
        tabs.getTabs().clear();
    }
}
