package de.moritzf.airplaycapture.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CoverHashTest {

    @TempDir
    Path temp;

    @Test
    void sameCoverAtDifferentSizesKeepsTheHash() throws Exception {
        BufferedImage original = pattern(64, 64);
        BufferedImage scaled = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.drawImage(original, 0, 0, 256, 256, null);
        graphics.dispose();

        Path first = temp.resolve("first.png");
        Path second = temp.resolve("second.png");
        ImageIO.write(original, "png", first.toFile());
        ImageIO.write(scaled, "png", second.toFile());

        assertEquals(CoverHash.of(first), CoverHash.of(second));
    }

    @Test
    void differentCoversGetDifferentHashes() {
        assertNotEquals(CoverHash.of(pattern(64, 64)), CoverHash.of(inverted(64, 64)));
    }

    private static BufferedImage pattern(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = (x * 3 + y * 5) % 256;
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }

    private static BufferedImage inverted(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = 255 - ((x * 3 + y * 5) % 256);
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        return image;
    }
}
