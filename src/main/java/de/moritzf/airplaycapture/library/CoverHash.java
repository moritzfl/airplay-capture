package de.moritzf.airplaycapture.library;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Perceptual difference hash (dHash) for cover art. The same cover at a different size or
 * compression keeps the same hash, while different covers normally differ in several bits. Used to
 * keep albums/books with the same title but different artwork apart.
 */
public final class CoverHash {

    private CoverHash() {}

    public static String of(Path file) {
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            return image == null ? "" : of(image);
        } catch (IOException e) {
            return "";
        }
    }

    static String of(BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
            return "";
        }
        BufferedImage small = new BufferedImage(9, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(image, 0, 0, 9, 8, null);
        } finally {
            graphics.dispose();
        }
        long hash = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray(small.getRGB(x, y));
                int right = gray(small.getRGB(x + 1, y));
                hash = (hash << 1) | (left > right ? 1 : 0);
            }
        }
        return String.format(Locale.ROOT, "%016x", hash) + colorGrid(image);
    }

    /** 2x2 average colour grid, so covers with the same luminance layout but different colours differ. */
    private static String colorGrid(BufferedImage image) {
        BufferedImage small = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = small.createGraphics();
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics.drawImage(image, 0, 0, 2, 2, null);
        } finally {
            graphics.dispose();
        }
        StringBuilder colors = new StringBuilder(24);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int rgb = small.getRGB(x, y);
                colors.append(
                    String.format(
                        Locale.ROOT,
                        "%02x%02x%02x",
                        (rgb >> 16) & 0xff,
                        (rgb >> 8) & 0xff,
                        rgb & 0xff
                    )
                );
            }
        }
        return colors.toString();
    }

    private static int gray(int rgb) {
        int red = (rgb >> 16) & 0xff;
        int green = (rgb >> 8) & 0xff;
        int blue = rgb & 0xff;
        return (red * 299 + green * 587 + blue * 114) / 1000;
    }
}
