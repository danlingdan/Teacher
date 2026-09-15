import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgePage from "./KnowledgePage";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
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
          article: { title: "调度算法概述" },
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
    const summary = await screen.findByText(/进程调度/, { selector: "summary" });
    expect(summary.closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText(/1 篇文档/)).toBeInTheDocument();
    expect(requestMock).toHaveBeenCalledWith("knowledge.overview");
    expect(requestMock).not.toHaveBeenCalledWith("course.workspace");
  });

  it("opens a section document directly from the course tree", async () => {
    renderPage();

    fireEvent.click(
      await screen.findByText("调度算法概述", { selector: ".course-tree button" }),
    );

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("knowledge.article", {
        articleId: "article-1",
      }),
    );
  });

  it("renders every search result as an openable document", async () => {
    renderPage();

    await screen.findByText(/1 篇文档/);
    fireEvent.change(screen.getByLabelText(/检索课程知识/), { target: { value: "调度" } });

    // v3.4.1 KNW-2：检索结果不允许出现点不动的禁用项。
    const hit = (await screen.findByText("常见调度算法对比……")).closest("button");
    expect(hit).not.toBeNull();
    expect(hit).toBeEnabled();
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
        return Promise.resolve({ article: { title: "调度算法概述" }, markdown: "# x", sourceName: "os.md", revision: 2 });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    expect(await screen.findByText(/正在建立课程知识索引/)).toBeInTheDocument();
    // 检索框在索引未就绪时仍可用（FTS5 降级）。
    expect(screen.getByLabelText(/检索课程知识/)).toBeInTheDocument();
  });

  it("gives teachers bundle update and offline import controls", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "knowledge.overview")
        return Promise.resolve(
          overview({
            hasOfficialBundle: true,
            bundle: { bundleId: "official-db-concepts", version: "1.0.0", source: "BUILTIN", importedAt: "2026-09-15T00:00:00Z" },
          }),
        );
      if (method === "session.current") return Promise.resolve({ role: "TEACHER" });
      if (method === "knowledge.index.status")
        return Promise.resolve({ pendingJobs: 0, indexedChunks: 40, failedChunks: 0, mode: "HYBRID", message: "" });
      if (method === "knowledge.article")
        return Promise.resolve({ article: { title: "调度算法概述" }, markdown: "# x", sourceName: "os.md", revision: 2 });
      if (method === "knowledge.bundle.check")
        return Promise.resolve({
          cloudAvailable: true,
          updateAvailable: true,
          bundleId: "official-db-concepts",
          cloudVersion: "2.0.0",
          localVersion: "1.0.0",
          title: "数据库系统概念",
          sizeBytes: 22000000,
          message: "云端有更新版本，可下载更新。",
        });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    expect(await screen.findByText(/已安装 1.0.0 · 随包/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "检查更新" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("knowledge.bundle.check"),
    );
    expect(await screen.findByRole("button", { name: /下载并安装 2.0.0/ })).toBeInTheDocument();
  });
});
