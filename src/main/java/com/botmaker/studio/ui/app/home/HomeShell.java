package com.botmaker.studio.ui.app.home;

import com.botmaker.core.sharing.PluginRegistry;
import com.botmaker.core.assist.HomeEndpoint;
import com.botmaker.studio.assist.OnScreen;
import com.botmaker.studio.ui.app.AssistedNewProject;
import javafx.application.Platform;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Studio's answers to the home endpoint's tools (2026-11-05, plan phase 9a): My bots as the bot list reads them, the
 * published bots and a new project as New Project makes one, opened on the FX thread, and what is on screen. The tools
 * ask this, never a window of Studio's.
 */
public final class HomeShell implements HomeEndpoint.Shell {

    private final BiConsumer<Path, Boolean> opens;
    private final AssistedNewProject newProject;

    /**
     * A shell over {@code main}, whose questions own the new project's dialogs; {@code opens} opens a folder in the
     * Runner when its flag is true, else in the editor, on the FX thread.
     */
    public HomeShell(Window main, BiConsumer<Path, Boolean> opens) {
        this.opens = opens;
        this.newProject = new AssistedNewProject(new PluginRegistry(), () -> main);
    }

    @Override
    public void open(Path dir, boolean run) {
        Platform.runLater(() -> opens.accept(dir, run));
    }

    @Override
    public List<Listed> bots() {
        return RunHome.myBots().stream().map(bot -> new Listed(bot.dir(), bot.name(), bot.owner(), bot.version(),
                bot.lastUsed(), bot.state() == MyBots.State.GONE, bot.unpacked(), bot.available())).toList();
    }

    @Override
    public String publishedBots() {
        return newProject.listBots();
    }

    @Override
    public String create(String name, String bot, List<String> plugins, Function<Path, String> opened) {
        return newProject.create(name, bot, plugins, opened::apply);
    }

    @Override
    public String busy() {
        return OnScreen.busy();
    }
}
