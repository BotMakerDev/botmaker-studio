package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.UserLibrary;
import com.botmaker.studio.services.JitPackSearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Makes a project as New Project does — Studio's blank one, or a published bot's release cloned into it — with
 * the picked plugins declared. The dialog and the assistant's {@code create_project} both call it, so both make
 * the same project. It blocks on the network: call it off the FX thread.
 */
public final class NewProjects {

    /** The longest one lookup may take: a stalled GitHub or JitPack must not hold the caller forever. */
    private static final long NETWORK_SECONDS = 60;

    private NewProjects() {}

    /**
     * Creates {@code projectName} under the projects root from {@code bot} (null for blank) with
     * {@code plugins} declared. A bot is cloned at the release Browse Bots would install, linked to its author
     * ({@link BotInstaller#install(GalleryEntry, String, java.nio.file.Path)}). The versions are resolved before
     * anything is created, so a lookup that fails leaves nothing behind; a failed creation deletes its own
     * directory ({@link ProjectCreator}).
     */
    public static void create(String projectName, GalleryEntry bot, List<PluginRegistry.Plugin> plugins,
                              GitHubClient client, GitHubAuth auth) throws IOException {
        PluginCatalog catalog = new PluginCatalog(new PluginRegistry(client), new JitPackSearch());
        List<ProjectCreator.PluginPick> picks = new ArrayList<>();
        for (PluginRegistry.Plugin plugin : plugins) {
            String version = catalog.version(plugin).orTimeout(NETWORK_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(failure -> null).join();
            if (version == null || version.isBlank()) {
                throw new IOException("Could not resolve a version for " + plugin.coordinate()
                        + ". Untick it, or add it later from Project ▸ Plugins & Libraries.");
            }
            picks.add(new ProjectCreator.PluginPick(
                    new UserLibrary(plugin.groupId(), plugin.artifactId(), version), plugin.editorLibraries()));
        }
        ProjectCreator creator = new ProjectCreator();
        if (bot == null) {
            creator.createProject(projectName, ProjectTemplate.EMPTY, picks);
            return;
        }
        GitHubGallery gallery = new GitHubGallery(client, auth);
        BotInstaller installer = new BotInstaller(client, gallery);
        String tag;
        try {
            tag = gallery.installTag(bot).orTimeout(NETWORK_SECONDS, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() instanceof TimeoutException ? null : e.getCause();
            throw new IOException("Could not find " + bot.name() + "'s release"
                    + (cause == null ? " in " + NETWORK_SECONDS + "s." : ": " + cause.getMessage()), e);
        }
        if (tag == null || tag.isBlank()) {
            throw new IOException(bot.name() + " has no published release yet, so there is nothing to start "
                    + "from. Ask its author to cut one.");
        }
        creator.createFromBot(projectName, dest -> installer.install(bot, tag, dest), picks);
    }
}
