package com.botmaker.studio.ui.app;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * Which of the Assistant window's panes are shown (spec {@code 2026-11-11-assistant-window-design.md}): any can be
 * hidden but the last one shown, and choosing a call shows Detail. Kept on this computer only, like {@code ShowCommunity}.
 *
 * <p>The rules are on the value ({@link #toggled}, {@link #canHide}), tested without the preference store; {@link #load}
 * and {@link #save} are the edges.
 */
record AssistantPanes(Set<Pane> shown) {

    /** The window's panes, left to right. */
    enum Pane {
        CHAT("chat", "Chat"),
        CALLS("calls", "Calls"),
        DETAIL("detail", "Detail");

        private final String id;
        private final String displayName;

        Pane(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        String id() {
            return id;
        }

        String displayName() {
            return displayName;
        }

        /** The pane {@code id} names, if any (a kept choice may name one a newer Studio added). */
        static Optional<Pane> fromId(String id) {
            for (Pane pane : values()) if (pane.id.equals(id)) return Optional.of(pane);
            return Optional.empty();
        }
    }

    /** Every pane: a first open, and a choice that cannot be read. */
    static final AssistantPanes ALL = new AssistantPanes(EnumSet.allOf(Pane.class));

    /** The toggle's tooltip while it cannot hide its pane. */
    static final String LAST_ONE = "One pane stays shown";

    private static final String PREFS_NODE = "/com/botmaker/studio";
    private static final String PREFS_KEY = "assistant.panes";

    AssistantPanes {
        if (shown == null || shown.isEmpty()) throw new IllegalArgumentException("one pane stays shown");
        shown = Set.copyOf(EnumSet.copyOf(shown));
    }

    boolean shows(Pane pane) {
        return shown.contains(pane);
    }

    /** Whether {@code pane}'s toggle may hide it now: not when it is the last one shown. */
    boolean canHide(Pane pane) {
        return !(shown.size() == 1 && shown.contains(pane));
    }

    /** {@code pane} hidden if shown, shown if hidden; the last one shown stays. */
    AssistantPanes toggled(Pane pane) {
        if (!shows(pane)) return showing(pane);
        if (!canHide(pane)) return this;
        EnumSet<Pane> less = EnumSet.copyOf(shown);
        less.remove(pane);
        return new AssistantPanes(less);
    }

    /** {@code pane} shown too (choosing a call shows Detail). */
    AssistantPanes showing(Pane pane) {
        if (shows(pane)) return this;
        EnumSet<Pane> more = EnumSet.copyOf(shown);
        more.add(pane);
        return new AssistantPanes(more);
    }

    /** As kept: the shown panes' ids, comma-separated, left to right. */
    String id() {
        return EnumSet.copyOf(shown).stream().map(Pane::id).collect(Collectors.joining(","));
    }

    /** The panes {@code id} names; {@link #ALL} when it names none (absent, empty, or written by another Studio). */
    static AssistantPanes fromId(String id) {
        if (id == null) return ALL;
        EnumSet<Pane> named = EnumSet.noneOf(Pane.class);
        Arrays.stream(id.split(",")).map(String::strip).map(Pane::fromId).flatMap(Optional::stream).forEach(named::add);
        return named.isEmpty() ? ALL : new AssistantPanes(named);
    }

    /** This computer's choice; every pane when the preference store cannot be read. */
    static AssistantPanes load() {
        try {
            return fromId(Preferences.userRoot().node(PREFS_NODE).get(PREFS_KEY, null));
        } catch (Exception e) {
            return ALL;
        }
    }

    /** Writes this choice as this computer's. Best effort: one that cannot be written stays as it was. */
    void save() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.put(PREFS_KEY, id());
            prefs.flush();
        } catch (Exception e) {
            System.err.println("Could not remember the Assistant window's panes: " + e.getMessage());
        }
    }
}
