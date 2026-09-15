import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
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

describe("SettingsPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "settings.environment")
        return Promise.resolve({
          connectivity: "未连接",
          manualPathPolicy: "PATH",
          runnerCapabilities: [],
          components: [],
        });
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("loads preferences without probing the local environment", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("按你的方式使用 SQLTeacher")).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("settings.preferences");
    expect(requestMock).not.toHaveBeenCalledWith("settings.environment");

    fireEvent.click(screen.getByText("本机环境与组件"));
    fireEvent.click(screen.getByRole("button", { name: "开始检测" }));
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("settings.environment"));
  });

  it("surfaces an error toast when restoring a backup fails", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "settings.environment")
        return Promise.resolve({
          connectivity: "未连接",
          manualPathPolicy: "PATH",
          runnerCapabilities: [],
          components: [],
        });
      if (method === "settings.storage")
        return Promise.resolve({ storage: { categoryBytes: {}, usableBytes: 1024 } });
      if (method === "settings.backups") {
        return Promise.resolve({
          items: [
            {
              id: "backup-1",
              createdAt: "2026-09-01T00:00:00Z",
              sizeBytes: 2048,
              automatic: false,
            },
          ],
        });
      }
      if (method === "settings.backup.restore") return Promise.reject(new Error("备份文件已损坏"));
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByText("备份与本地数据"));
    fireEvent.click(await screen.findByRole("button", { name: "恢复" }));
    fireEvent.click(await screen.findByRole("button", { name: "确认恢复" }));

    expect(await screen.findByText(/恢复备份失败/)).toBeInTheDocument();
    expect(screen.getByText(/备份文件已损坏/)).toBeInTheDocument();
  });

  it("renders the appearance and bank sections as collapsible panels", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "practice.bank.channels") return Promise.resolve({ items: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    const appearance = screen.getByText("外观与使用体验").closest("details");
    expect(appearance).not.toBeNull();
    expect(appearance).toHaveAttribute("open");
    const bank = screen.getByText("题库更新").closest("details");
    expect(bank).not.toBeNull();
    expect(bank).not.toHaveAttribute("open");
  });

  it("saves bank update preferences from the collapsed panel", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "practice.bank.channels") {
        return Promise.resolve({
          items: [{ channel: "network", bankVersion: 19, updatedAt: "2026-09-01T00:00:00Z" }],
        });
      }
      if (method === "settings.bank.update") return Promise.resolve({});
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    const checkbox = screen.getByText("定时检查题库更新").closest("label")?.querySelector("input");
    expect(checkbox).not.toBeNull();
    fireEvent.click(checkbox as HTMLInputElement);
    fireEvent.click(screen.getByRole("button", { name: "保存题库设置" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("settings.bank.update", {
        autoCheckEnabled: true,
        subscribedChannels: ["network"],
      }),
    );
    expect(await screen.findByText("题库更新设置已保存")).toBeInTheDocument();
  });

  it("renders the about panel with the confirmed credits and loads privacy inside it", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "settings.help")
        return Promise.resolve({
          content: "SQLTeacher 隐私说明（v1.11）\n\nSQLTeacher 默认不收集启动次数、页面访问、学习行为或设备指纹。",
        });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    const about = screen.getByText("关于").closest("details");
    expect(about).not.toBeNull();
    expect(about).not.toHaveAttribute("open");
    for (const credit of [
      "杨春蕾老师",
      "王红艺老师",
      "华佳浩（学生、主要负责人）",
      "董晓佟（学生）",
      "刘浩武（学生）",
      "刘馨潞（学生）",
      "邵思瀚（老学长）",
    ]) {
      expect(screen.getByText(credit)).toBeInTheDocument();
    }
    expect(screen.getByText(/软件著作权归河南科技大学所有/)).toBeInTheDocument();
    expect(screen.queryByText(/SQLTeacher 隐私说明/)).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "隐私与数据" }));
    expect(await screen.findByText(/SQLTeacher 隐私说明（v1.11）/)).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("settings.help", { topicId: "privacy" });
    expect(about?.contains(screen.getByText(/默认不收集启动次数/))).toBe(true);
  });

  it("gates problem report submission behind the diagnostic preview and shows the one-time token", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "support.report.preview") return Promise.resolve({ javaVersion: "25" });
      if (method === "support.report.submit")
        return Promise.resolve({
          reportId: "report-1",
          queryToken: "query-token-1",
          status: "RECEIVED",
          submittedAt: "2026-09-15T08:00:00Z",
        });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    // 更新与帮助面板和关于面板各有一个入口。
    const feedbackButtons = screen.getAllByRole("button", { name: "问题反馈" });
    expect(feedbackButtons).toHaveLength(2);
    const [feedbackEntry] = feedbackButtons;
    if (!feedbackEntry) throw new Error("问题反馈入口缺失");
    fireEvent.click(feedbackEntry);

    fireEvent.change(await screen.findByLabelText("摘要"), {
      target: { value: "练习提交失败" },
    });
    fireEvent.change(screen.getByLabelText("详细描述"), {
      target: { value: "点击提交后一直转圈" },
    });
    const submit = screen.getByRole("button", { name: "提交反馈" });
    expect(submit).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "预览诊断字段" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("support.report.preview", {
        diagnostics: {
          environment: false,
          recentErrors: false,
          networkSummary: false,
          updateState: false,
        },
      }),
    );
    expect(await screen.findByText(/javaVersion/)).toBeInTheDocument();
    expect(submit).toBeEnabled();
    fireEvent.click(submit);

    expect(await screen.findByText(/反馈编号：report-1/)).toBeInTheDocument();
    expect(screen.getByText("query-token-1")).toBeInTheDocument();
    expect(screen.getByText(/仅显示这一次/)).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith(
      "support.report.submit",
      expect.objectContaining({
        type: "BUG",
        severity: "MINOR",
        summary: "练习提交失败",
        diagnostics: {
          environment: false,
          recentErrors: false,
          networkSummary: false,
          updateState: false,
        },
      }),
    );
  });

  it("queries and withdraws a submitted report with report id and query token", async () => {
    let withdrawn = false;
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "support.report.status")
        return Promise.resolve({
          reportId: "report-1",
          status: withdrawn ? "WITHDRAWN" : "RECEIVED",
          submittedAt: "2026-09-15T08:00:00Z",
        });
      if (method === "support.report.withdraw") {
        withdrawn = true;
        return Promise.resolve({ withdrawn: true });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    fireEvent.click(screen.getByRole("button", { name: "查询反馈进度" }));
    fireEvent.change(await screen.findByLabelText("反馈编号"), {
      target: { value: "report-1" },
    });
    fireEvent.change(screen.getByLabelText(/查询凭据/), {
      target: { value: "query-token-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "查询进度" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("support.report.status", {
        reportId: "report-1",
        queryToken: "query-token-1",
      }),
    );
    expect(await screen.findByText(/当前状态：/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "撤回反馈" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("support.report.withdraw", {
        reportId: "report-1",
        queryToken: "query-token-1",
      }),
    );
    expect(await screen.findByText(/当前状态：WITHDRAWN/)).toBeInTheDocument();
  });

  it("rolls a bank channel back to the previous version after confirmation", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences")
        return Promise.resolve({ ...preferences, role: "ADMINISTRATOR" });
      if (method === "practice.bank.channels")
        return Promise.resolve({
          items: [{ channel: "network", bankVersion: 3, updatedAt: "2026-09-01T00:00:00Z" }],
        });
      if (method === "teaching.bank.rollback") return Promise.resolve({ bankVersion: 2 });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    fireEvent.click(screen.getByText("题库更新"));
    fireEvent.click(await screen.findByRole("button", { name: "回滚上一版本" }));

    expect(screen.getByRole("dialog")).toHaveTextContent(/从版本 3 回滚到版本 2/);
    fireEvent.click(screen.getByRole("button", { name: "确认回滚" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("teaching.bank.rollback", {
        channel: "network",
        bankVersion: 2,
      }),
    );
    expect(await screen.findByText(/已回滚到版本 2/)).toBeInTheDocument();
  });

  it("keeps the rollback button disabled when the channel has no previous version", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences")
        return Promise.resolve({ ...preferences, role: "ADMINISTRATOR" });
      if (method === "practice.bank.channels")
        return Promise.resolve({
          items: [{ channel: "network", bankVersion: 1, updatedAt: "2026-09-01T00:00:00Z" }],
        });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    fireEvent.click(screen.getByText("题库更新"));

    expect(await screen.findByText(/当前频道没有可回滚的历史版本/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "回滚上一版本" })).toBeDisabled();
    expect(requestMock).not.toHaveBeenCalledWith("teaching.bank.rollback", expect.anything());
  });

  it("hides the bank rollback control for non-administrators", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "practice.bank.channels") return Promise.resolve({ items: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <SettingsPage />
      </QueryClientProvider>,
    );

    await screen.findByText("按你的方式使用 SQLTeacher");
    fireEvent.click(screen.getByText("题库更新"));

    expect(screen.queryByRole("button", { name: "回滚上一版本" })).not.toBeInTheDocument();
  });

  it("manages AI providers in the settings panel without ever echoing the key", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "settings.preferences") return Promise.resolve(preferences);
      if (method === "settings.environment")
        return Promise.resolve({ connectivity: "未连接", manualPathPolicy: "PATH", runnerCapabilities: [], components: [] });
      if (method === "ai.provider.list") {
        return Promise.resolve({
          items: [
            {
              id: "deepseek",
              displayName: "DeepSeek",
              kind: "OPENAI_COMPATIBLE",
              endpoint: "https://api.deepseek.com",
              model: "deepseek-chat",
              enabled: true,
              active: false,
            },
          ],
          activeProfileId: "",
        });
      }
      if (method === "ai.provider.activate") return Promise.resolve({ items: [], activeProfileId: "deepseek" });
      if (method === "ai.provider.save") return Promise.resolve({ items: [], activeProfileId: "" });
      if (method === "ai.provider.test") {
        return Promise.resolve({ success: true, message: "连接成功。", models: ["deepseek-chat"] });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <Toaster>
          <SettingsPage />
        </Toaster>
      </QueryClientProvider>,
    );

    // v3.4.4 AIS-2：面板显示当前生效通道与供应商清单。
    fireEvent.click(await screen.findByText(/AI 模型/));
    expect(await screen.findByText(/本地 Ollama（http:\/\/localhost:11434）/)).toBeInTheDocument();
    expect(await screen.findByText("DeepSeek")).toBeInTheDocument();

    // 新建 → 填表 → 测试连接成功 → 保存；密钥只在请求里出现，界面上永不回显。
    fireEvent.click(screen.getByRole("button", { name: "新建网络 AI 供应商" }));
    fireEvent.change(await screen.findByLabelText("显示名称"), { target: { value: "新建供应商" } });
    fireEvent.change(screen.getByLabelText("API 端点"), { target: { value: "https://api.example.com" } });
    fireEvent.change(screen.getByLabelText("模型名称"), { target: { value: "example-chat" } });
    const keyInput = screen.getByLabelText("API Key");
    expect((keyInput as HTMLInputElement).type).toBe("password");
    fireEvent.change(keyInput, { target: { value: "sk-test" } });
    fireEvent.click(screen.getByRole("button", { name: "测试连接" }));

    expect(await screen.findByText("连接成功。")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "ai.provider.save",
        expect.objectContaining({ displayName: "新建供应商", credential: "sk-test" }),
      ),
    );
    expect(screen.getByText("AI 供应商已保存")).toBeInTheDocument();
  });
});
