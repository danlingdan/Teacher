import { describe, expect, it } from "vitest";
import manifest from "../../../contracts/ipc/v1/manifest.json";
import { CONTRACT_VERSION, LocalAppError, localAppRequest } from "./ipc";

describe("localAppRequest", () => {
  it("refuses to invent browser fallback data", async () => {
    await expect(localAppRequest("system.health")).rejects.toMatchObject({
      code: "DESKTOP_HOST_REQUIRED",
      retryable: false,
    });
  });

  it("normalizes structured bridge failures", () => {
    const error = LocalAppError.from({
      code: "BUSY",
      message: "busy",
      retryable: true,
    });
    expect(error).toMatchObject({
      code: "BUSY",
      message: "busy",
      retryable: true,
    });
  });

  it("stays synchronized with the frozen machine-readable contract", () => {
    expect(CONTRACT_VERSION).toBe(manifest.contractVersion);
    expect(manifest.methods).toContain("session.current");
    expect(manifest.methods).toEqual(
      expect.arrayContaining([
        "knowledge.import.preview",
        "runner.run",
        "sql.analyze",
        "sql.execute",
        "ai.knowledge.ask",
        "account.login",
        "account.logout",
        "teaching.workspace",
        "teaching.exercise.toggle",
        "cloud.workspace",
        "cloud.sync",
        "cloud.class.create",
        "settings.workspace",
        "settings.preferences",
        "cloud.assignment.snapshot",
        "cloud.assignment.submit",
        "cloud.notifications",
        "cloud.notification.read",
        "cloud.feedback.list",
        "cloud.feedback.save",
        "cloud.mastery",
        "cloud.courses",
        "cloud.course.content",
        "cloud.course.package.preview",
        "cloud.course.package.import",
        "learning.portfolio",
        "learning.portfolio.export",
        "settings.environment",
        "settings.storage",
        "settings.update",
      ]),
    );
    expect(manifest.events).toEqual(
      expect.arrayContaining([
        "import.progress",
        "runner.progress",
        "ai.delta",
      ]),
    );
    expect(manifest.compatibility.policy).toBe("additive-within-v1");
  });
});
