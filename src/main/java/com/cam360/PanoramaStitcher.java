package com.cam360;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PanoramaStitcher {
    private PanoramaStitcher() {}

    /**
     * Stitches the 10 captured frames asynchronously to prevent game thread stutter.
     */
    public static CompletableFuture<File> stitchSimpleAsync(
            List<File> shotFiles,
            File outputDir,
            long sessionId
    ) {
        // Deep-copy the list to ensure the game thread can modify its own collection concurrently
        final List<File> filesCopy = new ArrayList<>(shotFiles);

        return CompletableFuture.supplyAsync(() -> {
            try {
                if (filesCopy.size() < 10) {
                    throw new IllegalArgumentException("Need 10 shots (8 horizon + top + bottom).");
                }

                // Cache the first image to determine base resolution
                BufferedImage first = ImageIO.read(filesCopy.get(0));
                if (first == null) throw new IllegalStateException("Failed to read first screenshot.");

                int w = first.getWidth();
                int h = first.getHeight();

                int panoW = w * 8;
                int stripH = h / 2;
                int panoH = h + stripH + stripH; 

                BufferedImage out = new BufferedImage(panoW, panoH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();

                try {
                    // 1. Read and draw top strip (stretched)
                    BufferedImage top = ImageIO.read(filesCopy.get(8));
                    if (top != null) {
                        g.drawImage(top, 0, 0, panoW, stripH, null);
                    }

                    // 2. Read and draw 8 horizon images (fixed layout bug by caching images outside loop)
                    for (int i = 0; i < 8; i++) {
                        BufferedImage img = ImageIO.read(filesCopy.get(i));
                        if (img != null) {
                            g.drawImage(img, i * w, stripH, w, h, null);
                        }
                    }

                    // 3. Read and draw bottom strip (stretched)
                    BufferedImage bottom = ImageIO.read(filesCopy.get(9));
                    if (bottom != null) {
                        g.drawImage(bottom, 0, stripH + h, panoW, stripH, null);
                    }
                } finally {
                    g.dispose(); // Free graphics context memory instantly
                }

                if (!outputDir.exists()) outputDir.mkdirs();
                File outFile = new File(outputDir, "pano_" + sessionId + ".png");
                ImageIO.write(out, "png", outFile);
                return outFile;

            } catch (Exception e) {
                throw new RuntimeException("Failed to automatically stitch 360 panorama", e);
            }
        });
    }
}
