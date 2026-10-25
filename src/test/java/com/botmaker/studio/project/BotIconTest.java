package com.botmaker.studio.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot's icon file and the letter shown without one (2026-10-25, plan phase 4). */
class BotIconTest {

    @TempDir
    Path dir;

    @Test
    void writesA256Png() throws Exception {
        BufferedImage wide = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 600; x++) {
            for (int y = 0; y < 400; y++) wide.setRGB(x, y, x < 100 || x >= 500 ? 0xFF0000 : 0x00FF00);
        }
        BotIcon.write(dir, wide);

        Path file = BotIcon.of(dir).orElseThrow();
        byte[] head = Files.readAllBytes(file);
        assertEquals((byte) 0x89, head[0]);
        assertEquals('P', head[1]);
        BufferedImage icon = ImageIO.read(file.toFile());
        assertEquals(256, icon.getWidth());
        assertEquals(256, icon.getHeight());
        assertEquals(0x00FF00, icon.getRGB(2, 128) & 0xFFFFFF, "the centre square: the red sides are cut off");
        assertFalse(Files.exists(dir.resolve("icon.png.next")));
    }

    @Test
    void noFileIsLetter() throws Exception {
        assertTrue(BotIcon.of(dir).isEmpty());
        BotIcon.write(dir, new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB));
        BotIcon.remove(dir);
        assertTrue(BotIcon.of(dir).isEmpty());
    }

    @Test
    void letterIsStable() {
        BufferedImage a = BotIcon.letter("Firestone", 64);
        BufferedImage b = BotIcon.letter("Firestone", 64);
        assertEquals(64, a.getWidth());
        assertEquals(a.getRGB(4, 4), b.getRGB(4, 4));
        assertEquals(a.getRGB(32, 32), b.getRGB(32, 32));
        assertNotEquals(BotIcon.colourOf("Firestone"), BotIcon.colourOf("PushTest"));
        assertEquals(64, BotIcon.letter("", 64).getHeight(), "a blank name draws ?");
    }
}
