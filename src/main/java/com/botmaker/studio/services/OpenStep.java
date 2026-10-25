package com.botmaker.studio.services;

import java.util.List;
import java.util.stream.Stream;

/**
 * The steps the loading page ticks off while a bot opens (2026-10-25, run-mode spec § 1b): a bot ＋ New bot or Edit in
 * Studio copies, Get downloads or a {@code .bmk} file unpacks first, then every open's own.
 */
public enum OpenStep {
    DOWNLOADING("downloading", "Downloading", "Downloading…"),
    COPYING("copying", "Copying", "Copying…"),
    UNPACKING("unpacking", "Unpacking", "Unpacking…"),
    DEPENDENCIES("dependencies", "Dependencies", "Resolving dependencies…"),
    LIBRARIES("libraries", "Libraries", "Indexing libraries…"),
    PROJECT("project", "Project", "Loading project…"),
    FILES("files", "Files", "Reading project files…"),
    EDITOR("editor", "Editor", "Building the editor…");

    /** Every open's steps, in order. */
    public static final List<OpenStep> OPEN = List.of(DEPENDENCIES, LIBRARIES, PROJECT, FILES, EDITOR);

    private final String id;
    private final String displayName;
    private final String message;

    OpenStep(String id, String displayName, String message) {
        this.id = id;
        this.displayName = displayName;
        this.message = message;
    }

    public String id() {
        return id;
    }

    /** The checklist's word for the step: "Dependencies". */
    public String displayName() {
        return displayName;
    }

    /** The status line a reporter without a checklist shows for it: "Resolving dependencies…". */
    public String message() {
        return message;
    }

    /** {@code first}, then {@link #OPEN}: the checklist of a bot made on the page. */
    public static List<OpenStep> after(OpenStep first) {
        return Stream.concat(Stream.of(first), OPEN.stream()).toList();
    }
}
