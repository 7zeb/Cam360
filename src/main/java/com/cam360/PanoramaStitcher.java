package com.cam360;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

public final class PanoramaStitcher {
    private PanoramaStitcher() {}

    /**
     * Very simple stitch:
     * - 8 horizon shots are concatenated left->right
     * - top and bottom appended as strips
     * This is not true equirectangular reprojection yet, but gives one combined image.
     */
    public static File stitchSimple(
            List<File> shotFiles,
            File outputDir,
            long sessionId
    ) throws Exception {
        if (shotFiles.size() < 10) {
            throw new IllegalArgumentException("Need 10 shots (8 horizon + top + bottom).");
        }

        BufferedImage first = ImageIO.read(shotFiles.get(0));
        if (first == null) throw new IllegalStateException("Failed to read first screenshot.");

        int w = first.getWidth();
        int h = first.getHeight();

        int panoW = w * 8;
        int panoH = h + (h / 2) + (h / 2); // top strip + horizon + bottom strip
        BufferedImage out = new BufferedImage(panoW, panoH, BufferedImage.TYPE_INT_ARGB);

        // Top strip from shot index 8
        BufferedImage top = ImageIO.read(shotFiles.get(8));
        // Bottom strip from shot index 9
        BufferedImage bottom = ImageIO.read(shotFiles.get(9));

        // Draw top strip stretched across width
        for (int x = 0; x < panoW; x++) {
            int sx = (int)((x / (double)panoW) * top.getWidth());
            for (int y = 0; y < h / 2; y++) {
                int sy = (int)((y / (double)(h / 2)) * top.getHeight());
                out.setRGB(x, y, top.getRGB(Math.min(sx, top.getWidth() - 1), Math.min(sy, top.getHeight() - 1)));
            }
        }

        // Draw 8 horizon images
        for (int i = 0; i < 8; i++) {
            BufferedImage img = ImageIO.read(shotFiles.get(i));
            int xOff = i * w;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out.setRGB(xOff + x, (h / 2) + y, img.getRGB(x, y));
                }
            }
        }

        // Draw bottom strip stretched across width
        int bottomY = (h / 2) + h;
        for (int x = 0; x < panoW; x++) {
            int sx = (int)((x / (double)panoW) * bottom.getWidth());
            for (int y = 0; y < h / 2; y++) {
                int sy = (int)((y / (double)(h / 2)) * bottom.getHeight());
                out.setRGB(x, bottomY + y, bottom.getRGB(Math.min(sx, bottom.getWidth() - 1), Math.min(sy, bottom.getHeight() - 1)));
            }
        }

        if (!outputDir.exists()) outputDir.mkdirs();
        File outFile = new File(outputDir, "pano_" + sessionId + ".png");
        ImageIO.write(out, "png", outFile);
        return outFile;
    }
}
