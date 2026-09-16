import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgePage from "./KnowledgePage";

// v3.4.4 KUI：检索高亮/归属/计数、助教会话与可点击引用、阅读头部、教师管理收拢。
const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
}));

const webviewWindowMock = vi.fn();
vi.mock("@tauri-apps/api/webviewWindow", () => ({
  WebviewWindow: class {
    constructor(label: string, options: Record<string, unknown>) {
      webviewWindowMock(label, options);
    }
    once() {}
  },
}));

const article1 = {
  id: "article-1",
  courseTitle: "操作系统",
  sectionTitle: "进程调度",
  title: "调度算法概述",
  visibility: "PUBLISHED",
  currentRevision: 2,
  knowledgePoints: [],
  contentHash: "hash-1",
  updatedAt: "2026-09-01T00:00:00Z",
};

function overview(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    articleCount: 1,
    articles: [article1],
    hasOfficialBundle: false,
    bundle: null,
    index: { pendingJobs: 0, indexedChunks: 0, failedChunks: 0, mode: "HYBRID", message: "ready" },
    ...overrides,
  };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <KnowledgePage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("KnowledgePage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview") return Promise.resolve(overview());
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status") {
        return Promise.resolve({
          pendingJobs: 0,
          indexedChunks: 0,
          failedChunks: 0,
          mode: "HYBRID",
          message: "ready",
        });
      }
      if (method === "knowledge.article") {
        return Promise.resolve({
          article: article1,
          markdown: "# 调度算法概述",
          sourceName: "os.md",
          revision: 2,
        });
      }
      if (method === "knowledge.search") {
        return Promise.resolve({
          items: [
            {
              articleId: "article-1",
              documentId: "doc-1",
              title: "调度算法概述",
              sourceName: "os.md",
              chunkIndex: 0,
              snippet: "常见调度算法对比……",
              relevance: 0.92,
            },
          ],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("builds the course tree from articles and never renders activities", async () => {
    renderPage();

    // v3.4.3 KSR-2：课程树由文章自身的 courseTitle/sectionTitle 归组，overview 根本不含活动。
    // v3.5.3：树默认收起（多库并存的目录很长，默认展开没法找），点击章节逐级展开。
    const summary = await screen.findByText(/进程调度/, { selector: "summary" });
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText(/1 篇文档/)).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("knowledge.overview");
    expect(requestMock).not.toHaveBeenCalledWith("course.workspace");
  });

  it("opens a section document directly from the course tree and shows the reading header", async () => {
    renderPage();

    fireEvent.click(
      await screen.findByText("调度算法概述", { selector: ".course-tree button" }),
    );

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("knowledge.article", {
        articleId: "article-1",
      }),
    );
    // v3.4.4 KUI-4：文档头部展示标题与课程/章节/版本。
    expect(await screen.findByText("调度算法概述", { selector: "h2" })).toBeInTheDocument();
    expect(screen.getByText(/操作系统 · 进程调度 · 第 2 版/)).toBeInTheDocument();
  });

  it("renders every search result as an openable document with origin labels", async () => {
    renderPage();

    await screen.findByText(/1 篇文档/);
    fireEvent.change(screen.getByLabelText(/检索课程知识/), { target: { value: "调度" } });

    // v3.4.4 KUI-2：snippet 按命中词分片高亮，命中仍是可点开的文档按钮。
    const mark = await screen.findByText("调度", { selector: "mark" });
    const hit = mark.closest("button");
    if (!hit) throw new Error("搜索结果未渲染为按钮");
    expect(hit).toBeEnabled();
    // v3.4.4 KUI-2：结果标注所属课程/章节（来自 overview 映射，不加 IPC）。
    expect(screen.getByText(/来自 操作系统 · 进程调度/)).toBeInTheDocument();
  });

  it("highlights query hits safely and offers a result count with clear", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview") return Promise.resolve(overview());
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 0, indexedChunks: 0, failedChunks: 0, mode: "HYBRID", message: "" });
      if (method === "knowledge.search") {
        return Promise.resolve({
          items: [
            {
              articleId: "article-1",
              documentId: "doc-1",
              title: "调度算法概述",
              sourceName: "os.md",
              chunkIndex: 0,
              snippet: "常见<script>alert(1)</script>调度算法对比……",
              relevance: 0.92,
            },
          ],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    const { container } = renderPage();

    await screen.findByText(/1 篇文档/);
    fireEvent.change(screen.getByLabelText(/检索课程知识/), { target: { value: "调度" } });

    // v3.4.4 KUI-2：命中词高亮为 <mark>，snippet 按不可信文本渲染——不允许出现真实 script 元素。
    const mark = await screen.findByText("调度", { selector: "mark" });
    expect(mark).toBeInTheDocument();
    expect(container.querySelector("script")).toBeNull();
    expect(screen.getByText("共 1 条结果")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "清除" }));
    expect((screen.getByLabelText(/检索课程知识/) as HTMLInputElement).value).toBe("");
  });

  it("guides to reword when nothing matches", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview") return Promise.resolve(overview());
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 0, indexedChunks: 0, failedChunks: 0, mode: "HYBRID", message: "" });
      if (method === "knowledge.search") return Promise.resolve({ items: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    await screen.findByText(/1 篇文档/);
    fireEvent.change(screen.getByLabelText(/检索课程知识/), { target: { value: "不存在的词" } });

    expect(await screen.findByText(/没有匹配“不存在的词”的内容/)).toBeInTheDocument();
    expect(screen.getByText(/换个关键词试试/)).toBeInTheDocument();
  });

  it("opens the assistant child window with the reading context", async () => {
    renderPage();

    fireEvent.click(
      await screen.findByText("调度算法概述", { selector: ".course-tree button" }),
    );

    // v3.4.4：阅读区内不再嵌入助教表单；「知识助教」按钮拉起独立子窗口并携带上下文。
    const button = await screen.findByRole("button", { name: "知识助教" });
    expect(screen.queryByLabelText(/针对课程资料提问/)).not.toBeInTheDocument();
    fireEvent.click(button);

    await waitFor(() => expect(webviewWindowMock).toHaveBeenCalledTimes(1));
    const [label, options] = webviewWindowMock.mock.calls[0] as [string, { url: string }];
    expect(label).toMatch(/^assistant-/);
    expect(options.url).toContain("/#/assistant-window?");
    expect(options.url).toContain("course=%E6%93%8D%E4%BD%9C%E7%B3%BB%E7%BB%9F");
    expect(options.url).toContain("title=%E8%B0%83%E5%BA%A6%E7%AE%97%E6%B3%95%E6%A6%82%E8%BF%B0");
  });

  it("hides search and assistant and shows guidance when the library is empty", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview")
        return Promise.resolve(overview({ articleCount: 0, articles: [] }));
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 0, indexedChunks: 0, failedChunks: 0, mode: "FTS5", message: "" });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    // v3.4.3 KSR-2：空库不再显示检索框与知识助教，改为引导。
    expect(await screen.findByText(/课程知识库还是空的/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/检索课程知识/)).not.toBeInTheDocument();
    expect(screen.queryByText("知识助教")).not.toBeInTheDocument();
    // 学生看不到下载/导入按钮，只有提示联系教师。
    expect(screen.queryByRole("button", { name: "检查官方知识库" })).not.toBeInTheDocument();
    expect(screen.getByText(/联系任课教师/)).toBeInTheDocument();
  });

  it("shows an indexing notice while vector jobs are pending", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview")
        return Promise.resolve(
          overview({ index: { pendingJobs: 4, indexedChunks: 10, failedChunks: 0, mode: "FTS5", message: "" } }),
        );
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 4, indexedChunks: 10, failedChunks: 0, mode: "FTS5", message: "" });
      if (method === "knowledge.article")
        return Promise.resolve({ article: article1, markdown: "# x", sourceName: "os.md", revision: 2 });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    expect(await screen.findByText(/正在建立课程知识索引/)).toBeInTheDocument();
    // 检索框在索引未就绪时仍可用（FTS5 降级）。
    expect(screen.getByLabelText(/检索课程知识/)).toBeInTheDocument();
  });

  it("offers knowledge-bundle update and manual import to every role in the sidebar", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview")
        return Promise.resolve(
          overview({
            hasOfficialBundle: true,
            bundle: { bundleId: "official-db-concepts", version: "1.0.0", source: "BUILTIN", importedAt: "2026-09-15T00:00:00Z" },
            bundles: [
              { bundleId: "official-db-concepts", version: "1.0.0", source: "builtin" },
              { bundleId: "thomas-calculus", version: "1.1.0", source: "builtin" },
            ],
          }),
        );
      if (method === "session.current") return Promise.resolve({ role: "STUDENT" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 0, indexedChunks: 40, failedChunks: 0, mode: "HYBRID", message: "" });
      if (method === "knowledge.article")
        return Promise.resolve({ article: article1, markdown: "# x", sourceName: "os.md", revision: 2 });
      if (method === "knowledge.bundle.check")
        return Promise.resolve({
          cloudAvailable: true,
          updateAvailable: true,
          bundleId: "official-db-concepts",
          cloudVersion: "1.0.1",
          localVersion: "1.0.0",
          title: "数据库系统概念",
          sizeBytes: 22000000,
          message: "云端有更新版本，可下载更新。",
        });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    // v3.4.4：知识库更新入口对全角色开放（学生可自查云端更新或手动导入）。
    fireEvent.click(await screen.findByText(/知识库更新/, { selector: "summary" }));
    expect(await screen.findByText(/已安装 2 个知识库/)).toBeInTheDocument();
    // 面板逐包列出名称与版本，而不是只显示官方通道的第一个状态。
    expect((await screen.findAllByText("托马斯微积分")).length).toBeGreaterThan(0);
    expect(await screen.findByText(/v1.1.0 · 内置/)).toBeInTheDocument();
    expect((await screen.findAllByText(/数据库系统概念/)).length).toBeGreaterThan(0);
    fireEvent.click(screen.getByRole("button", { name: "检查云端更新" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("knowledge.bundle.check"),
    );
    expect(await screen.findByRole("button", { name: /下载并安装 1.0.1/ })).toBeInTheDocument();
  });
});
