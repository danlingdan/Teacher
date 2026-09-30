import { describe, expect, it } from "vitest";
import { cloudNotificationRoute } from "./cloudRoute";
import type { CloudNotification } from "./types";

const feedback: CloudNotification = {
  id: "n-1",
  type: "FEEDBACK_PUBLISHED",
  resourceType: "ASSIGNMENT",
  resourceId: "assignment / 甲",
  title: "收到教师反馈",
  message: "教师已更新你的任务反馈。",
  createdAt: "2026-09-23T00:00:00Z",
};

describe("cloudNotificationRoute", () => {
  it("opens feedback for the assignment named by a cloud notification", () => {
    expect(cloudNotificationRoute(feedback)).toBe(
      `/cloud?assignment=${encodeURIComponent(feedback.resourceId)}&view=feedback`,
    );
  });

  it("opens a published assignment without forcing the feedback panel", () => {
    expect(cloudNotificationRoute({ ...feedback, type: "ASSIGNMENT_PUBLISHED" })).toBe(
      `/cloud?assignment=${encodeURIComponent(feedback.resourceId)}`,
    );
  });

  it("falls back to the cloud workspace for unrelated or incomplete notifications", () => {
    expect(cloudNotificationRoute({ ...feedback, type: "OTHER" })).toBe("/cloud");
    expect(cloudNotificationRoute({ ...feedback, resourceId: " " })).toBe("/cloud");
  });
});
