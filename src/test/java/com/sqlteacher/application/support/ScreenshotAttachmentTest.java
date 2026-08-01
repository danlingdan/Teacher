package com.sqlteacher.application.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotAttachmentTest {

    @Test void validatesMimeSizeAndFilenameAndDefensivelyCopiesBytes() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotAttachment("a.bmp", "image/bmp", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotAttachment("", "image/png", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotAttachment("a.png", "image/png", new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new ScreenshotAttachment("a.png", "image/png", new byte[ScreenshotAttachment.MAX_BYTES + 1]));

        byte[] original = {1, 2, 3};
        ScreenshotAttachment attachment = new ScreenshotAttachment("shot.png", "image/png", original);
        original[0] = 99;
        assertEquals(1, attachment.data()[0], "constructor must copy the payload");
        attachment.data()[0] = 55;
        assertEquals(1, attachment.data()[0], "accessor must copy the payload");
        assertEquals(new ScreenshotAttachment("shot.png", "image/png", new byte[]{1, 2, 3}), attachment);
    }

    @Test void validatesViaFactoryStyleWithScreenshot() {
        ProblemReportDraft draft = new ProblemReportDraft("k", ProblemReportDraft.Type.BUG, ProblemReportDraft.Severity.MINOR,
            "summary", "description", "", "", "", "", null, null);
        assertNull(draft.screenshot());
        ScreenshotAttachment attachment = new ScreenshotAttachment("s.png", "image/png", new byte[]{1});
        ProblemReportDraft withShot = draft.withScreenshot(attachment);
        assertEquals(attachment, withShot.screenshot());
        assertNull(draft.screenshot(), "original draft must stay unchanged");
    }
}
