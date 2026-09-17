import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { LocalAppEvent } from "./ipc";
import { useUpdateInstaller } from "./useUpdateInstaller";

const requestMock = vi.fn();
const requestWithIdMock = vi.fn();
const subscribeMock = vi.fn();

vi.mock("./ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestWithIdMock(...args),
  subscribeLocalAppEvents: (...args: unknown[]) => subscribeMock(...args),
  cancelLocalAppRequest: (targetRequestId: string) => {
    requestMock("system.cancel", { targetRequestId });
    return Promise.resolve({ cancelled: true });
  },
}));

function Harness({ mode }: { mode: "download" | "forceDownload" }) {
  const installer = useUpdateInstaller();
  return (
    <div>
      <span data-testid="phase">{installer.phase}</span>
      <span data-testid="fraction">{installer.fraction}</span>
      <span data-testid="error">{installer.error}</span>
      <button onClick={mode === "download" ? installer.download : installer.forceDownload}>
        start
      </button>
      <button onClick={installer.cancel}>cancel</button>
      <button onClick={installer.launch}>launch</button>
    </div>
  );
}

function progressEvent(requestId: string, fraction: number): LocalAppEvent {
  return {
    type: "event",
    requestId,
    contractVersion: "3.0-v1",
    event: "progress",
    payload: { phase: "update.download", fraction },
  };
}

describe("useUpdateInstaller", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestWithIdMock.mockReset();
    subscribeMock.mockReset();
    requestMock.mockResolvedValue({ launched: true });
    subscribeMock.mockImplementation(() => Promise.resolve(() => {}));
  });

  it("downloads through the regular check-gated method and stages the installer", async () => {
    requestWithIdMock.mockResolvedValue({ ready: true, version: "3.5.4" });
    render(<Harness mode="download" />);

    fireEvent.click(screen.getByRole("button", { name: "start" }));
    expect(requestWithIdMock).toHaveBeenCalledWith(
      "settings.update.download",
      {},
      expect.any(String),
    );
    expect(screen.getByTestId("phase").textContent).toBe("downloading");

    const handler = subscribeMock.mock.calls[0]?.[0] as ((event: LocalAppEvent) => void) | undefined;
    const requestId = String(requestWithIdMock.mock.calls[0]?.[2] ?? "");
    act(() => {
      handler?.(progressEvent(requestId, 0.5));
    });
    expect(screen.getByTestId("fraction").textContent).toBe("0.5");

    await waitFor(() => expect(screen.getByTestId("phase").textContent).toBe("ready"));
    fireEvent.click(screen.getByRole("button", { name: "launch" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("settings.update.install", {}),
    );
    await waitFor(() => expect(screen.getByTestId("phase").textContent).toBe("launching"));
  });

  it("force-downloads through the ungated reinstall method", async () => {
    requestWithIdMock.mockRejectedValue(new Error("无法获取官方更新清单，请检查网络后重试"));
    render(<Harness mode="forceDownload" />);

    fireEvent.click(screen.getByRole("button", { name: "start" }));
    expect(requestWithIdMock).toHaveBeenCalledWith(
      "settings.update.forceDownload",
      {},
      expect.any(String),
    );

    await waitFor(() => expect(screen.getByTestId("phase").textContent).toBe("idle"));
    expect(screen.getByTestId("error").textContent).toBe(
      "无法获取官方更新清单，请检查网络后重试",
    );
  });

  it("cancels an in-flight download via system.cancel while it is still running", async () => {
    // 下载请求保持悬挂，模拟长时间传输；取消应携带当前 requestId 发出 system.cancel。
    requestWithIdMock.mockImplementation(() => new Promise(() => { }));
    render(<Harness mode="download" />);

    fireEvent.click(screen.getByRole("button", { name: "start" }));
    const requestId = String(requestWithIdMock.mock.calls[0]?.[2] ?? "");
    expect(screen.getByTestId("phase").textContent).toBe("downloading");

    fireEvent.click(screen.getByRole("button", { name: "cancel" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("system.cancel", { targetRequestId: requestId }),
    );
  });
});
