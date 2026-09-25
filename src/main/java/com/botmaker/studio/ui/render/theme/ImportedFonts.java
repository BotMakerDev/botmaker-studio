package com.botmaker.studio.ui.render.theme;

import com.botmaker.shared.tools.UserDirs;
import javafx.scene.text.Font;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Font files the user brought: copied into {@code <config>/fonts/} by View ▸ Block Font ▸ More Fonts… ▸
 * Import, and registered with JavaFX at startup beside the bundled ones ({@link BlockFont#loadBundled}). A
 * copy, not a path to the original, so a font picked from Downloads still draws after the file is cleaned up.
 * Once registered an imported face is an ordinary installed family, saved as {@code family:<name>}.
 */
public final class ImportedFonts {

    private static final List<String> EXTENSIONS = List.of(".ttf", ".otf");

    private ImportedFonts() {}

    /** Where imported files live. */
    public static Path dir() {
        return UserDirs.config().resolve("fonts");
    }

    /** Whether {@code file} is a font file this can import, by its name. */
    public static boolean isFontFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * Copies {@code file} into {@link #dir()} and registers it; answers the font it draws. A file JavaFX will
     * not read is refused and its copy removed, so a bad import does not come back at every start.
     */
    public static BlockFont importFont(Path file) throws IOException {
        if (!isFontFile(file)) throw new IOException("Not a font file: pick a .ttf or .otf.");
        Files.createDirectories(dir());
        Path copy = dir().resolve(file.getFileName().toString());
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
        Font font = load(copy);
        if (font == null) {
            Files.deleteIfExists(copy);
            throw new IOException("JavaFX could not read " + file.getFileName() + " as a font.");
        }
        return new BlockFont(font.getFamily());
    }

    /** Registers every imported file. A file that will not load costs only itself. */
    static void loadAll() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(ImportedFonts::isFontFile).sorted().forEach(ImportedFonts::load);
        } catch (IOException e) {
            System.err.println("Could not list imported fonts in " + dir + ": " + e);
        }
    }

    private static Font load(Path file) {
        try {
            return Font.loadFont(file.toUri().toString(), 13);
        } catch (RuntimeException | LinkageError e) {
            System.err.println("Could not load imported font " + file + ": " + e);
            return null;
        }
    }
}
