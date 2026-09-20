package com.botmaker.studio.project;

import java.nio.file.Path;

/**
 * What a project file <em>is</em>, from the editor's point of view — the single source of truth for which
 * files the user owns.
 *
 * <p>Before this existed the rules were duplicated as inline path checks in {@code CodeEditorService.refreshUI}
 * and half-mirrored in {@code FileExplorerManager}'s cell factory, which is how a generated file ended up
 * read-only in the editor but freely deletable (with no confirmation) from the tree. Anything that needs to
 * know "may the user change this?" asks {@link #of} — do not re-derive it from a path.
 *
 * <h2>{@code GENERATED} is deleted, twice over</h2>
 *
 * <p>The old {@code GENERATED} classified everything BotMaker wrote into a user's source tree: the game-bot
 * entry point, {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates}. Around
 * it grew {@code MethodLock} (a method-level grant inside a locked file), {@code GeneratedMembers} (a
 * member-level lock inside an editable one), {@code LockedRegions} (what may reach disk) and
 * {@code MemberVisibility} (what is worth drawing). All of it went on 2026-08-29 with the reason
 * <em>nothing generates a project's Java any more</em>, and a lock over files nobody rewrites protects
 * nothing.
 *
 * <p>It came back for one day. On 2026-09-20 a plugin's model was briefly going to be a class the host
 * wrote whole, and a file-level role was the honest lock for that. The design was withdrawn the same day:
 * <b>the plugin ships the file and the host rewrites one expression inside it</b>
 * ({@code docs/refactor/33-plugin-java.md}). So the host generates nothing again — and locking the file a
 * plugin hands the user would fight the whole reason for handing it over, which is that a developer with no
 * BotMaker installed can read it, edit it and hand it to a compiler.
 *
 * <p>What replaced it is narrower by a whole file: {@code LockResolver} refuses a {@code @Managed} method's
 * body, because another window owns that one expression, and everything around it in the same file stays
 * the user's.
 *
 * <p>Enforcement of the <em>other</em> read-only case — a bot installed from the gallery, opened for reading
 * — is {@link ProjectMode}'s, and is applied in {@link LockResolver} rather than here: it is a property of
 * the checkout, not of the file.
 */
public enum FileRole {

    /** Ordinary user code. Fully editable, edits persist. */
    EDITABLE,

    /** Bundled library source under {@code com/botmaker/library}. Fully locked: no interaction at all. */
    LIBRARY;

    /** True when this file's contents are not the user's to change. */
    public boolean isReadOnly() {
        return this != EDITABLE;
    }

    /** True when blocks default to refusing interaction (menus suppressed). */
    public boolean suppressesInteraction() {
        return this != EDITABLE;
    }

    /** A short suffix for the editor status line / explorer label, or {@code null} for ordinary files. */
    public String badge() {
        return switch (this) {
            case EDITABLE -> null;
            case LIBRARY -> "Library - Read Only";
        };
    }

    /**
     * Why an edit here is refused, or {@code null} when it is not.
     *
     * <p>Beside {@link #badge()} rather than in {@link LockResolver}, so that a role added later cannot be
     * given a badge and no sentence: the two answers are about the same fact and a caller reaching for one
     * usually wants the other.
     */
    public String reason() {
        return switch (this) {
            case EDITABLE -> null;
            case LIBRARY -> "This is bundled library code — it can't be edited.";
        };
    }

    /**
     * Classifies {@code file}. Never null, and {@link #EDITABLE} for anything not recognised, so a file the
     * Studio does not know about always belongs to the user.
     *
     * <p>One rule left, and it is about bundled code rather than about anything in the project: a file under
     * {@code com/botmaker/library} is Studio's own. A file under {@code plugins/<segment>/} was classed
     * {@code GENERATED} for one day — see the note above — and is ordinary user code, because that is
     * exactly what a plugin handed the user.
     */
    public static FileRole of(Path file) {
        if (file == null) return EDITABLE;
        String path = file.toString().replace("\\", "/");
        return path.contains("com/botmaker/library") ? LIBRARY : EDITABLE;
    }
}
