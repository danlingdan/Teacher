import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
import TopbarAiEngine from "./TopbarAiEngine";
import { openAiEnginePanel, subscribeAiEnginePanel } from "./aiPanel";

// v3.10.0 HAJ-1 修订：AI 引擎独立顶栏按钮——chip 状态词（网络/本地/未就绪）、
// 弹层挂载 AiEnginePanel、以及数据页/练习页失败引导按钮的打开通道。
const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const localEngineStatus = {
  networkActive: false,
  activeKind: "OLLAMA",
  displayName: "本地 Ollama",
  selectedModel: "qwen2.5:7b",
  ollamaAvailable: true,
  ollamaModelCount: 2,
  message: "ok",
};
const localModels = { installedModels: ["qwen2.5:7b"], selectedModel: "qwen2.5:7b", message: "ok" };
const providersEmpty = { items: [], activeProfileId: "" };

function renderTopbarAi() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Toaster>
        <TopbarAiEngine />
      </Toaster>
    </QueryClientProvider>,
  );
}

describe("TopbarAiEngine", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      if (method === "ai.provider.list") return Promise.resolve(providersEmpty);
      if (method === "ai.model.list") return Promise.resolve(localModels);
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("renders the chip with the local-ready status and opens the engine popover", async () => {
    renderTopbarAi();

    // findByRole 按 accessible name 轮询：状态词由 ai.engine.status 异步写入 aria-label。
    const chip = await screen.findByRole("button", { name: "AI 引擎：本地" });
    expect(chip).not.toHaveClass("missing");
    expect(chip).toHaveTextContent("本地");

    fireEvent.click(chip);
    const dialog = await screen.findByRole("dialog", { name: "AI 引擎" });
    expect(dialog).toBeInTheDocument();
    // 弹层挂载的是 AiEnginePanel 本体：引擎状态行与供应商管理入口可见。
    // 头部 small 与面板自己的标题都带「当前引擎」句，故用 findAllByText。
    expect(
      (await screen.findAllByText(/当前引擎：本地 Ollama · qwen2\.5:7b/)).length,
    ).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "新建网络 AI 供应商" })).toBeInTheDocument();
  });

  it("marks the chip as missing when neither network AI nor local Ollama is ready", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") {
        return Promise.resolve({ ...localEngineStatus, ollamaAvailable: false, ollamaModelCount: 0 });
      }
      if (method === "ai.provider.list") return Promise.resolve(providersEmpty);
      if (method === "ai.model.list") return Promise.resolve(localModels);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbarAi();

    // findByRole 按 accessible name 轮询：未就绪状态由 ai.engine.status 异步写入。
    const chip = await screen.findByRole("button", { name: "AI 引擎：未就绪" });
    expect(chip).toHaveClass("missing");
    expect(chip).toHaveTextContent("未就绪");
  });

  it("opens the popover when data or exercise pages request the shared panel", async () => {
    renderTopbarAi();
    const opened = vi.fn();
    const unsubscribe = subscribeAiEnginePanel(opened);

    act(() => {
      openAiEnginePanel();
    });
    expect(await screen.findByRole("dialog", { name: "AI 引擎" })).toBeInTheDocument();
    unsubscribe();
  });
});
