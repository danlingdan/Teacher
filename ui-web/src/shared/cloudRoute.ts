import type { CloudNotification } from "./types";

/** Cloud notifications may identify an assignment but do not contain its classroom. */
export function cloudNotificationRoute(notification: CloudNotification): string {
  if (
    (notification.type === "FEEDBACK_PUBLISHED" || notification.type === "ASSIGNMENT_PUBLISHED") &&
    notification.resourceType === "ASSIGNMENT" &&
    notification.resourceId.trim()
  ) {
    const assignment = `assignment=${encodeURIComponent(notification.resourceId)}`;
    return notification.type === "FEEDBACK_PUBLISHED"
      ? `/cloud?${assignment}&view=feedback`
      : `/cloud?${assignment}`;
  }
  return "/cloud";
}
