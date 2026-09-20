import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { LocalAppEvent } from "./ipc";

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

const basePreferences = {
  role: "STUDENT",
  developerMode: false,
  canMaintainLocalData: true,
  secretsExposed: false,
  general: {
    automaticUpdateChecks: true,
    skippedVersion: "",
    proxyMode: "SYSTEM",
    proxyHost: "",
    proxyPort: 0,
    reducedMotion: false,
    highContrast: false,
    supportLogging: false,
    supportLoggingExpiresAt: 0,
    updateMirrorsEnabled: false,
    language: "zh",
    nativeNotificationsEnabled: true,
    meteredNetwork: false,
    theme: "system",
    font: "modern",
    density: "comfortable",
  },
  notifications: [],
  tasks: [],
  helpTopics: [],
};

const updateCheck = {
  status: "AVAILABLE",
  message: "",
  available: {
    version: { major: 3, minor: 4, patch: 0 },
    releaseNotesUrl: "https://example.com/notes",
  },
};

function mockHappyRequests() {
  requestMock.mockImplementation((method: string) => {
    if (method === "settings.preferences") return Promise.resolve(basePreferences);
    if (method === "settings.update.check") return Promise.resolve(updateCheck);
    if (method === "settings.update.install") return Promise.resolve({});
    if (method === "settings.update.skip") return Promise.resolve({});
    if (method === "system.cancel") return Promise.resolve({ cancelled: true });
    throw new Error(`Unexpected request: ${method}`);
  });
}

// UpdateDialog 的自动检查由模块级 startupCheckStarted 标记限制为每会话一次；
// 每个用例重置模块注册表后再动态导入，保证互不影响。
async function renderDialog() {
  const { UpdateDialog } = await import("./UpdateDialog");
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <UpdateDialog />
    </QueryClientProvider>,
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

describe("UpdateDialog", () => {
  beforeEach(() => {
    vi.resetModules();
    requestMock.mockReset();
    requestWithIdMock.mockReset();
    subscribeMock.mockReset();
    subscribeMock.mockImplementation(() => Promise.resolve(() => {}));
  });

  it("walks the state machine from download progress to launching the installer", async () => {
    mockHappyRequests();
    let resolveDownload: (value: unknown) => void = () => {};
    requestWithIdMock.mockImplementation((method: string) => {
      if (method === "settings.update.download") {
        return new Promise((resolve) => {
          resolveDownload = resolve;
        });
      }
      throw new Error(`Unexpected with-id request: ${method}`);
    });
    await renderDialog();

    expect(await screen.findByText("发现新版本 SQLTeacher 3.4.0")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "立即下载并安装" }));
    expect(requestWithIdMock).toHaveBeenCalledWith(
      "settings.update.download",
      {},
      expect.any(String),
    );
    expect(subscribeMock).toHaveBeenCalledTimes(1);

    const handler = subscribeMock.mock.calls[0]?.[0] as ((event: LocalAppEvent) => void) | undefined;
    if (!handler) throw new Error("progress subscription handler missing");
    const requestId = String(requestWithIdMock.mock.calls[0]?.[2] ?? "");
    act(() => {
      handler(progressEvent(requestId, 0.42));
    });
    expect(await screen.findByText(/正在下载更新… 42%/)).toBeInTheDocument();
    // v3.8.0 UIX-4：busy 保留按钮原文案(内联 spinner),进度直接体现在按钮文案上。
    expect(screen.getByRole("button", { name: "下载中 42%" })).toBeDisabled();
    expect(
      screen.queryByRole("button", { name: "启动安装程序" }),
    ).not.toBeInTheDocument();

    resolveDownload({});
    fireEvent.click(await screen.findByRole("button", { name: "启动安装程序" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("settings.update.install", {}),
    );
    // 安装请求成功后应用保持运行：弹窗不关闭，主按钮停留在 launching 忙状态。
    expect(screen.getByRole("button", { name: "立即下载并安装" })).toBeDisabled();
    expect(screen.queryByText("发现新版本 SQLTeacher 3.4.0")).toBeInTheDocument();
  });

  it("cancels an in-flight download and returns to the idle state", async () => {
    mockHappyRequests();
    let rejectDownload: (cause: unknown) => void = () => {};
    requestWithIdMock.mockImplementation((method: string) => {
      if (method === "settings.update.download") {
        return new Promise((_resolve, reject) => {
          rejectDownload = reject;
        });
      }
      throw new Error(`Unexpected with-id request: ${method}`);
    });
    await renderDialog();

    expect(await screen.findByText("发现新版本 SQLTeacher 3.4.0")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "立即下载并安装" }));
    expect(await screen.findByText(/正在下载更新… 0%/)).toBeInTheDocument();

    const requestId = String(requestWithIdMock.mock.calls[0]?.[2] ?? "");
    fireEvent.click(screen.getByRole("button", { name: "取消下载" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("system.cancel", { targetRequestId: requestId }),
    );

    // Java 侧中止后请求以错误收尾：状态机回到 idle，展示原因且可重新发起下载。
    rejectDownload(new Error("更新下载中断，请检查网络后重试"));
    expect(await screen.findByText("更新下载中断，请检查网络后重试")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "立即下载并安装" })).toBeEnabled();
  });

  it("skips the offered version and closes the dialog", async () => {
    mockHappyRequests();
    await renderDialog();

    expect(await screen.findByText("发现新版本 SQLTeacher 3.4.0")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "跳过此版本" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("settings.update.skip", { version: "3.4.0" }),
    );
    await waitFor(() =>
      expect(screen.queryByText(/发现新版本 SQLTeacher/)).not.toBeInTheDocument(),
    );
  });

  it("does not reopen the dialog for a version the user already skipped", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") {
        return Promise.resolve({
          ...basePreferences,
          general: { ...basePreferences.general, skippedVersion: "3.4.0" },
        });
      }
      if (method === "settings.update.check") return Promise.resolve(updateCheck);
      throw new Error(`Unexpected request: ${method}`);
    });
    await renderDialog();

    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("settings.update.check"));
    expect(screen.queryByText(/发现新版本 SQLTeacher/)).not.toBeInTheDocument();
  });
});
