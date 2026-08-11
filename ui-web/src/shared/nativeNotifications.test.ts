import { describe, expect, it } from "vitest";
import { isNativeNotificationAllowed } from "./nativeNotifications";

const notification = (title: string, target: string, read = false) => ({
  id: "n1", category: "UPDATE", title, message: "safe body", target, createdAt: new Date(0).toISOString(), read,
});

describe("native notification privacy gate", () => {
  it("allows only unread whitelisted notification types", () => {
    expect(isNativeNotificationAllowed(notification("更新可用", "updates"))).toBe(true);
    expect(isNativeNotificationAllowed(notification("更新可用", "updates", true))).toBe(false);
    expect(isNativeNotificationAllowed(notification("学生张三提交了作业", "tasks"))).toBe(false);
    expect(isNativeNotificationAllowed(notification("更新可用", "knowledge"))).toBe(false);
  });
});
