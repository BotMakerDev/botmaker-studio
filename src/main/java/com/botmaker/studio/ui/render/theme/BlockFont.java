package com.botmaker.studio.ui.render.theme;

import javafx.scene.text.Font;

import java.io.InputStream;
import java.util.List;

/**
 * The typeface a canvas's blocks are written in: Nunito, which Studio ships, the platform's own, or any family
 * installed on this machine. A set that is open at one end — {@link #NUNITO} and {@link #SYSTEM} are the two
 * fixed answers, and an installed family is a third kind — so it is a record over a family name rather than
 * an enum.
 *
 * <p>Nunito because its round terminals and even weight read like a block language's; the canvas named
 * {@code "Segoe UI"} before, which Linux does not have, so every Linux user got whatever JavaFX fell back to.
 * It is bundled (OFL, {@code resources/fonts/OFL.txt}) in four weights. JavaFX files two of them as families of
 * their own — {@code "Nunito SemiBold"}, {@code "Nunito ExtraBold"} — which is why the heavier words on a block
 * name those families under {@code .font-nunito} in {@code blocks.css}, not a weight.
 *
 * @param family the family name, or {@code null} for the platform's default
 */
public record BlockFont(String family) {

    public static final BlockFont NUNITO = new BlockFont("Nunito");
    public static final BlockFont SYSTEM = new BlockFont(null);
    public static final BlockFont DEFAULT = NUNITO;

    private static final List<String> BUNDLED_FILES = List.of(
            "Nunito-Regular.ttf", "Nunito-SemiBold.ttf", "Nunito-Bold.ttf", "Nunito-ExtraBold.ttf");
    private static boolean loaded;

    /** The stable key a preference is saved under: {@code nunito}, {@code system}, or {@code family:<name>}. */
    public String id() {
        if (this.equals(NUNITO)) return "nunito";
        if (family == null) return "system";
        return "family:" + family;
    }

    /** What the View menu calls it. */
    public String displayName() {
        if (this.equals(NUNITO)) return "Nunito (bundled)";
        if (family == null) return "System";
        return family;
    }

    /** The class the canvas carries for this font — {@code font-nunito} selects Nunito's own weights. */
    public String styleClass() {
        return this.equals(NUNITO) ? "font-nunito" : "font-other";
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
        return List.of("font-nunito", "font-other");
    }

    /** The font saved as {@code id}; anything unreadable is {@link #DEFAULT}. Total. */
    public static BlockFont fromId(String id) {
        if (id == null || id.equals("nunito")) return DEFAULT;
        if (id.equals("system")) return SYSTEM;
        if (id.startsWith("family:") && id.length() > "family:".length()) {
            return new BlockFont(id.substring("family:".length()));
        }
        return DEFAULT;
    }

    /**
     * Registers the bundled Nunito files with JavaFX, once. Before this a {@code -fx-font-family: "Nunito"}
     * names a family JavaFX does not know and silently draws the default. A file that will not load costs
     * only itself.
     */
    public static synchronized void loadBundled() {
        if (loaded) return;
        loaded = true;
        for (String file : BUNDLED_FILES) {
            try (InputStream in = BlockFont.class.getResourceAsStream("/fonts/" + file)) {
                if (in != null) Font.loadFont(in, 13);
            } catch (Exception | LinkageError e) {
                System.err.println("Could not load bundled font " + file + ": " + e);
            }
        }
    }
}
