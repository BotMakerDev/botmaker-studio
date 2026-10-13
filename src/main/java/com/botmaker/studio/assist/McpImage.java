package com.botmaker.studio.assist;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * A plugin's image as the MCP client receives it. A plugin answers with a PNG; a large one is a game frame,
 * which PNG stores at several times JPEG's size for no detail the assistant reads, so it goes out as a JPEG when
 * that is smaller. A small one (most pictures and crops) and one with transparency stay PNG, exact; the rule is
 * the size alone, so a large crop or a grid at full scale goes as a JPEG too.
 */
record McpImage(byte[] bytes, String mimeType) {

    /** Below this a PNG is a picture or a crop, kept exact. */
    static final int JPEG_FROM_BYTES = 32 * 1024;
    static final float JPEG_QUALITY = 0.85f;

    static McpImage of(byte[] png) {
        McpImage asPng = new McpImage(png, "image/png");
        if (png.length < JPEG_FROM_BYTES) return asPng;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getColorModel().hasAlpha()) return asPng;
            byte[] jpeg = jpeg(image);
            return jpeg.length < png.length ? new McpImage(jpeg, "image/jpeg") : asPng;
        } catch (IOException | RuntimeException e) {
            // No JPEG writer in this runtime, or a PNG that does not decode: the plugin's own bytes still go.
            return asPng;
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
