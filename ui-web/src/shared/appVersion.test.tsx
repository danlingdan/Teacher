import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const getVersionMock = vi.fn();
vi.mock("@tauri-apps/api/app", () => ({
  getVersion: (...args: unknown[]) => getVersionMock(...args),
}));

// cachedVersion 是模块级缓存：每个用例重置模块注册表后再取最新的 hook，
// 保证用例之间不共享缓存状态。
async function loadAppVersionHook() {
  return (await import("./appVersion")).useAppVersion;
}

describe("useAppVersion", () => {
  beforeEach(() => {
    vi.resetModules();
    getVersionMock.mockReset();
  });

  it("starts from the major fallback and adopts the bridge version once read", async () => {
    getVersionMock.mockResolvedValue("3.4.0");
    const { result } = renderHook(await loadAppVersionHook());

    expect(result.current).toBe("3");
    await waitFor(() => expect(result.current).toBe("3.4.0"));
  });

  it("keeps the fallback when the desktop bridge cannot provide a version", async () => {
    getVersionMock.mockRejectedValue(new Error("not a Tauri host"));
    const { result } = renderHook(await loadAppVersionHook());

    await waitFor(() => expect(getVersionMock).toHaveBeenCalledTimes(1));
    expect(result.current).toBe("3");
  });

  it("ignores blank versions returned by the bridge", async () => {
    getVersionMock.mockResolvedValue("   ");
    const { result } = renderHook(await loadAppVersionHook());

    await waitFor(() => expect(getVersionMock).toHaveBeenCalledTimes(1));
    expect(result.current).toBe("3");
  });
});
