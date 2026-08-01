package com.sqlteacher.application.support;

import java.util.Arrays;
import java.util.Locale;

/**
 * A user-selected screenshot attached to a problem report. Only PNG and JPEG are
 * allowed, the payload is limited to 2 MiB, and the bytes must already be free of
 * metadata (EXIF/GPS) before this value is created.
 */
public record ScreenshotAttachment(String filename, String mimeType, byte[] data) {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public ScreenshotAttachment {
        filename = filename == null ? "" : filename.strip();
        mimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (filename.isEmpty() || filename.length() > 160) throw new IllegalArgumentException("screenshot filename is invalid");
        if (data == null || data.length == 0) throw new IllegalArgumentException("screenshot must not be empty");
        if (data.length > MAX_BYTES) throw new IllegalArgumentException("screenshot exceeds the 2 MiB limit");
        if (!"image/png".equals(mimeType) && !"image/jpeg".equals(mimeType)) {
            throw new IllegalArgumentException("screenshot format must be PNG or JPEG");
        }
        data = data.clone();
    }

    public String fileName() { return filename; }
    @Override public byte[] data() { return data.clone(); }
    @Override public boolean equals(Object other) {
        return other instanceof ScreenshotAttachment that && filename.equals(that.filename)
            && mimeType.equals(that.mimeType) && Arrays.equals(data, that.data);
    }
    @Override public int hashCode() { return 31 * (31 * filename.hashCode() + mimeType.hashCode()) + Arrays.hashCode(data); }
}
