package com.botmaker.studio.ui.render.theme;

import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The typeface a canvas's blocks are written in: one Studio ships ({@link Bundled}), the platform's own, or any
 * family installed on this machine (or imported, {@link ImportedFonts}). A set that is open at one end — the
 * bundled faces and {@link #SYSTEM} are fixed answers, and an installed family is a third kind — so it is a
 * record over a family name rather than an enum.
 *
 * <p>Nunito is the default because its round terminals and even weight read like a block language's; the
 * canvas named {@code "Segoe UI"} before, which Linux does not have, so every Linux user got whatever JavaFX
 * fell back to. Every bundled face is OFL ({@code resources/fonts/OFL*.txt}) and ships in static cuts, never a
 * variable file: JavaFX draws a variable font at its default instance only. JavaFX files a cut that is neither
 * regular nor bold as a family of its own — {@code "Nunito SemiBold"}, {@code "Lexend SemiBold"} — which is why
 * the heavier words on a block name those families under each face's class in {@code blocks.css}, not a weight.
 *
 * @param family the family name, or {@code null} for the platform's default
 */
public record BlockFont(String family) {

    /** A face Studio ships: its files, and the family JavaFX files its label cut under. */
    public enum Bundled {
        NUNITO("nunito", "Nunito", "Nunito SemiBold", "Round and friendly",
                "Nunito-Regular.ttf", "Nunito-SemiBold.ttf", "Nunito-Bold.ttf", "Nunito-ExtraBold.ttf"),
        LEXEND("lexend", "Lexend", "Lexend SemiBold", "Wide, made for reading ease",
                "Lexend-Regular.ttf", "Lexend-SemiBold.ttf", "Lexend-Bold.ttf"),
        ATKINSON("atkinson", "Atkinson Hyperlegible Next", "Atkinson Hyperlegible Next SemiBold",
                "Every letter unmistakable",
                "AtkinsonHyperlegibleNext-Regular.ttf", "AtkinsonHyperlegibleNext-SemiBold.ttf",
                "AtkinsonHyperlegibleNext-Bold.ttf"),
        FREDOKA("fredoka", "Fredoka", "Fredoka Medium", "Soft and playful",
                "Fredoka-Regular.ttf", "Fredoka-Medium.ttf", "Fredoka-Bold.ttf"),
        SPACE_GROTESK("space-grotesk", "Space Grotesk", "Space Grotesk Medium", "Crisp, a little technical",
                "SpaceGrotesk-Regular.ttf", "SpaceGrotesk-Medium.ttf", "SpaceGrotesk-Bold.ttf");

        private final String id;
        private final String family;
        private final String labelFamily;
        private final String note;
        private final List<String> files;

        Bundled(String id, String family, String labelFamily, String note, String... files) {
            this.id = id;
            this.family = family;
            this.labelFamily = labelFamily;
            this.note = note;
            this.files = List.of(files);
        }

        public String id() {
            return id;
        }

        public String family() {
            return family;
        }

        /** The family a block's connecting words ({@code .bc-label}) are drawn in. */
        public String labelFamily() {
            return labelFamily;
        }

        /** One line on what the face is like, for the font dialog. */
        public String note() {
            return note;
        }

        public List<String> files() {
            return files;
        }

        public BlockFont font() {
            return new BlockFont(family);
        }

        /** The class the canvas carries for this face: it selects the face's own heavier cuts in the CSS. */
        public String styleClass() {
            return "font-" + id;
        }
    }

    public static final BlockFont NUNITO = Bundled.NUNITO.font();
    public static final BlockFont SYSTEM = new BlockFont(null);
    public static final BlockFont DEFAULT = NUNITO;

    /** The monospaced face Studio ships for the Run console and the terminals. */
    public static final String MONO_FAMILY = "JetBrains Mono";
    private static final List<String> MONO_FILES = List.of("JetBrainsMono-Regular.ttf", "JetBrainsMono-Bold.ttf");

    private static final String OTHER_CLASS = "font-other";
    private static boolean loaded;

    /** Every face Studio ships, as fonts, default first. */
    public static List<BlockFont> bundled() {
        return Arrays.stream(Bundled.values()).map(Bundled::font).toList();
    }

    /** The bundled face this is, if it is one. */
    public Optional<Bundled> asBundled() {
        if (family == null) return Optional.empty();
        for (Bundled b : Bundled.values()) {
            if (b.family.equals(family)) return Optional.of(b);
        }
        return Optional.empty();
    }

    /** The stable key a preference is saved under: a bundled face's id, {@code system}, or {@code family:<name>}. */
    public String id() {
        if (family == null) return "system";
        return asBundled().map(Bundled::id).orElse("family:" + family);
    }

    /** What the View menu calls it. */
    public String displayName() {
        if (family == null) return "System";
        return asBundled().map(b -> b.family + " (bundled)").orElse(family);
    }

    /** The class the canvas carries for this font — a bundled face's selects its own weights. */
    public String styleClass() {
        return asBundled().map(Bundled::styleClass).orElse(OTHER_CLASS);
    }

    /** The inline style the canvas root carries: the family and the base size, or the size alone for System. */
    public String canvasStyle() {
        String size = "-fx-font-size: 13px;";
        return family == null ? size : "-fx-font-family: \"" + family.replace("\"", "") + "\"; " + size;
    }

    /**
     * Writes {@code canvas} in this font: the bundled files registered, the class swapped, the family and size
     * inline. The one way a canvas takes a font, so the editor and the tests that measure it draw the same.
     */
    public void applyTo(javafx.scene.Node canvas) {
        loadBundled();
        canvas.getStyleClass().removeAll(styleClasses());
        canvas.getStyleClass().add(styleClass());
        canvas.setStyle(canvasStyle());
    }

    /** Every class {@link #styleClass()} can answer, for swapping one for another. */
    public static List<String> styleClasses() {
        List<String> classes = new ArrayList<>();
        for (Bundled b : Bundled.values()) classes.add(b.styleClass());
        classes.add(OTHER_CLASS);
        return classes;
    }

    /** The font saved as {@code id}; anything unreadable is {@link #DEFAULT}. Total. */
    public static BlockFont fromId(String id) {
        if (id == null) return DEFAULT;
        if (id.equals("system")) return SYSTEM;
        for (Bundled b : Bundled.values()) {
            if (b.id.equals(id)) return b.font();
        }
        if (id.startsWith("family:") && id.length() > "family:".length()) {
            return new BlockFont(id.substring("family:".length()));
        }
        return DEFAULT;
    }

    /**
     * Registers every bundled file with JavaFX, then the fonts the user imported, once. Before this a
     * {@code -fx-font-family: "Lexend"} names a family JavaFX does not know and silently draws the default. A
     * file that will not load costs only itself.
     */
    public static synchronized void loadBundled() {
        if (loaded) return;
        loaded = true;
        List<String> files = new ArrayList<>(MONO_FILES);
        for (Bundled b : Bundled.values()) files.addAll(b.files);
        for (String file : files) {
            try (InputStream in = BlockFont.class.getResourceAsStream("/fonts/" + file)) {
                if (in != null) Font.loadFont(in, 13);
            } catch (Exception | LinkageError e) {
                System.err.println("Could not load bundled font " + file + ": " + e);
            }
        }
        ImportedFonts.loadAll();
    }
}
