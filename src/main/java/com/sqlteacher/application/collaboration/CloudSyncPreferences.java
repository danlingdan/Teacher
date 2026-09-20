package com.sqlteacher.application.collaboration;

/**
 * v3.7.0 TFB-C1/C2: student-controlled learning-record sync preferences. Upload pause stops
 * every upload path (manual, login-triggered, and automatic) while keeping local records and
 * the offline assignment queue untouched; automatic sync defaults to enabled per the
 * 2026-09-20 product decision, and can be turned off at any time.
 */
public interface CloudSyncPreferences {

    /** True while every learning-record upload is paused by the user. */
    boolean uploadPaused();

    void uploadPaused(boolean paused);

    /** True when the app may sync learning records periodically on its own (default true). */
    boolean autoSyncEnabled();

    void autoSyncEnabled(boolean enabled);
}
