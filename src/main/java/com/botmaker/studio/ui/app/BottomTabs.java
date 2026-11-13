package com.botmaker.studio.ui.app;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The bottom tool window's tabs, which come and go since 2026-11-13: the {@link BottomTab#always()} ones are always
 * there; <b>Debug</b> while a debug session lasts, <b>Usages</b> from Find Usages until it is closed, and
 * <b>Terminal</b> from View ▸ Terminal, kept in dev mode. A tab that comes back takes its place in the enum's order.
 */
final class BottomTabs {

    private final TabPane pane = new TabPane();
    private final EnumMap<BottomTab, Tab> tabs = new EnumMap<>(BottomTab.class);
    /** View ▸ Terminal's check: whether the Terminal tab is there, either way round. */
    private final BooleanProperty terminal = new SimpleBooleanProperty();
    /** Dev mode: the Terminal tab stays, and View ▸ Terminal is greyed. */
    private final ReadOnlyBooleanWrapper terminalKept = new ReadOnlyBooleanWrapper();

    /** @param contents each tab's node; a tab missing here is never shown */
    BottomTabs(Map<BottomTab, Node> contents) {
        contents.forEach((which, node) -> {
            Tab tab = new Tab(which.title(), node);
            tab.setClosable(which.closable());
            // Before its ✕ takes it away: Run comes forward, as hide() does, not the neighbour JavaFX would pick.
            tab.setOnCloseRequest(e -> {
                if (pane.getSelectionModel().getSelectedItem() == tab) select(BottomTab.RUN);
            });
            tabs.put(which, tab);
        });
        for (BottomTab which : tabs.keySet()) {
            if (which.always()) show(which);
        }
        // Ticked from the menu: the tab comes forward, which starts its first shell. Already there (dev mode, or the
        // list listener below catching up): nothing moves.
        terminal.addListener((o, was, now) -> {
            if (now && !isShown(BottomTab.TERMINAL)) select(BottomTab.TERMINAL);
            else if (!now) hide(BottomTab.TERMINAL);
        });
        // The tab's own ✕ unticks the menu item.
        pane.getTabs().addListener((ListChangeListener<Tab>) c -> terminal.set(isShown(BottomTab.TERMINAL)));
    }

    TabPane node() {
        return pane;
    }

    /** Every tab, shown or not, for the saved layout to name. */
    Map<BottomTab, Tab> tabs() {
        return Collections.unmodifiableMap(tabs);
    }

    boolean isShown(BottomTab which) {
        Tab tab = tabs.get(which);
        return tab != null && pane.getTabs().contains(tab);
    }

    /** Adds {@code which} in its place, unselected; a no-op when it is there. */
    void show(BottomTab which) {
        Tab tab = tabs.get(which);
        if (tab == null || pane.getTabs().contains(tab)) return;
        int at = 0;
        for (Tab shown : pane.getTabs()) {
            BottomTab key = keyOf(shown);
            if (key != null && key.ordinal() < which.ordinal()) at++;
        }
        pane.getTabs().add(at, tab);
    }

    /** Takes {@code which} away; when it was the one showing, Run shows instead of its neighbour. */
    void hide(BottomTab which) {
        Tab tab = tabs.get(which);
        if (tab == null || !pane.getTabs().contains(tab)) return;
        if (pane.getSelectionModel().getSelectedItem() == tab && which != BottomTab.RUN) select(BottomTab.RUN);
        pane.getTabs().remove(tab);
    }

    /** Shows {@code which} and brings it forward. */
    void select(BottomTab which) {
        show(which);
        Tab tab = tabs.get(which);
        if (tab != null) pane.getSelectionModel().select(tab);
    }

    /**
     * A saved layout's tab: selected when it is there at open, else Run — a tab that comes and goes (Debug, Usages,
     * Terminal outside dev mode) or one gone for good ({@code null}) never opens on its own.
     */
    void restore(BottomTab saved) {
        select(saved != null && isShown(saved) ? saved : BottomTab.RUN);
    }

    /** The selected tab, or {@code null} when none is (or one not of this set). */
    BottomTab selected() {
        Tab tab = pane.getSelectionModel().getSelectedItem();
        return tab == null ? null : keyOf(tab);
    }

    BooleanProperty terminalProperty() {
        return terminal;
    }

    ReadOnlyBooleanProperty terminalKeptProperty() {
        return terminalKept.getReadOnlyProperty();
    }

    /**
     * Dev mode on keeps the Terminal tab, without its ✕; off gives the ✕ back and leaves the tab where it is,
     * for the user to close.
     */
    void keepTerminal(boolean devMode) {
        terminalKept.set(devMode);
        Tab tab = tabs.get(BottomTab.TERMINAL);
        if (tab != null) tab.setClosable(!devMode);
        // Shown, not selected: dev mode at open starts no shell until the tab is clicked.
        if (devMode) show(BottomTab.TERMINAL);
    }

    /** The key of {@code tab}, or {@code null} for a tab this set did not make. */
    private BottomTab keyOf(Tab tab) {
        for (Map.Entry<BottomTab, Tab> e : tabs.entrySet()) {
            if (e.getValue() == tab) return e.getKey();
        }
        return null;
    }
}
