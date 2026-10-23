package com.botmaker.studio.sharing;

import com.botmaker.shared.github.GitHubAuth;
import com.botmaker.shared.github.GitHubClient;
import com.botmaker.studio.project.ProjectCreator;
import com.botmaker.studio.project.ProjectTemplate;
import com.botmaker.studio.project.UserLibrary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes a project as New Project does — Studio's blank one, or a published bot's release cloned into it — with
 * the picked plugins declared. The dialog and the assistant's {@code create_project} both call it, so both make
 * the same project. It blocks on the network: call it off the FX thread.
 */
public final class NewProjects {

    private NewProjects() {}

    /**
     * Creates {@code projectName} under the projects root from {@code bot} (null for blank) with
     * {@code plugins} declared, each at its catalog tag. A bot is cloned at its catalog tag, linked to its author
     * ({@link BotInstaller#install(GalleryEntry, String, java.nio.file.Path)}). A row with no tag is refused
     * before anything is created; a failed creation deletes its own directory ({@link ProjectCreator}).
     */
    public static void create(String projectName, GalleryEntry bot, List<PluginRegistry.Plugin> plugins,
                              GitHubClient client, GitHubAuth auth) throws IOException {
        List<ProjectCreator.PluginPick> picks = new ArrayList<>();
        for (PluginRegistry.Plugin plugin : plugins) {
            if (plugin.version().isBlank()) {
                throw new IOException("The catalog lists no release of " + plugin.coordinate()
                        + ". Untick it, or add it later from Project ▸ Plugins & Libraries.");
            }
            picks.add(new ProjectCreator.PluginPick(
                    new UserLibrary(plugin.groupId(), plugin.artifactId(), plugin.version()),
                    plugin.editorLibraries()));
        }
        ProjectCreator creator = new ProjectCreator();
        if (bot == null) {
            creator.createProject(projectName, ProjectTemplate.EMPTY, picks);
            return;
        }
        if (bot.tag().isBlank()) {
            throw new IOException(bot.name() + " lists no release, so there is nothing to start from.");
        }
        BotInstaller installer = new BotInstaller(new GitHubGallery(client, auth));
        creator.createFromBot(projectName, dest -> installer.install(bot, bot.tag(), dest), picks);
    }
}
