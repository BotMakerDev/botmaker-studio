package com.botmaker.studio.project;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.Remote;
import com.botmaker.studio.project.vcs.SyncModel;
import com.botmaker.studio.sharing.BotSource;
import com.botmaker.studio.sharing.UnpackedBots;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether a project opens for <em>reading</em> (someone else's installed bot, shown full-colour but with every
 * edit path refused) or for <em>editing</em> (your own code). This is a per-checkout, local-only distinction —
 * it never ships with a published bot — so it is derived from provenance plus a local opt-in marker rather than
 * persisted in {@code settings.json}:
 *
 * <ul>
 *   <li>A locally-created project (no {@link BotSource} provenance) is <b>always editable</b> — it is yours.</li>
 *   <li>A bot whose provenance names the <b>signed-in account</b> is also always editable: you published it,
 *       so re-installing your own bot on a second machine must not present it as somebody else's. So is a bot
 *       whose {@code mine} is your fork of it ({@link #isYours}).</li>
 *   <li>Any other installed bot defaults to <b>reading</b>, until the user chooses "Improve this bot", which
 *       drops the {@link #MARKER} file and flips it to editing for good.</li>
 * </ul>
 *
 * <p>Nothing here decides how a project is <em>drawn</em> any more — an installed bot opens in the Runner
 * window, not in a stripped-down editor — and the editor can preview that window at will. That preview is
 * session-only and deliberately does not route through here: previewing what a user sees must never leave a
 * marker behind that changes what the project is.
 *
 * <p>Enforcement is not here — it is in {@link LockResolver}, the one authority on whether a node may change.
 * This class only decides the initial/effective mode; {@code LockResolver} turns "reader" into a denial that
 * outranks every {@link FileRole}/{@link MethodLock} verdict.
 */
public final class ProjectMode {

    /** Local opt-in marker: its presence means the user switched an installed bot to Editor mode. */
    public static final String MARKER = ".botmaker-editing";

    private ProjectMode() {}

    /** True when {@code projectDir} should open read-only, judged against the account signed in on this machine. */
    public static boolean isReader(Path projectDir) {
        return isReader(projectDir, GitHubAuth.rememberedLogin());
    }

    /**
     * The rule itself, with the signed-in login passed in so it can be tested without credentials on disk.
     *
     * @param signedInLogin the GitHub login signed in here, or blank/null when nobody is
     */
    public static boolean isReader(Path projectDir, String signedInLogin) {
        if (projectDir == null) return false;
        // A bot opened from a .bmk is for running only: editing it goes through a clone (BmkEdit, 2026-10-25).
        if (UnpackedBots.isUnpacked(projectDir)) return true;
        BotSource source = BotSource.read(projectDir).orElse(null);
        if (source == null) return false;                 // locally created → always yours to edit
        if (isYours(projectDir, source, signedInLogin)) return false;  // your own bot, or your fork of one
        return !Files.exists(projectDir.resolve(MARKER));
    }

    /**
     * Whether an installed project is the signed-in user's: they published it (its provenance names them), or
     * its {@code mine} is their fork of it (2026-10-23) — a fork they publish as a bot of their own is theirs to
     * edit on every computer, though it still follows its author.
     */
    public static boolean isYours(Path projectDir, BotSource source, String login) {
        if (login == null || login.isBlank()) return false;
        if (source.owner() != null && source.owner().equalsIgnoreCase(login.trim())) return true;
        return SyncModel.slug(new ProjectVcs(projectDir).remoteUrl(Remote.MINE))
                .filter(mine -> mine.owner().equalsIgnoreCase(login.trim())).isPresent();
    }

    /** Marks {@code projectDir} as opted into Editor mode (idempotent). */
    public static void switchToEditor(Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(MARKER), "editor" + System.lineSeparator());
    }
}
