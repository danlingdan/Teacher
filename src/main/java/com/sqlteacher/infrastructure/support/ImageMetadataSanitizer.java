package com.sqlteacher.infrastructure.support;

import com.sqlteacher.application.support.ScreenshotAttachment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Sanitizes screenshot bytes before upload by re-encoding through ImageIO, which
 * drops EXIF/GPS/device metadata. Also caps the decoded dimensions so an overly
 * large image cannot be used to exhaust server memory.
 */
public final class ImageMetadataSanitizer {
    public static final int MAX_DIMENSION = 4096;

    private ImageMetadataSanitizer() { }

    /**
     * @return a new {@link ScreenshotAttachment} whose bytes are a clean re-encoded image
     * @throws IllegalArgumentException when the bytes are not a decodable PNG/JPEG, exceed the
     *         2 MiB limit, or decode to a dimension beyond {@link #MAX_DIMENSION}
     */
    public static ScreenshotAttachment sanitize(String filename, String mimeType, byte[] raw) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (!"image/png".equals(normalized) && !"image/jpeg".equals(normalized)) {
            throw new IllegalArgumentException("screenshot format must be PNG or JPEG");
        }
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("screenshot must not be empty");
        if (raw.length > ScreenshotAttachment.MAX_BYTES) throw new IllegalArgumentException("screenshot exceeds the 2 MiB limit");
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException error) {
            throw new IllegalArgumentException("screenshot could not be decoded", error);
        }
        if (image == null) throw new IllegalArgumentException("screenshot could not be decoded");
        if (image.getWidth() <= 0 || image.getHeight() <= 0
            || image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
            throw new IllegalArgumentException("screenshot dimensions exceed the allowed range");
        }
        String outputFormat = "image/png".equals(normalized) ? "png" : "jpg";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, outputFormat, output);
            return new ScreenshotAttachment(filename, normalized, output.toByteArray());
        } catch (IOException error) {
            throw new IllegalArgumentException("screenshot could not be re-encoded", error);
        }
    }
}
