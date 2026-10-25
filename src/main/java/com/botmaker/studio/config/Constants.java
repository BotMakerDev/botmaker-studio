package com.botmaker.studio.config;

import java.nio.file.Path;

/**
 * Application-wide constants
 */
public class Constants {

    // Debugger Configuration
    public static final int DEBUGGER_MAX_CONNECT_RETRIES = 10;
    public static final int DEBUGGER_RETRY_DELAY_MS = 250;
    /** {@code ~/BotMakerProjects}, or a sandbox's {@code projects/} ({@link AppPaths}). */
    public static final Path PROJECTS_ROOT = AppPaths.projectsRoot();

    /** Archived (soft-deleted) projects live here. A dot-dir, so the top-level project scan skips it. */
    public static final Path ARCHIVE_ROOT = PROJECTS_ROOT.resolve(".archive");

    private Constants() {} // Prevent instantiation
}
