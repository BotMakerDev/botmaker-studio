package com.botmaker.studio.ui.app.home;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.OpenStep;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.sharing.BmkEdit;
import com.botmaker.studio.sharing.BmkOpen;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.CatalogIndex;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.ui.app.loading.LoadingPage;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * {@link BmkOpen} and {@link BmkEdit} as makings for the loading page (2026-10-25, N12, N13): the work runs on the
 * page's first step, and the windows it needs — the trust sheet, the note that an edited bot is an unlinked copy —
 * are asked on the FX thread while it waits. A refusal is the page's failed step.
 */
public final class BmkFlow {

    private final Window owner;

    public BmkFlow(Window owner) {
        this.owner = owner;
    }

    /**
     * Opening {@code bmk} — from {@code --open}, a double-click or a drop — on the Unpacking step, then the Runner.
     * The bot is known by its file name until its {@code bot.json} is read.
     */
    public LoadingPage.Making opening(Path bmk) {
        String file = bmk.getFileName().toString();
        String name = file.toLowerCase(Locale.ROOT).endsWith(".bmk") ? file.substring(0, file.length() - 4)
                : file;
        return new LoadingPage.Making(LoadingPage.Bot.named(name, Optional.empty()), OpenStep.UNPACKING,
                progress -> unpack(bmk, progress), LoadingPage.Opens.RUNNER);
    }

    /**
     * <i>Edit in Studio</i> on the unpacked bot in {@code unpacked}, on the Copying step: the author's repository
     * cloned at its tag, or a copy when it cannot be reached ({@link BmkEdit}), then the editor on it.
     */
    public LoadingPage.Making editing(Path unpacked, BotJson bot) {
        return new LoadingPage.Making(LoadingPage.Bot.named(bot.name(), Optional.empty()), OpenStep.COPYING,
                progress -> edit(unpacked, bot, progress), LoadingPage.Opens.EDITOR);
    }

    /** Blocking: {@code bmk} checked, trusted and unpacked; the folder, or why not. */
    private Path unpack(Path bmk, ProgressReporter progress) throws Exception {
        progress.message("Unpacking " + bmk.getFileName() + "…");
        BmkOpen.Fetcher fetch = coordinates -> MavenService.fetch(coordinates, ProgressReporter.NONE);
        CatalogIndex catalog;
        try {
            catalog = CatalogClient.session().fetch().join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
        boolean agreed = false;
        while (true) {
            BmkOpen.Result result = BmkOpen.open(bmk, catalog, ProjectPreferences.trustedBots(), agreed,
                    UnpackedBots.root(), fetch);
            switch (result) {
                case BmkOpen.Opened opened -> {
                    return opened.dir();
                }
                case BmkOpen.Refused refused -> throw new IOException(refused.why());
                case BmkOpen.NeedsTrust trust -> {
                    if (!onFx(() -> agrees(trust))) throw new LoadingPage.Cancelled();
                    if (trust.listed()) ProjectPreferences.trustBot(trust.key());
                    agreed = true;
                }
            }
        }
    }

    /** Blocking: the editable copy of {@code unpacked}, the user told first when it is not linked. */
    private Path edit(Path unpacked, BotJson bot, ProgressReporter progress) throws IOException {
        progress.message("Getting " + bot.name() + " from " + bot.owner() + "/" + bot.repo() + "…");
        BotInstaller installer = new BotInstaller(new GitHubGallery(new GitHubClient(), new GitHubAuth()));
        List<Path> projects = MyBots.folders(new ProjectManager(), ProjectPreferences.recentDirectories(), List.of());
        BmkEdit.Edited edited = BmkEdit.edit(unpacked, bot, projects, Constants.PROJECTS_ROOT, installer::install);
        if (!edited.linked()) {
            onFx(() -> {
                Alert note = ThemedWindows.alert(Alert.AlertType.INFORMATION, edited.why(), ButtonType.OK);
                note.initOwner(owner);
                note.setHeaderText("An unlinked copy");
                note.showAndWait();
                return true;
            });
        }
        return edited.dir();
    }

    /** The trust sheet: <i>Run it</i> or <i>Cancel</i>. */
    private boolean agrees(BmkOpen.NeedsTrust trust) {
        ButtonType runIt = new ButtonType("Run it", ButtonBar.ButtonData.OK_DONE);
        Alert sheet = ThemedWindows.alert(trust.listed() ? Alert.AlertType.CONFIRMATION : Alert.AlertType.WARNING,
                trust.warning(), runIt, ButtonType.CANCEL);
        sheet.initOwner(owner);
        sheet.setHeaderText("Open " + trust.bot().name() + " " + trust.bot().tag() + "?");
        return sheet.showAndWait().filter(runIt::equals).isPresent();
    }

    /** {@code ask}'s answer, asked on the FX thread while the making's thread waits. */
    static <T> T onFx(Supplier<T> ask) {
        if (Platform.isFxApplicationThread()) return ask.get();
        CompletableFuture<T> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                answer.complete(ask.get());
            } catch (RuntimeException e) {
                answer.completeExceptionally(e);
            }
        });
        return answer.join();
    }
}
