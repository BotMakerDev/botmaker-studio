package com.botmaker.studio.services.upgrade;

import com.botmaker.studio.project.PluginFiles;
import com.botmaker.studio.project.ProjectConfig;
import com.botmaker.studio.project.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A removed plugin's files, the lines that name them, and what deleting them leaves in the user's code. */
class PluginHoldersTest {

    private static final List<PluginFiles.Holder> SDK = List.of(
            new PluginFiles.Holder("com.botmaker.sdk", "SDK", "sdk", "Sdk"),
            new PluginFiles.Holder("com.botmaker.sdk", "SDK", "sdk", "Pictures"));

    @TempDir
    Path root;

    @Test
    void theHoldersAreFoundWithTheLinesThatNameThem() throws Exception {
        ProjectConfig config = project();

        PluginHolders holders = PluginHolders.of(config, new ProjectState(), SDK);

        assertEquals("Pictures.java, Sdk.java", holders.fileNames());
        assertEquals(List.of("Base.java:8"), holders.references(), "the import goes by itself");
    }

    @Test
    void deletingThemTakesTheirImportAndMarksTheFunctionThatStillNamesOne() throws Exception {
        ProjectConfig config = project();
        PluginHolders holders = PluginHolders.of(config, new ProjectState(), SDK);

        PluginHolders.Deleted deleted = holders.delete(config, new ProjectState(), "SDK");

        Path folder = config.mainPackageDir().resolve("plugins/sdk");
        assertFalse(Files.exists(folder), "the empty folder goes with its files");
        String base = Files.readString(config.mainPackageDir().resolve("Base.java"));
        assertFalse(base.contains("import " + config.mainPackage() + ".plugins.sdk.Sdk;"), base);
        assertTrue(base.contains("@Refactor(\"Sdk went with the SDK plugin"), base);
        assertEquals(1, deleted.rewritten());
        assertEquals(List.of("Base.java · main() still names Sdk."), deleted.left());
    }

    @Test
    void aStaticOrWildcardImportGoesAndAUseOutsideAFunctionIsReported() throws Exception {
        ProjectConfig config = project();
        String pkg = config.mainPackage();
        Files.writeString(config.mainPackageDir().resolve("Other.java"), """
                package %s;

                import static %s.plugins.sdk.Sdk.flow;
                import %s.plugins.sdk.*;

                public class Other {
                    static final Object PICTURES = new Pictures();
                }
                """.formatted(pkg, pkg, pkg));
        PluginHolders holders = PluginHolders.of(config, new ProjectState(), SDK);

        PluginHolders.Deleted deleted = holders.delete(config, new ProjectState(), "SDK");

        String other = Files.readString(config.mainPackageDir().resolve("Other.java"));
        assertFalse(other.contains("import"), other);
        assertTrue(deleted.left().contains("Other.java:7 names Pictures outside a function; change it by hand."),
                deleted.left().toString());
        assertTrue(deleted.left().stream().anyMatch(l -> l.startsWith("Other.java imported " + pkg
                + ".plugins.sdk.Sdk.flow statically")), deleted.left().toString());
    }

    @Test
    void aFileThatNoLoadedPluginDeclaresIsNotOffered() throws Exception {
        ProjectConfig config = project();

        PluginHolders holders = PluginHolders.of(config, new ProjectState(),
                List.of(new PluginFiles.Holder("com.other.basics", "Basics", "basics", "Basics")));

        assertTrue(holders.isEmpty());
    }

    private ProjectConfig project() throws Exception {
        ProjectConfig config = ProjectConfig.forProject("Base", root);
        Path main = config.mainPackageDir();
        Path sdk = main.resolve("plugins/sdk");
        Files.createDirectories(sdk);
        String pkg = config.mainPackage();
        Files.writeString(sdk.resolve("Sdk.java"), """
                package %s.plugins.sdk;

                public final class Sdk {
                    public static String flow() { return ""; }
                }
                """.formatted(pkg));
        Files.writeString(sdk.resolve("Pictures.java"), """
                package %s.plugins.sdk;

                public final class Pictures {}
                """.formatted(pkg));
        Files.writeString(main.resolve("Base.java"), """
                package %s;

                import %s.plugins.sdk.Sdk;

                public class Base {

                    public static void main(String[] args) {
                        System.out.println(Sdk.flow());
                    }
                }
                """.formatted(pkg, pkg));
        return config;
    }
}
