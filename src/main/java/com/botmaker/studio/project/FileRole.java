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
 * <h2>{@code GENERATED} was deleted, and it is back — but only the file-level half</h2>
 *
 * <p>The old {@code GENERATED} classified everything BotMaker wrote into a user's source tree: the game-bot
 * entry point, {@code Activities}, {@code ActivityRegistry}, {@code FlowDriver}, {@code Templates}. Around
 * it grew {@code MethodLock} (a method-level grant inside a locked file), {@code GeneratedMembers} (a
 * member-level lock inside an editable one), {@code LockedRegions} (what may reach disk) and
 * {@code MemberVisibility} (what is worth drawing). All of it went on 2026-08-29 with the reason
 * <em>nothing generates a project's Java any more</em>, and a lock over files nobody rewrites protects
 * nothing.
 *
 * <p>That reason expired on 2026-09-20, when a plugin's model became compiled code
 * ({@code docs/refactor/33-plugin-java.md}). Something generates a project's Java again, and the user's own
 * statement of the rule is the test: <i>not editable — it's modified here in the flow editor, not in the
 * file.</i> An edit that appears to work and vanishes on the next save is worse than one never offered.
 *
 * <p><b>What came back is the role and nothing around it.</b> A generated file is rewritten <em>whole</em>,
 * so there is no partial grant to model: no method is the user's inside it and no member of an editable file
 * is the host's. The four member-level mechanisms stay deleted, and this enum stays a question about a file.
 *
 * <p>Enforcement of the <em>other</em> read-only case — a bot installed from the gallery, opened for reading
 * — is {@link ProjectMode}'s, and is applied in {@link LockResolver} rather than here: it is a property of
 * the checkout, not of the file.
 */
public enum FileRole {

    /** Ordinary user code. Fully editable, edits persist. */
    EDITABLE,

    /** Bundled library source under {@code com/botmaker/library}. Fully locked: no interaction at all. */
    LIBRARY,

    /**
     * A plugin's model, written by the host into the bot's own source tree and rewritten whole every time
     * that model is saved. Locked, because the editor that owns the model is where it is changed.
     */
    GENERATED;

    /** The directory under a bot's package that holds one package per plugin. */
    private static final String PLUGINS = "plugins";

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
            case GENERATED -> "Generated - Read Only";
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
            case GENERATED -> "This file is written by BotMaker from a plugin's model. Change it in the "
                    + "editor that owns the model; an edit here is overwritten the next time it is saved.";
        };
    }

    /**
     * Classifies {@code file}. Never null, and {@link #EDITABLE} for anything not recognised, so a file the
     * Studio does not know about always belongs to the user.
     *
     * <p>A generated file is recognised <b>by location only</b>: {@code …/plugins/<segment>/<Name>.java},
     * exactly one package below {@code plugins}, mirroring {@code PluginData}'s resource scheme so the two
     * cannot drift. That is narrow on purpose — a user's own {@code plugins.helpers.Util} is two levels
     * down and stays theirs — and the one collision it cannot avoid is a user who writes their own class
     * into a plugin's package, which is the package the host is going to rewrite anyway.
     */
    public static FileRole of(Path file) {
        if (file == null) return EDITABLE;
        String path = file.toString().replace("\\", "/");
        if (path.contains("com/botmaker/library")) return LIBRARY;
        return isPluginModel(path) ? GENERATED : EDITABLE;
    }

    private static boolean isPluginModel(String path) {
        int at = path.lastIndexOf("/" + PLUGINS + "/");
        if (at < 0 || !path.endsWith(".java")) return false;
        String below = path.substring(at + PLUGINS.length() + 2);
        return below.indexOf('/') == below.lastIndexOf('/') && below.indexOf('/') > 0;
    }
}
