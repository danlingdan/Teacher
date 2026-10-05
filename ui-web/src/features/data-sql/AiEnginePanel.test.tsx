import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
import AiEnginePanel from "./AiEnginePanel";
import { CUSTOM_PRESET_ID } from "./aiProviderPresets";

// v3.10.0 HAJ-2：AI 引擎面板常驻顶栏弹层后的安全与行为测试——
// 密钥只写不回显（DPAPI 在 Java 侧），引擎状态行、本地模型切换与供应商 CRUD 走既有 IPC。
// HAJ-8：服务商预设——选择即填端点与默认模型，“自定义”保留手填，编辑按端点回选。
// HAJ-9：「发现模型」——端点+密钥拉取真实模型列表，datalist 下拉选择，编辑时携带已存 id。
const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const engineStatus = {
  networkActive: false,
  activeKind: "OLLAMA",
  displayName: "本地 Ollama",
  selectedModel: "qwen2.5:7b",
  ollamaAvailable: true,
  ollamaModelCount: 2,
  message: "ok",
};
const localModels = {
  installedModels: ["qwen2.5:7b", "llama3:8b"],
  selectedModel: "qwen2.5:7b",
  message: "ok",
};
const providers = {
  items: [
    {
      id: "deepseek",
      displayName: "DeepSeek",
      kind: "OPENAI_COMPATIBLE",
      endpoint: "https://api.deepseek.com/v1",
      model: "deepseek-chat",
      enabled: true,
      active: false,
    },
  ],
  activeProfileId: "",
};

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <Toaster>
        <AiEnginePanel />
      </Toaster>
    </QueryClientProvider>,
  );
}

// v3.10.0 HAJ-9：模型候选经 datalist 注入（原生 combobox），断言直接读 option 的 value。
const modelOptions = () =>
  Array.from(document.querySelectorAll("#ai-provider-model-options option")).map((option) =>
    option.getAttribute("value"),
  );

const discoverySuccess = {
  success: true,
  message: "连接成功，发现 2 个模型。",
  models: ["example-chat", "example-mini"],
};

function mockIpc(discovery: unknown = discoverySuccess) {
  requestMock.mockImplementation((method: string) => {
    if (method === "ai.engine.status") return Promise.resolve(engineStatus);
    if (method === "ai.model.list") return Promise.resolve(localModels);
    if (method === "ai.provider.list") return Promise.resolve(providers);
    if (method === "ai.provider.test") {
      return Promise.resolve({
        success: true,
        message: "连接成功。",
        models: ["deepseek-chat", "deepseek-reasoner"],
      });
    }
    if (method === "ai.provider.models") return Promise.resolve(discovery);
    if (method === "ai.provider.save") return Promise.resolve({});
    if (method === "ai.provider.activate") return Promise.resolve({});
    if (method === "ai.model.select") {
      return Promise.resolve({ ...localModels, selectedModel: "llama3:8b" });
    }
    throw new Error(`Unexpected request: ${method}`);
  });
}

describe("AiEnginePanel", () => {
  beforeEach(() => {
    requestMock.mockReset();
    mockIpc();
  });

  it("shows the engine status line, local model select and provider rows", async () => {
    renderPanel();

    expect(await screen.findByText("AI 引擎")).toBeInTheDocument();
    expect(await screen.findByText(/当前引擎：本地 Ollama · qwen2\.5:7b/)).toBeInTheDocument();
    const select = screen.getByLabelText("本地模型") as HTMLSelectElement;
    expect(select).toHaveValue("qwen2.5:7b");
    expect(await screen.findByText("DeepSeek")).toBeInTheDocument();
    expect(screen.getByText(/https:\/\/api\.deepseek\.com\/v1 · deepseek-chat/)).toBeInTheDocument();
  });

  it("creates a provider: the key stays write-only, test succeeds and save sends the credential", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "新建网络 AI 供应商" }));
    expect(await screen.findByRole("dialog", { name: "新建网络 AI 供应商" })).toBeInTheDocument();

    const keyInput = screen.getByLabelText("API Key");
    expect((keyInput as HTMLInputElement).type).toBe("password");

    fireEvent.change(screen.getByLabelText("显示名称"), { target: { value: "新建供应商" } });
    fireEvent.change(screen.getByLabelText("API 端点"), { target: { value: "https://api.example.com" } });
    fireEvent.change(screen.getByLabelText("模型名称"), { target: { value: "example-chat" } });
    fireEvent.change(keyInput, { target: { value: "sk-test" } });

    fireEvent.click(screen.getByRole("button", { name: "测试连接" }));
    expect(await screen.findByText("连接成功。")).toBeInTheDocument();

    // v3.10.0 HAJ-9：测试返回的可用模型进入 datalist 候选，模型名输入框下拉选择（替代旧点选 chips）。
    expect(modelOptions()).toEqual(expect.arrayContaining(["deepseek-chat", "deepseek-reasoner"]));

    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "ai.provider.save",
        expect.objectContaining({ displayName: "新建供应商", credential: "sk-test" }),
      ),
    );

    // v3.4.4 AIS-2 安全语义保持：密钥只在请求里出现，界面任何位置都不回显明文。
    expect(document.body.textContent).not.toContain("sk-test");
  });

  // v3.10.0 HAJ-9：「发现模型」——端点+密钥拉取真实模型列表，经 datalist 下拉选择或继续手填。
  it("discovers models: ai.provider.models gets the endpoint and typed key, options fill the datalist", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "新建网络 AI 供应商" }));
    fireEvent.change(screen.getByLabelText("API 端点"), { target: { value: "https://api.example.com" } });
    fireEvent.change(screen.getByLabelText("API Key"), { target: { value: "sk-test" } });

    fireEvent.click(screen.getByRole("button", { name: "发现模型" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("ai.provider.models", {
        id: "",
        endpoint: "https://api.example.com",
        credential: "sk-test",
      }),
    );
    expect(await screen.findByText(/发现 2 个模型，可在模型名称框下拉选择/)).toBeInTheDocument();
    expect(modelOptions()).toEqual(expect.arrayContaining(["example-chat", "example-mini"]));
  });

  it("surfaces the classified discovery failure message when the key is rejected", async () => {
    mockIpc({
      success: false,
      message: "认证失败，请检查 API Key。",
      models: [],
      errorCode: "AUTHENTICATION_FAILED",
    });
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "新建网络 AI 供应商" }));
    fireEvent.change(screen.getByLabelText("API 端点"), { target: { value: "https://api.example.com" } });

    fireEvent.click(screen.getByRole("button", { name: "发现模型" }));

    expect(await screen.findByText("认证失败，请检查 API Key。")).toBeInTheDocument();
    expect(screen.getByText("发现模型失败")).toBeInTheDocument();
    expect(modelOptions()).toEqual([]);
  });

  it("sends the stored profile id when discovering from the edit dialog with an empty key", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
    expect(await screen.findByRole("dialog", { name: "编辑网络 AI 供应商" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "发现模型" }));

    // 编辑已存供应商且密钥留空：Java 侧借用其 DPAPI 密钥，前端只带 id。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("ai.provider.models", {
        id: "deepseek",
        endpoint: "https://api.deepseek.com/v1",
        credential: "",
      }),
    );
  });

  it("switches the local model through ai.model.select", async () => {
    renderPanel();

    const select = (await screen.findByLabelText("本地模型")) as HTMLSelectElement;
    await waitFor(() => expect(select).toHaveValue("qwen2.5:7b"));
    fireEvent.change(select, { target: { value: "llama3:8b" } });

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("ai.model.select", { model: "llama3:8b" }),
    );
  });

  // v3.10.0 HAJ-8：服务商预设——选择即填，只需粘贴 API Key。
  it("offers the preset select: choosing DeepSeek fills name, endpoint, model and shows the key hint", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "新建网络 AI 供应商" }));
    expect(await screen.findByRole("dialog", { name: "新建网络 AI 供应商" })).toBeInTheDocument();
    expect(screen.getByLabelText("服务商预设")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("服务商预设"), { target: { value: "deepseek" } });

    expect((screen.getByLabelText("显示名称") as HTMLInputElement).value).toBe("DeepSeek");
    expect((screen.getByLabelText("API 端点") as HTMLInputElement).value).toBe("https://api.deepseek.com/v1");
    expect((screen.getByLabelText("模型名称") as HTMLInputElement).value).toBe("deepseek-chat");
    expect(screen.getByText(/申请 API Key：https:\/\/platform\.deepseek\.com\/api_keys/)).toBeInTheDocument();

    // 预设只负责填表：选中后手改端点不被覆盖。
    fireEvent.change(screen.getByLabelText("API 端点"), { target: { value: "https://api.deepseek.com/v2" } });
    expect((screen.getByLabelText("API 端点") as HTMLInputElement).value).toBe("https://api.deepseek.com/v2");
  });

  it("keeps the filled values and hides preset hints after switching to 自定义", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "新建网络 AI 供应商" }));
    const presetSelect = (await screen.findByLabelText("服务商预设")) as HTMLSelectElement;
    expect(presetSelect.value).toBe(CUSTOM_PRESET_ID);

    fireEvent.change(presetSelect, { target: { value: "deepseek" } });
    fireEvent.change(presetSelect, { target: { value: CUSTOM_PRESET_ID } });

    expect((screen.getByLabelText("显示名称") as HTMLInputElement).value).toBe("DeepSeek");
    expect((screen.getByLabelText("API 端点") as HTMLInputElement).value).toBe("https://api.deepseek.com/v1");
    expect((screen.getByLabelText("模型名称") as HTMLInputElement).value).toBe("deepseek-chat");
    expect(screen.queryByText(/申请 API Key：/)).not.toBeInTheDocument();
  });

  it("preselects the matching preset when editing an existing provider", async () => {
    renderPanel();

    fireEvent.click(await screen.findByRole("button", { name: "编辑" }));
    expect(await screen.findByRole("dialog", { name: "编辑网络 AI 供应商" })).toBeInTheDocument();

    expect((screen.getByLabelText("服务商预设") as HTMLSelectElement).value).toBe("deepseek");
    expect(screen.getByText(/申请 API Key：https:\/\/platform\.deepseek\.com\/api_keys/)).toBeInTheDocument();
  });
});
