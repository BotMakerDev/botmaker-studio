package com.botmaker.studio.ui.app.home;

import com.botmaker.cli.bmk.BotJson;
import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.config.Constants;
import com.botmaker.studio.project.ProjectManager;
import com.botmaker.studio.project.ProjectPreferences;
import com.botmaker.studio.services.MavenService;
import com.botmaker.studio.services.ProgressReporter;
import com.botmaker.studio.sharing.BmkEdit;
import com.botmaker.studio.sharing.BmkOpen;
import com.botmaker.studio.sharing.BotInstaller;
import com.botmaker.studio.sharing.CatalogClient;
import com.botmaker.studio.sharing.GitHubGallery;
import com.botmaker.studio.sharing.UnpackedBots;
import com.botmaker.studio.ui.render.theme.ThemedWindows;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The windows around {@link BmkOpen} and {@link BmkEdit} (2026-10-25): the trust sheet, the refusal, the note that an
 * edited bot is an unlinked copy. The work runs off the FX thread; {@code status} gets a line while it does.
 */
public final class BmkFlow {

    private final Window owner;
    private final Consumer<String> status;

    public BmkFlow(Window owner, Consumer<String> status) {
        this.owner = owner;
        this.status = status;
    }

    /** Opens {@code bmk}, asking the user when the bot needs their trust; {@code onOpened} gets the unpacked folder. */
    public void open(Path bmk, Consumer<Path> onOpened) {
        open(bmk, false, onOpened, () -> { });
    }

    // openGot, the file Get downloaded, went on 2026-10-25: the loading page's Downloading step opens it
    // (RunHome.got, spec § 1b, P1).

    private void open(Path bmk, boolean agreed, Consumer<Path> onOpened, Runnable done) {
        status.accept("Opening " + bmk.getFileName() + "…");
        BmkOpen.Fetcher fetch = coordinates -> MavenService.fetch(coordinates, ProgressReporter.NONE);
        CatalogClient.session().fetch()
                .thenApplyAsync(catalog -> BmkOpen.open(bmk, catalog, ProjectPreferences.trustedBots(), agreed,
                        UnpackedBots.root(), fetch))
                .whenComplete((result, failed) -> Platform.runLater(() -> {
                    status.accept("");
                    if (failed != null) {
                        done.run();
                        error("Could not open this bot", failed.getMessage());
                        return;
                    }
                    switch (result) {
                        case BmkOpen.Opened opened -> {
                            done.run();
                            onOpened.accept(opened.dir());
                        }
                        case BmkOpen.Refused refused -> {
                            done.run();
                            error("Could not open this bot", refused.why());
                        }
                        case BmkOpen.NeedsTrust trust -> {
                            if (!agrees(trust)) {
                                done.run();
                                return;
                            }
                            if (trust.listed()) ProjectPreferences.trustBot(trust.key());
                            open(bmk, true, onOpened, done);
                        }
                    }
                }));
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

    /**
     * <i>Edit in Studio</i> on the unpacked bot in {@code unpacked}: the author's repository cloned at its tag, or a
     * copy when it cannot be reached ({@link BmkEdit}); {@code onEdited} gets the project to open.
     */
    public void edit(Path unpacked, BotJson bot, Consumer<Path> onEdited) {
        status.accept("Getting " + bot.name() + " from " + bot.owner() + "/" + bot.repo() + "…");
        BotInstaller installer = new BotInstaller(new GitHubGallery(new GitHubClient(), new GitHubAuth()));
        CompletableFuture.supplyAsync(() -> {
            try {
                List<Path> projects = MyBots.folders(new ProjectManager(), ProjectPreferences.recentDirectories(),
                        List.of());
                return BmkEdit.edit(unpacked, bot, projects, Constants.PROJECTS_ROOT, installer::install);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((edited, failed) -> Platform.runLater(() -> {
            status.accept("");
            if (failed != null) {
                Throwable cause = failed.getCause() == null ? failed : failed.getCause();
                error("Could not make an editable copy", cause.getMessage());
                return;
            }
            if (!edited.linked()) {
                Alert note = ThemedWindows.alert(Alert.AlertType.INFORMATION, edited.why(), ButtonType.OK);
                note.initOwner(owner);
                note.setHeaderText("An unlinked copy");
                note.showAndWait();
            }
            onEdited.accept(edited.dir());
        }));
    }

    private void error(String header, String body) {
        Alert alert = ThemedWindows.alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle("Error");
        alert.setHeaderText(header);
        alert.setContentText(body);
        alert.showAndWait();
    }
}
