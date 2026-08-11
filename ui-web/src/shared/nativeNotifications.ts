import type { SettingsWorkspace } from "./types";

type AppNotification = SettingsWorkspace["notifications"][number];

const allowedTitles = new Set([
  "更新可用", "更新完成", "备份完成", "备份失败", "任务完成", "任务失败", "反馈已提交",
  "Update available", "Update installed", "Backup complete", "Backup failed", "Task complete", "Task failed", "Feedback submitted",
]);
const allowedTargets = new Set(["updates", "tasks", "support"]);

export function isNativeNotificationAllowed(notification: AppNotification) {
  return !notification.read && allowedTitles.has(notification.title.trim()) && allowedTargets.has(notification.target.trim().toLowerCase());
}

export async function deliverNativeNotifications(notifications: AppNotification[]) {
  const eligible = notifications.filter(isNativeNotificationAllowed);
  if (eligible.length === 0) return 0;
  try {
    const { isPermissionGranted, requestPermission, sendNotification } = await import("@tauri-apps/plugin-notification");
    let granted = await isPermissionGranted();
    if (!granted) granted = await requestPermission() === "granted";
    if (!granted) return 0;
    for (const notification of eligible) {
      sendNotification({ title: notification.title, body: notification.message.slice(0, 160) });
    }
    return eligible.length;
  } catch {
    return 0;
  }
}
