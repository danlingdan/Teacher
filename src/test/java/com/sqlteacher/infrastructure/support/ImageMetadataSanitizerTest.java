package com.sqlteacher.infrastructure.support;

import com.sqlteacher.application.support.ScreenshotAttachment;
import org.junit.jupiter.api.Test;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

class ImageMetadataSanitizerTest {

    @Test void reencodesPngAndKeepsPixels() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000); image.setRGB(3, 2, 0x00FF00);
        byte[] raw = encode(image, "png");
        ScreenshotAttachment clean = ImageMetadataSanitizer.sanitize("shot.png", "image/png", raw);
        assertEquals("image/png", clean.mimeType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(clean.data()));
        assertEquals(4, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
        assertEquals(0xFF0000, decoded.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test void reencodesPngAndDropsEmbeddedTextMetadata() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        byte[] raw = encodePngWithText(image);
        assertTrue(containsAscii(raw, "EXIF GPS MARKER"), "fixture must embed a metadata marker before sanitizing");
        ScreenshotAttachment clean = ImageMetadataSanitizer.sanitize("shot.png", "image/png", raw);
        assertFalse(containsAscii(clean.data(), "EXIF GPS MARKER"), "sanitized output must not contain embedded metadata");
        assertEquals(4, ImageIO.read(new ByteArrayInputStream(clean.data())).getWidth());
    }

    @Test void reencodesJpegAndKeepsDimensions() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        byte[] raw = encode(image, "jpg");
        ScreenshotAttachment clean = ImageMetadataSanitizer.sanitize("shot.jpg", "image/jpeg", raw);
        assertEquals("image/jpeg", clean.mimeType());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(clean.data()));
        assertEquals(4, decoded.getWidth());
        assertEquals(3, decoded.getHeight());
    }

    @Test void rejectsUnsupportedMimeEmptyOversizedAndUndecodableInput() {
        byte[] png = encode(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png");
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataSanitizer.sanitize("shot.bmp", "image/bmp", png));
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataSanitizer.sanitize("shot.png", "image/png", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataSanitizer.sanitize("shot.png", "image/png", new byte[ScreenshotAttachment.MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataSanitizer.sanitize("shot.png", "image/png", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
    }

    @Test void rejectsImagesBeyondMaxDimension() throws Exception {
        BufferedImage huge = new BufferedImage(5000, 10, BufferedImage.TYPE_INT_RGB);
        byte[] raw = encode(huge, "png");
        assertThrows(IllegalArgumentException.class, () -> ImageMetadataSanitizer.sanitize("huge.png", "image/png", raw));
    }

    private static byte[] encode(BufferedImage image, String format) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, format, output);
            return output.toByteArray();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Writes a PNG whose tEXt chunk embeds a recognizable marker that a re-encode should drop. */
    private static byte[] encodePngWithText(BufferedImage image) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        assertTrue(writers.hasNext());
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
        IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree("javax_imageio_png_1.0");
        IIOMetadataNode text = new IIOMetadataNode("tEXt");
        IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
        entry.setAttribute("keyword", "Comment");
        entry.setAttribute("value", "EXIF GPS MARKER");
        text.appendChild(entry);
        tree.appendChild(text);
        metadata.setFromTree("javax_imageio_png_1.0", tree);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writer.setOutput(ImageIO.createImageOutputStream(output));
            writer.write(null, new IIOImage(image, null, metadata), param);
            writer.dispose();
            return output.toByteArray();
        }
    }

    private static boolean containsAscii(byte[] bytes, String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int index = 0; index + needle.length <= bytes.length; index++) {
            for (int offset = 0; offset < needle.length; offset++) if (bytes[index + offset] != needle[offset]) continue outer;
            return true;
        }
        return false;
    }
}
