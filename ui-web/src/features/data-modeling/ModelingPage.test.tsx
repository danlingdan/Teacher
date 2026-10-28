import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import ModelingPage from "./ModelingPage";
import { subscribeAiEnginePanel } from "../data-sql/aiPanel";
import type { ErGenerationResult } from "./erModel";

// v3.11.0 ERM-6：建模页三态（成功/校验失败/引擎未就绪）与空值禁用。
// requestMock 模式与 DataSqlPage.test.tsx 一致；React Flow 在 jsdom 下需要
// ResizeObserver 桩（仅观察用），布局走真实 elkjs（小样例，毫秒级）。
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
(globalThis as { ResizeObserver?: unknown }).ResizeObserver ??= ResizeObserverStub;

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const okResult: ErGenerationResult = {
  status: "OK",
  model: "ollama:qwen2.5:7b",
  promptVersion: "er-modeling-v1",
  erModel: {
    title: "学生选课系统",
    explanation: "以学生、课程为中心，选修联系上记录成绩。",
    entities: [
      { id: "student", name: "学生", weak: false, attributes: [{ name: "学号", key: true }] },
      { id: "course", name: "课程", weak: false, attributes: [{ name: "课程号", key: true }] },
    ],
    relationships: [
      {
        id: "elective",
        name: "选修",
        identifying: false,
        members: [
          { entity: "student", cardinality: "n", total: false },
          { entity: "course", cardinality: "m", total: false },
        ],
        attributes: [{ name: "成绩" }],
      },
    ],
    hierarchies: [],
  },
  errors: [],
  message: "已生成学生选课 E-R 模型",
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/modeling"]}>
        <ModelingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const localEngineStatus = {
  networkActive: false,
  activeKind: "ollama",
  displayName: "本地 Ollama",
  selectedModel: "qwen2.5:7b",
  ollamaAvailable: true,
  ollamaModelCount: 1,
  message: "",
};
const networkEngineStatus = {
  ...localEngineStatus,
  networkActive: true,
  activeKind: "openai-compatible",
  displayName: "DeepSeek",
  selectedModel: "deepseek-chat",
};

describe("ModelingPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
  });

  it("keeps generation disabled while the requirement is blank", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    const generate = screen.getByRole("button", { name: "生成" });
    expect(generate).toBeDisabled();
    expect(await screen.findByText(/还没有 E-R 模型/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("建模需求"), { target: { value: "   " } });
    expect(generate).toBeDisabled();
    fireEvent.change(screen.getByLabelText("建模需求"), { target: { value: "设计学生选课系统" } });
    expect(generate).toBeEnabled();
    // 空态下不发出建模请求（引擎状态查询除外）；需求计数随输入更新。
    expect(requestMock).not.toHaveBeenCalledWith("ai.modeling.er.generate", expect.anything());
    expect(screen.getByText("8/2000")).toBeInTheDocument();
  });

  it("renders the laid-out canvas and toolbar on success", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      if (method === "ai.modeling.er.generate") return Promise.resolve(okResult);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.change(screen.getByLabelText("建模需求"), { target: { value: "设计学生选课系统" } });
    fireEvent.click(screen.getByRole("button", { name: "生成" }));

    expect(await screen.findByRole("heading", { name: "学生选课系统" })).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("ai.modeling.er.generate", {
      requirement: "设计学生选课系统",
    });
    // 画布出现陈氏记号图元文字：实体、联系与属性（含主码）标签。
    expect((await screen.findAllByText("学生")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("课程").length).toBeGreaterThan(0);
    expect(screen.getAllByText("选修").length).toBeGreaterThan(0);
    expect(screen.getAllByText("学号").length).toBeGreaterThan(0);
    expect(screen.getByText(/以学生、课程为中心/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "适应视图" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "导出 PNG" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "导出 SVG" })).toBeInTheDocument();
    // 基数/双线等边标记随边渲染，但 jsdom 无法完成 handleBounds 测量，
    // React Flow 在测量完成前不挂载任何边；边数据的记号断言在 erModel.test.ts 覆盖。
  });

  it("lists validation errors when the generated model fails deterministic checks", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      if (method === "ai.modeling.er.generate") {
        return Promise.resolve({
          status: "VALIDATION_FAILED",
          model: null,
          promptVersion: "er-modeling-v1",
          erModel: null,
          errors: ["联系成员必须引用已声明的实体", "基数取值必须在 1/n/m 内"],
          message: "AI 输出未通过确定性校验",
        } satisfies ErGenerationResult);
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.change(screen.getByLabelText("建模需求"), { target: { value: "设计学生选课系统" } });
    fireEvent.click(screen.getByRole("button", { name: "生成" }));

    expect(await screen.findByText("生成结果校验未通过")).toBeInTheDocument();
    expect(screen.getByText(/AI 输出未通过确定性校验/)).toBeInTheDocument();
    expect(screen.getByText("联系成员必须引用已声明的实体")).toBeInTheDocument();
    expect(screen.getByText("基数取值必须在 1/n/m 内")).toBeInTheDocument();
    // 失败不渲染画布，回到空态引导。
    expect(screen.getByText(/还没有 E-R 模型/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "导出 PNG" })).not.toBeInTheDocument();
  });

  it("guides to the topbar AI engine panel when the engine is unavailable", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      if (method === "ai.modeling.er.generate") {
        return Promise.resolve({
          status: "MODEL_UNAVAILABLE",
          model: null,
          promptVersion: "er-modeling-v1",
          erModel: null,
          errors: [],
          message: "未检测到可用的本地模型，也未配置网络 AI 供应商",
        } satisfies ErGenerationResult);
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.change(screen.getByLabelText("建模需求"), { target: { value: "设计学生选课系统" } });
    fireEvent.click(screen.getByRole("button", { name: "生成" }));

    expect(await screen.findByText("AI 引擎未就绪")).toBeInTheDocument();
    expect(screen.getByText(/未检测到可用的本地模型/)).toBeInTheDocument();

    // 与数据页一致的引导：按钮唤起顶栏「AI 引擎」弹层（事件总线先例）。
    const opened = vi.fn();
    const unsubscribe = subscribeAiEnginePanel(opened);
    fireEvent.click(screen.getByRole("button", { name: "打开 AI 引擎" }));
    expect(opened).toHaveBeenCalledTimes(1);
    unsubscribe();
    expect(screen.queryByRole("button", { name: "导出 PNG" })).not.toBeInTheDocument();
  });

  it("wording follows the active engine channel from ai.engine.status", async () => {
    // 网络引擎：chips 标注网络 AI，隐私提示如实说明需求会上传至服务商。
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(networkEngineStatus);
      throw new Error(`Unexpected request: ${method}`);
    });
    const view = renderPage();

    expect(await screen.findByText("陈氏记号 · 网络 AI")).toBeInTheDocument();
    expect(screen.getByText(/需求将上传至所选网络 AI 服务商生成/)).toBeInTheDocument();
    expect(screen.queryByText(/不上传云端/)).not.toBeInTheDocument();
    view.unmount();

    // 本地引擎：重新挂载后 chips 与提示回到仅本机处理口径。
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.engine.status") return Promise.resolve(localEngineStatus);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();
    expect(await screen.findByText("陈氏记号 · 本地 AI")).toBeInTheDocument();
    expect(screen.getByText(/需求仅经本机 AI 通道处理，不上传云端/)).toBeInTheDocument();
  });
});
