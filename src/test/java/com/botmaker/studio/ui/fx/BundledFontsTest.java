package com.botmaker.studio.ui.fx;

import com.botmaker.shared.tools.UserDirs;
import com.botmaker.studio.ui.render.theme.BlockFont;
import com.botmaker.studio.ui.render.theme.ImportedFonts;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The faces Studio ships: every cut registers under the family the stylesheet names, a block's words take the
 * face's own cuts through {@code blocks.css}, and an imported file is copied, registered and survives as an
 * ordinary family.
 */
class BundledFontsTest extends FxHeadlessTest {

    @Override
    public void start(Stage stage) {
        // Nothing to show: each test builds its own scene.
    }

    @Test
    void everyFontRoundTripsThroughItsId() {
        for (BlockFont font : BlockFont.bundled()) {
            assertEquals(font, BlockFont.fromId(font.id()), font.id());
            assertTrue(font.asBundled().isPresent(), font.id());
        }
        assertEquals(BlockFont.NUNITO, BlockFont.fromId("nunito"), "the id saved before this release still reads");
        assertEquals(BlockFont.Bundled.LEXEND.font(), new BlockFont("Lexend"),
                "an installed Lexend and the bundled one are one font");
        assertEquals("family:DejaVu Sans", new BlockFont("DejaVu Sans").id());
        assertEquals("font-other", new BlockFont("DejaVu Sans").styleClass());
    }

    @Test
    void everyBundledCutRegistersUnderTheFamilyTheStylesheetNames() {
        interact(BlockFont::loadBundled);
        var families = Font.getFamilies();
        for (BlockFont.Bundled face : BlockFont.Bundled.values()) {
            assertTrue(families.contains(face.family()), face.family() + " is registered");
            assertTrue(families.contains(face.labelFamily()), face.labelFamily() + " is registered");
            Font bold = Font.font(face.family(), FontWeight.BOLD, 13);
            assertEquals(face.family(), bold.getFamily(), face + " bold stays in its family");
            assertTrue(bold.getName().contains("Bold"), face + " has a bold cut: " + bold.getName());
        }
        assertTrue(families.contains(BlockFont.MONO_FAMILY), "the console's face is registered");
    }

    @Test
    void aBlocksWordsTakeTheFacesOwnCuts() {
        String css = getClass().getResource("/css/blocks.css").toExternalForm();
        for (BlockFont.Bundled face : BlockFont.Bundled.values()) {
            AtomicReference<Font> label = new AtomicReference<>();
            AtomicReference<Font> keyword = new AtomicReference<>();
            interact(() -> {
                Label connecting = new Label("times");
                connecting.getStyleClass().add("bc-label");
                Label kw = new Label("repeat");
                kw.getStyleClass().add("keyword-label");
                HBox canvas = new HBox(kw, connecting);
                canvas.getStyleClass().add("blocks-canvas");
                face.font().applyTo(canvas);
                Scene scene = new Scene(canvas, 300, 60);
                scene.getStylesheets().add(css);
                canvas.applyCss();
                label.set(connecting.getFont());
                keyword.set(kw.getFont());
            });
            assertEquals(face.labelFamily(), label.get().getFamily(), face + " connecting words");
            assertTrue(keyword.get().getName().contains("Bold"), face + " keyword: " + keyword.get().getName());
        }
    }

    @Test
    void anImportedFontIsCopiedRegisteredAndNamedByItsFamily(@TempDir Path dir) throws IOException {
        String before = System.getProperty(UserDirs.CONFIG_PROPERTY);
        System.setProperty(UserDirs.CONFIG_PROPERTY, dir.resolve("config").toString());
        try {
            Path file = dir.resolve("Picked.ttf");
            try (InputStream in = BlockFont.class.getResourceAsStream("/fonts/Fredoka-Medium.ttf")) {
                Files.copy(in, file);
            }
            AtomicReference<BlockFont> imported = new AtomicReference<>();
            interact(() -> {
                try {
                    imported.set(ImportedFonts.importFont(file));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            });
            assertTrue(imported.get().family().startsWith("Fredoka"), imported.get().family());
            assertTrue(Files.exists(ImportedFonts.dir().resolve("Picked.ttf")), "a copy, not the original's path");
            assertTrue(imported.get().id().startsWith("family:") || imported.get().asBundled().isPresent());

            Path text = Files.writeString(dir.resolve("notes.txt"), "not a font");
            assertThrows(IOException.class, () -> ImportedFonts.importFont(text));

            Path broken = Files.writeString(dir.resolve("Broken.ttf"), "not a font either");
            AtomicReference<Throwable> refused = new AtomicReference<>();
            interact(() -> {
                try {
                    ImportedFonts.importFont(broken);
                } catch (IOException e) {
                    refused.set(e);
                }
            });
            assertTrue(refused.get() instanceof IOException, "an unreadable file is refused");
            assertFalse(Files.exists(ImportedFonts.dir().resolve("Broken.ttf")), "and its copy removed");
        } finally {
            if (before == null) System.clearProperty(UserDirs.CONFIG_PROPERTY);
            else System.setProperty(UserDirs.CONFIG_PROPERTY, before);
        }
    }
}
