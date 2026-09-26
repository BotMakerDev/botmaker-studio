package com.botmaker.studio.ui.app;

import com.botmaker.studio.project.ProjectState.SourceFile;
import com.botmaker.studio.project.vcs.ProjectVcs;
import com.botmaker.studio.project.vcs.VcsFileStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** The explorer's model: folded package chains, the filter, what each file is, and what changed. */
class ExplorerModelTest {

    @Test
    void aPackageChainIsOneRowAndTheBotsOwnFoldersStay(@TempDir Path root) throws Exception {
        Path java = root.resolve("java");
        touch(java.resolve("com/mybot/MyBot.java"));
        touch(java.resolve("com/mybot/Parameters.java"));
        touch(java.resolve("com/mybot/plugins/sdk/Sdk.java"));
        touch(java.resolve("com/mybot/plugins/sdk/Pictures.java"));
        touch(java.resolve("com/mybot/helpers/Moves.java"));

        assertEquals(List.of(
                "com.mybot/",
                "  helpers/",
                "    Moves.java",
                "  plugins.sdk/",
                "    Pictures.java",
                "    Sdk.java",
                "  MyBot.java",
                "  Parameters.java"), lines(ExplorerModel.tree(java, p -> true)));
    }

    @Test
    void theFilterKeepsMatchingFilesAndOnlyTheFoldersLeadingToThem(@TempDir Path root) throws Exception {
        Path java = root.resolve("java");
        touch(java.resolve("com/mybot/MyBot.java"));
        touch(java.resolve("com/mybot/plugins/sdk/Sdk.java"));
        touch(java.resolve("com/mybot/plugins/sdk/Pictures.java"));

        assertEquals(List.of("com.mybot.plugins.sdk/", "  Pictures.java"),
                lines(ExplorerModel.tree(java, ExplorerModel.matching("pic"))),
                "a folder left with one folder folds again, so the match is one row down");
        assertTrue(ExplorerModel.tree(java, ExplorerModel.matching("zzz")).isEmpty());
        assertEquals(3, count(ExplorerModel.tree(java, ExplorerModel.matching("  "))), "blank keeps everything");
    }

    @Test
    void eachFileSaysWhatItIs() {
        Path pkg = Path.of("/p/src/main/java/com/mybot");
        Path main = pkg.resolve("MyBot.java");
        assertEquals(ExplorerModel.Kind.ENTRY_POINT, ExplorerModel.kindOf(main, "class MyBot {}", main));
        assertEquals(ExplorerModel.Kind.PARAMETERS,
                ExplorerModel.kindOf(pkg.resolve("Parameters.java"), "@Param(min = 1) static int x = 2;", main));
        assertEquals(ExplorerModel.Kind.PLUGIN_FILE,
                ExplorerModel.kindOf(pkg.resolve("plugins/sdk/Sdk.java"), "@Param int x;", main),
                "a plugin's file is that before it is anything else");
        assertEquals(ExplorerModel.Kind.JAVA, ExplorerModel.kindOf(pkg.resolve("Moves.java"), "class Moves {}", main));
        assertEquals(ExplorerModel.Kind.LIBRARY,
                ExplorerModel.kindOf(Path.of("/p/src/main/java/com/botmaker/library/Lib.java"), "", main));
        assertEquals(ExplorerModel.Kind.PICTURE, ExplorerModel.kindOf(Path.of("/r/images/ore.PNG"), null, main));
        assertEquals(ExplorerModel.Kind.DATA,
                ExplorerModel.kindOf(Path.of("/r/botmaker-project.properties"), null, main));
        assertEquals(ExplorerModel.Kind.OTHER, ExplorerModel.kindOf(Path.of("/r/notes"), null, main));
        assertFalse(ExplorerModel.isPluginFile(pkg.resolve("plugins/sdk/deep/Util.java")),
                "exactly one package below plugins, as HostPluginValues writes one");
    }

    @Test
    void anUnsavedEditCountsAsAChangeBeforeGitCanSeeIt(@TempDir Path root) throws Exception {
        Path saved = touch(root.resolve("src/A.java"));
        Path edited = touch(root.resolve("src/B.java"));
        Path fresh = touch(root.resolve("src/C.java"));
        ProjectVcs.FileStatus git = new ProjectVcs.FileStatus(new TreeSet<>(), new TreeSet<>(), new TreeSet<>(),
                new TreeSet<>(List.of("src/C.java")));

        Map<Path, VcsFileStatus> status = ExplorerModel.status(root, git, List.of(
                new SourceFile(saved, "class X {}\n"),
                new SourceFile(edited, "class B { int changed; }\n"),
                new SourceFile(fresh, "anything")));

        assertEquals(Map.of(edited, VcsFileStatus.MODIFIED, fresh, VcsFileStatus.NEW), status,
                "git's answer stands for C; A's buffer is what is on disk");
    }

    private static Path touch(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}\n");
        return file;
    }

    private static List<String> lines(ExplorerModel.Folder root) {
        List<String> out = new ArrayList<>();
        walk(root, 0, out);
        return out;
    }

    private static void walk(ExplorerModel.Folder folder, int depth, List<String> out) {
        for (ExplorerModel.Folder sub : folder.folders()) {
            out.add("  ".repeat(depth) + sub.label() + "/");
            walk(sub, depth + 1, out);
        }
        for (Path file : folder.files()) out.add("  ".repeat(depth) + file.getFileName());
    }

    private static int count(ExplorerModel.Folder folder) {
        return folder.files().size() + folder.folders().stream().mapToInt(ExplorerModelTest::count).sum();
    }
}
