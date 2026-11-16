// SPDX-FileCopyrightText: 2026 LiQiyeDev
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package com.botmaker.studio.ui.app;

import com.botmaker.core.nav.SourceNavigation;
import com.botmaker.core.project.ProjectFile;
import com.botmaker.java.index.nav.StructureKind;
import com.botmaker.studio.core.CodeBlock;
import com.botmaker.studio.events.CoreApplicationEvents;
import com.botmaker.studio.nav.BlockAt;
import com.botmaker.studio.parser.BlockReuse;
import com.botmaker.core.events.EventBus;
import com.botmaker.core.index.TypeSummaryManager;
import com.botmaker.studio.parser.EditorFixture;
import com.botmaker.core.project.ProjectConfig;
import com.botmaker.studio.project.StudioContext;
import com.botmaker.studio.services.ClassFiles;
import com.botmaker.studio.services.LibraryService;
import com.botmaker.studio.services.ProjectSettingsService;
import com.botmaker.java.types.ProjectAnalyzer;
import com.botmaker.studio.ui.dnd.BlockDragAndDropManager;
import com.botmaker.studio.ui.fx.FxHeadlessTest;
import com.botmaker.studio.validation.DiagnosticsManager;
import javafx.stage.Stage;
import org.eclipse.jdt.core.dom.ASTNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
        Path helper = config.mainPackageDir().resolve("Helper.java");
        write(helper, HELPER);
        write(config.mainPackageDir().resolve("plugins/sdk/Sdk.java"), "package com.mybot.plugins.sdk;\nclass Sdk {}\n");
        write(config.parametersSourceFile(), "package com.mybot.params;\npublic final class Parameters {}\n");
        write(config.resourcesRoot().resolve("images/ore.png"), "not really a picture");

        EditorFixture fixture = new EditorFixture(MAIN, main);
        EventBus bus = new EventBus(false);
        StudioContext ctx = new StudioContext(config, fixture.state, bus, new DiagnosticsManager(),
                new BlockDragAndDropManager(bus), new ProjectAnalyzer(new TypeSummaryManager(Set.of()), fixture.state),
                new LibraryService(config, fixture.state, new TypeSummaryManager(Set.of()), bus),
                new ProjectSettingsService(config, fixture.state, bus), fixture.context(), null);
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
                "    Helper.java",
                "    MyBot.java",
                "      " + member("rounds"),
                "      " + member("main"),
                "      " + member("play"),
                "Resources",
                "  images",
                "    ore.png"), explorer.get().rows());

        ClassFiles files = new ClassFiles(ctx);
        assertTrue(files.lockedProblem(config.parametersSourceFile()).isPresent(),
                "the Parameters window finds its file by path: neither renamed nor deleted");
        assertTrue(files.lockedProblem(main).isEmpty());

        interact(() -> explorer.get().filter().setText("ore"));
        assertEquals(List.of("Resources", "  images", "    ore.png"), explorer.get().rows(),
                "a filter keeps the matching files and the folders leading to them, and hides an empty group");

        interact(() -> explorer.get().filter().setText(""));
        List<CodeBlock> revealed = new ArrayList<>();
        AtomicInteger canvas = new AtomicInteger();
        interact(() -> {
            explorer.get().setOnReveal(revealed::add);
            explorer.get().setOnOpen(canvas::incrementAndGet, p -> { });
            explorer.get().clickMember("play");
        });
        assertEquals(1, revealed.size(), "a member's click lands on its block");
        assertSame(BlockAt.blockFor(memberNode(fixture, "play"), fixture.state.getNodeToBlockMap()).orElseThrow(),
                revealed.getFirst());
        assertEquals(1, canvas.get(), "with the canvas in front");

        // Another file opened: its row takes the members, and the last one's row has none.
        fixture.state.addFile(new ProjectFile(helper, HELPER));
        interact(() -> {
            fixture.state.setActiveFile(helper);
            fixture.reparse(HELPER, BlockReuse.NONE);
            bus.publish(new CoreApplicationEvents.UIBlocksUpdatedEvent(null));
        });
        List<String> rows = explorer.get().rows();
        int at = rows.indexOf("    Helper.java");
        assertEquals("      " + member("help"), rows.get(at + 1));
        assertEquals("    MyBot.java", rows.get(at + 2));
        assertEquals("Resources", rows.get(at + 3));
    }

    private static final String HELPER = """
            package com.mybot;
            public class Helper {
                static void help() {}
            }
            """;

    /** A member row's label, as the tree shows it. */
    private static String member(String name) {
        return switch (name) {
            case "rounds" -> StructureKind.FIELD.glyph() + "  rounds: int";
            case "main" -> StructureKind.METHOD.glyph() + "  main(String[]): void";
            case "play" -> StructureKind.METHOD.glyph() + "  play(): void";
            case "help" -> StructureKind.METHOD.glyph() + "  help(): void";
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static ASTNode memberNode(EditorFixture fixture, String name) {
        return SourceNavigation.structure(fixture.state.getCompilationUnit().orElseThrow()).stream()
                .filter(e -> e.name().equals(name)).findFirst().orElseThrow().node();
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }
}
