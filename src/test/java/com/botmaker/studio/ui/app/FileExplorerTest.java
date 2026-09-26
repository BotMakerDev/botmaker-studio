package com.botmaker.studio.ui.app;

import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.events.EventBus;
import com.botmaker.studio.index.TypeSummaryManager;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.studio.suggestions.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Project Files panel over a real project on disk: a folder tree with its package chain folded, the
 * resources beside the code, a filter that narrows both, and the open file's outline under them.
 */
class FileExplorerTest extends FxHeadlessTest {

    private static final String MAIN = """
            package com.mybot;
            public class MyBot {
                static int rounds = 3;
                public static void main(String[] args) {
                    play();
                }
                static void play() {}
            }
            """;

    @Override
    public void start(Stage stage) {}

    @Test
    void theTreeTheFilterAndTheStructure(@TempDir Path root) throws Exception {
        ProjectConfig config = ProjectConfig.forProject("MyBot", root);
        Path main = config.mainSourceFile();
        write(main, MAIN);
        write(config.mainPackageDir().resolve("plugins/sdk/Sdk.java"), "package com.mybot.plugins.sdk;\nclass Sdk {}\n");
        write(config.resourcesRoot().resolve("images/ore.png"), "not really a picture");

        EditorFixture fixture = new EditorFixture(MAIN, main);
        EventBus bus = new EventBus(false);
        StudioContext ctx = new StudioContext(config, fixture.state, bus, new DiagnosticsManager(),
                new BlockDragAndDropManager(bus), new ProjectAnalyzer(new TypeSummaryManager(Set.of()), fixture.state),
                new LibraryService(config, fixture.state, new TypeSummaryManager(Set.of()), bus),
                new ProjectSettingsService(config, fixture.state, bus), null, fixture.context(), null);
        AtomicReference<FileExplorerManager> explorer = new AtomicReference<>();
        interact(() -> {
            explorer.set(new FileExplorerManager(ctx));
            explorer.get().createView();
        });

        assertEquals(List.of(
                "My code",
                "  com.mybot",
                "    plugins.sdk",
                "      Sdk.java",
                "    MyBot.java",
                "Resources",
                "  images",
                "    ore.png"), explorer.get().rows());

        interact(() -> explorer.get().filter().setText("ore"));
        assertEquals(List.of("Resources", "  images", "    ore.png"), explorer.get().rows(),
                "a filter keeps the matching files and the folders leading to them, and hides an empty group");

        interact(() -> bus.publish(new CoreApplicationEvents.UIBlocksUpdatedEvent(null)));
        interact(() -> { });
        assertEquals(List.of("MyBot", "rounds", "main", "play"), explorer.get().structureNames());
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
