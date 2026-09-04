import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SettingsPage } from "./PlatformPages";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const preferences = {
  role: "STUDENT",
  developerMode: false,
  canMaintainLocalData: true,
  secretsExposed: false,
  general: {
    automaticUpdateChecks: true, skippedVersion: "", proxyMode: "SYSTEM", proxyHost: "", proxyPort: 0,
    reducedMotion: false, highContrast: false, supportLogging: false, supportLoggingExpiresAt: 0,
    updateMirrorsEnabled: false, language: "zh", nativeNotificationsEnabled: true, meteredNetwork: false,
    theme: "system", font: "modern", density: "comfortable",
  },
  notifications: [], tasks: [], helpTopics: [],
};

describe("SettingsPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "settings.environment") return Promise.resolve({
        connectivity: "未连接", manualPathPolicy: "PATH", runnerCapabilities: [], components: [],
      });
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("loads preferences without probing the local environment", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><SettingsPage /></QueryClientProvider>);

    expect(await screen.findByText("按你的方式使用 SQLTeacher")).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("settings.preferences");
    expect(requestMock).not.toHaveBeenCalledWith("settings.environment");

    fireEvent.click(screen.getByText("本机环境与组件"));
    fireEvent.click(screen.getByRole("button", { name: "开始检测" }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("settings.environment"));
  });
});
