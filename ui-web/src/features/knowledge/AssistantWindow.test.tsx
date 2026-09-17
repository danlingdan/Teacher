import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AssistantWindow from "./AssistantWindow";

// v3.4.4：知识助教独立子窗口——上下文来自 hash 查询参数，会话与引用在窗口内闭环。
const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
}));

function renderWindow(route = "/assistant-window?course=%E6%93%8D%E4%BD%9C%E7%B3%BB%E7%BB%9F&section=%E8%BF%9B%E7%A8%8B%E8%B0%83%E5%BA%A6&title=%E8%B0%83%E5%BA%A6%E7%AE%97%E6%B3%95%E6%A6%82%E8%BF%B0") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <AssistantWindow />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("AssistantWindow", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "ai.knowledge.ask") {
        return Promise.resolve({
          aiGenerated: true,
          model: "local-test",
          answer: "调度算法的解释。",
          citations: [
            {
              number: 1,
              documentId: "doc-1",
              articleTitle: "调度算法概述",
              revision: 2,
              chunkIndex: 0,
              snippet: "引用片段",
            },
          ],
          message: "",
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("shows the reading context carried from the main window", () => {
    renderWindow();

    expect(screen.getByText(/将结合正在阅读的资料：调度算法概述/)).toBeInTheDocument();
  });

  it("sends the reading context as structured fields and keeps the conversation", async () => {
    renderWindow();

    const input = screen.getByLabelText(/针对课程资料提问/);
    fireEvent.change(input, { target: { value: "第一个问题" } });
    fireEvent.click(screen.getByRole("button", { name: "生成有引用的解释" }));

    expect(await screen.findByText("第一个问题")).toBeInTheDocument();
    expect(await screen.findByText("调度算法的解释。")).toBeInTheDocument();

    // 第二轮可继续追问，历史保留。
    fireEvent.change(input, { target: { value: "第二个问题" } });
    fireEvent.click(screen.getByRole("button", { name: "生成有引用的解释" }));
    expect(await screen.findByText("第二个问题")).toBeInTheDocument();
    expect(screen.getByText("第一个问题")).toBeInTheDocument();

    // v3.6.0 KBF-1：问题文本保持纯净，阅读上下文经结构化字段随请求传递（Java 端做检索过滤）。
    await waitFor(() => {
      const asks = requestMock.mock.calls.filter((call) => call[0] === "ai.knowledge.ask");
      expect(asks).toHaveLength(2);
      expect(asks[0]?.[1]?.question).toBe("第一个问题");
      expect(asks[0]?.[1]?.context).toEqual({ courseTitle: "操作系统", sectionTitle: "进程调度" });
      expect(asks[1]?.[1]?.question).toBe("第二个问题");
      expect(asks[1]?.[1]?.context).toEqual({ courseTitle: "操作系统", sectionTitle: "进程调度" });
    });
    // 引用在子窗口中为可追溯文本（原文跳转在主窗口完成）；两轮各有一条相同引用。
    const citation = screen.getAllByText(/调度算法概述 第 2 版/)[0];
    if (!citation) throw new Error("引用未渲染");
    expect(citation.tagName).toBe("P");
  });

  it("clears the conversation on demand", async () => {
    renderWindow();

    const input = screen.getByLabelText(/针对课程资料提问/);
    fireEvent.change(input, { target: { value: "一个问题" } });
    fireEvent.click(screen.getByRole("button", { name: "生成有引用的解释" }));
    expect(await screen.findByText("一个问题")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "清空对话" }));
    expect(screen.queryByText("一个问题")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "生成有引用的解释" })).toBeDisabled();
  });
});
