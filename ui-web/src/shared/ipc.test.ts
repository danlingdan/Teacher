import { describe, expect, it } from "vitest";
import { LocalAppError, localAppRequest } from "./ipc";

describe("localAppRequest", () => {
  it("refuses to invent browser fallback data", async () => {
    await expect(localAppRequest("system.health")).rejects.toMatchObject({
      code: "DESKTOP_HOST_REQUIRED",
      retryable: false,
    });
  });

  it("normalizes structured bridge failures", () => {
    const error = LocalAppError.from({ code: "BUSY", message: "busy", retryable: true });
    expect(error).toMatchObject({ code: "BUSY", message: "busy", retryable: true });
  });
});
