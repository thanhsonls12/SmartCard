package vn.labaccess.desktop.ui.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import vn.labaccess.desktop.card.PhotoImage;

public final class PhotoDraft {

    private static final int WHITE = 0xFF;

    private PhotoDraft() {
    }

    public static byte[] fromFile(Path file) throws IOException {
        BufferedImage source = ImageIO.read(file.toFile());
        if (source == null) {
            throw new IOException("không đọc được ảnh: " + file.getFileName());
        }
        return fromImage(source);
    }

    public static byte[] fromImage(BufferedImage source) {
        BufferedImage canvas = new BufferedImage(
                PhotoImage.WIDTH, PhotoImage.HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setColor(new Color(WHITE, WHITE, WHITE));
            graphics.fillRect(0, 0, PhotoImage.WIDTH, PhotoImage.HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            double scale = Math.max(
                    (double) PhotoImage.WIDTH / source.getWidth(),
                    (double) PhotoImage.HEIGHT / source.getHeight());
            int width = (int) Math.ceil(source.getWidth() * scale);
            int height = (int) Math.ceil(source.getHeight() * scale);
            int x = Math.floorDiv(PhotoImage.WIDTH - width, 2);
            int y = Math.floorDiv(PhotoImage.HEIGHT - height, 2);
            graphics.drawImage(source, x, y, width, height, null);
        } finally {
            graphics.dispose();
        }
        byte[] gray = new byte[PhotoImage.LENGTH];
        for (int row = 0; row < PhotoImage.HEIGHT; row++) {
            for (int column = 0; column < PhotoImage.WIDTH; column++) {
                gray[row * PhotoImage.WIDTH + column] =
                        (byte) luminance(canvas.getRGB(column, row));
            }
        }
        return gray;
    }

    private static int luminance(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        return (299 * red + 587 * green + 114 * blue + 500) / 1000;
    }
}
