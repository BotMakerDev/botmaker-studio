package com.botmaker.studio.assist;

import com.botmaker.core.assist.ToolGroup;
import com.botmaker.core.assist.McpTools;
import com.botmaker.plugin.api.assist.AssistantTool;
import javafx.application.Platform;
import javafx.stage.Window;

import java.util.List;
import java.util.Set;

/**
 * Studio's {@link ToolGroup}: {@code ui} in a dev Studio Claude drives ({@link UiTools#enabled}), never in the installed
 * app, {@code quit_studio} on the home, and the questions Studio has on screen ({@link OnScreen}).
 */
public final class StudioToolGroup implements ToolGroup {

    public static final StudioToolGroup INSTANCE = new StudioToolGroup();

    private StudioToolGroup() {}

    /** Studio's main window, which {@code quit_studio} closes. */
    public static void mainWindow(Window window) {
        UiTools.mainWindow(window);
    }

    @Override
    public List<AssistantTool<?>> tools() {
        return driven() ? List.of(new UiTools().tool()) : List.of();
    }

    @Override
    public boolean driven() {
        return UiTools.enabled();
    }

    @Override
    public List<AssistantTool<?>> homeTools() {
        return List.of(McpTools.quitStudio(UiTools::quit));
    }

    @Override
    public Set<String> answering() {
        return Set.of(UiTools.NAME);
    }

    @Override
    public String busy() {
        return OnScreen.busy();
    }

    @Override
    public boolean asking() {
        return OnScreen.asking();
    }

    @Override
    public List<String> news() {
        return OnScreen.news();
    }

    /** A {@code ui} press reads the windows on the FX thread, so on it the wait goes off it. */
    @Override
    public void whenQuiet(Runnable then) {
        if (!UiTools.pressing()) {
            then.run();
        } else if (Platform.isFxApplicationThread()) {
            Thread.ofVirtual().start(() -> {
                UiTools.awaitPresses();
                then.run();
            });
        } else {
            UiTools.awaitPresses();
            then.run();
        }
    }
}
