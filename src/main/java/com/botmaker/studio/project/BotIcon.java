package com.botmaker.studio.project;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * A bot's icon (2026-10-25, spec § 4): {@code icon.png} at the project's root, a square PNG Studio writes at
 * {@value #SIZE}×{@value #SIZE}. The catalog and the website read it raw from GitHub at the pinned commit. A bot
 * without one shows {@link #letter}: its first letter on a square coloured from its name, drawn, never written.
 */
public final class BotIcon {

    /** The file's name, at the project's root. */
    public static final String FILE = "icon.png";

    /** The side Studio writes the icon at. */
    public static final int SIZE = 256;

    private BotIcon() {}

    /** {@code projectDir}'s icon file, when it has one. */
    public static Optional<Path> of(Path projectDir) {
        Path file = projectDir.resolve(FILE);
        return Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /** Where GitHub serves the icon of {@code owner/repo} at {@code commit}; a 404 when the bot has none. */
    public static String rawUrl(String owner, String repo, String commit) {
        return "https://raw.githubusercontent.com/" + owner + "/" + repo + "/" + commit + "/" + FILE;
    }

    /**
     * Writes {@code image} as {@code projectDir}'s icon: its centre square, scaled to {@value #SIZE}×{@value #SIZE}.
     * The old icon is replaced in one move, so a failed write leaves it as it was.
     */
    public static void write(Path projectDir, BufferedImage image) throws IOException {
        int side = Math.min(image.getWidth(), image.getHeight());
        if (side <= 0) throw new IOException("The picture is empty.");
        BufferedImage square = image.getSubimage((image.getWidth() - side) / 2, (image.getHeight() - side) / 2,
                side, side);
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(square, 0, 0, SIZE, SIZE, null);
        } finally {
            g.dispose();
        }
        // A fresh name, made new: a cloned bot may ship a symlink under any fixed name.
        Path next = Files.createTempFile(projectDir, ".icon", ".next");
        try {
            try (OutputStream stream = Files.newOutputStream(next)) {
                if (!ImageIO.write(out, "png", stream)) throw new IOException("No PNG writer.");
            }
            Files.move(next, projectDir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(next);   // never left beside the bot, where a publish would commit it
        }
    }

    /** Removes {@code projectDir}'s icon; the letter shows again. */
    public static void remove(Path projectDir) throws IOException {
        Files.deleteIfExists(projectDir.resolve(FILE));
    }

    /**
     * The icon of a bot that has none: {@code name}'s first letter, white on a square whose hue comes from the
     * name, so a bot keeps its colour everywhere it shows. {@code size} pixels a side.
     */
    public static BufferedImage letter(String name, int size) {
        String n = name == null ? "" : name.strip();
        String initial = n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(colourOf(n));
            int arc = Math.max(4, size / 6);
            g.fillRoundRect(0, 0, size, size, arc, arc);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(8, size * 11 / 20)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(initial, (size - fm.stringWidth(initial)) / 2, (size - fm.getHeight()) / 2 + fm.getAscent());
        } finally {
            g.dispose();
        }
        return out;
    }

    /** One hue per name, mid saturation and brightness, so white reads on it in either theme. */
    static Color colourOf(String name) {
        float hue = Math.floorMod(name.hashCode(), 360) / 360f;
        return Color.getHSBColor(hue, 0.55f, 0.62f);
    }
}
