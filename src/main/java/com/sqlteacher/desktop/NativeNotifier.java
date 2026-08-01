package com.sqlteacher.desktop;

import com.sqlteacher.application.system.NativeNotificationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

/**
 * Optional Windows native notifications backed by {@link SystemTray} ballons.
 * Always off by default; the user must enable them in settings. Any failure
 * degrades silently to the in-app notification center (never blocks the UI).
 * Notification text is whitelist-gated by {@link NativeNotificationPolicy}.
 */
public final class NativeNotifier {
    private static final Logger log = LoggerFactory.getLogger(NativeNotifier.class);
    private static final NativeNotifier INSTANCE = new NativeNotifier();
    private volatile TrayIcon icon;
    private volatile boolean enabled;

    private NativeNotifier() { }

    public static NativeNotifier instance() { return INSTANCE; }

    /** Enable/disable native notifications (disabled by default). */
    public synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value && icon != null) {
            SystemTray.getSystemTray().remove(icon);
            icon = null;
        }
    }

    public boolean isEnabled() { return enabled; }

    /** Shows a whitelist-gated notification; silently ignores anything not on the whitelist. */
    public void notify(String title, String message, String target, ActionListener onClick) {
        if (!enabled || !NativeNotificationPolicy.isAllowed(title, target)) return;
        try {
            TrayIcon current = icon();
            current.setToolTip("SQLTeacher");
            current.displayMessage(title, message, TrayIcon.MessageType.INFO);
            if (onClick != null) current.addActionListener(onClick);
        } catch (Throwable error) {
            log.debug("Native notification unavailable, falling back to in-app: {}", error.getClass().getSimpleName());
        }
    }

    private TrayIcon icon() {
        TrayIcon current = icon;
        if (current != null) return current;
        synchronized (this) {
            if (icon == null) {
                BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
                icon = new TrayIcon(image, "SQLTeacher");
                icon.setImageAutoSize(true);
                try {
                    SystemTray.getSystemTray().add(icon);
                } catch (java.awt.AWTException error) {
                    icon = null;
                    log.debug("System tray unavailable, native notifications disabled: {}", error.getClass().getSimpleName());
                    throw new IllegalStateException("system tray unavailable", error);
                }
            }
            return icon;
        }
    }
}
