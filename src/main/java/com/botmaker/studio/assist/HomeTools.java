package com.botmaker.studio.assist;

import com.botmaker.plugin.api.assist.AssistantTool;
import com.botmaker.plugin.api.assist.Describe;
import com.botmaker.studio.assist.HomeEndpoint.Shell;
import com.botmaker.core.project.ProjectManager;
import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.sharing.UnpackedBots;
import com.botmaker.studio.ui.app.AssistedNewProject;
import com.botmaker.studio.ui.app.home.MyBots;
import com.botmaker.studio.ui.app.home.RunHome;
import javafx.application.Platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.botmaker.studio.assist.McpTools.given;
import static com.botmaker.studio.assist.McpTools.said;

/**
 * The home endpoint's own tools beside {@code ui} (2026-10-28): the bot list read, a project opened and a new one
 * made without a click, so a session starts from the home as well as from a project.
 */
final class HomeTools {

    private static final DateTimeFormatter USED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    record OpenProject(@Describe("the project's folder, absolute, as list_projects gives it") String path,
                       @Describe(value = "true for the Runner, which runs the bot; left out, the editor",
                               optional = true) Boolean run) {
    }

    private HomeTools() {
    }

    static List<AssistantTool<?>> of(Shell shell) {
        AssistedNewProject newProject = new AssistedNewProject(new PluginRegistry(), HomeEndpoint::mainWindow);
        return List.of(
                said("list_projects", "List My bots as the bot list shows them, newest first: each one's name, "
                        + "whose, its version and its folder.", HomeTools::listProjects),
                said("open_project", "Open a project in the editor, or the Runner. The editor's endpoint takes "
                                + "this address over once it is open, with the project's tools; the Runner serves "
                                + "none, so these stay.",
                        OpenProject.class, call -> openProject(shell, call.path(), Boolean.TRUE.equals(call.run()))),
                said("list_bots", "List the published bots create_project can start from.", newProject::listBots),
                said("create_project", "Create a project, blank or cloned from a published bot's release, and "
                                + "open it in the editor.",
                        ProjectTools.NewProject.class, call -> newProject.create(call.name(), given(call.bot()),
                                ProjectTools.pluginIds(call.plugins()), dir -> {
                                    Platform.runLater(() -> shell.open(dir, false));
                                    return opening(false);
                                })));
    }

    static String listProjects() {
        List<MyBots.MyBot> bots = RunHome.myBots();
        if (bots.isEmpty()) return "My bots is empty: create_project makes one.";
        return String.join("\n", bots.stream().map(HomeTools::line).toList());
    }

    private static String line(MyBots.MyBot bot) {
        String whose = bot.owner().map(o -> "by " + o).orElse("mine");
        String version = bot.version().map(v -> ", " + v).orElse("");
        String used = bot.lastUsed().equals(LocalDateTime.MIN) ? "" : ", used " + USED.format(bot.lastUsed());
        String state = (bot.state() == MyBots.State.GONE ? ", its folder is gone" : "")
                + (bot.unpacked() ? ", a .bmk to run" : "")
                + bot.available().map(t -> ", " + t + " available").orElse("");
        return bot.name() + "  (" + whose + version + used + state + ")  " + bot.dir();
    }

    static String openProject(Shell shell, String path, boolean run) {
        Path dir;
        try {
            dir = Path.of(given(path).strip());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a path: " + path);
        }
        if (!dir.isAbsolute()) throw new IllegalArgumentException("Give the folder's absolute path: " + path);
        Path wanted = dir.normalize();
        if (!Files.isDirectory(wanted)) throw new IllegalArgumentException("No folder " + wanted + ".");
        if (!UnpackedBots.isUnpacked(wanted) && new ProjectManager().projectAt(wanted).isEmpty()) {
            throw new IllegalArgumentException(wanted + " has no " + ProjectManager.PROJECT_SHAPE
                    + ", so there is no project here to open.");
        }
        // A project made after the user agrees would switch the window away from this one.
        String asked = OnScreen.busy();
        if (!asked.isEmpty()) throw new IllegalArgumentException(asked + " Wait for it: an answer after it says how it ended.");
        Platform.runLater(() -> shell.open(wanted, run));
        return "Opening " + wanted.getFileName() + ". " + opening(run);
    }

    private static String opening(boolean run) {
        return run ? "The Runner serves no project's tools: ui drives it, and these tools stay."
                : "Its endpoint takes this address over once the editor is open, and this connection ends; the "
                + "project's tools come back on the same address.";
    }
}
