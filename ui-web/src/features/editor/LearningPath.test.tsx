import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { findRelatedArticle, LearningPathPanel } from "./LearningPath";
import type { LearningPathView } from "../../shared/types";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const path: LearningPathView = {
  id: "core-path-v1",
  name: "SQL 查询学习路径",
  version: 1,
  chapters: [
    {
      order: 1,
      title: "入门查询",
      knowledgeTags: ["基础查询", "选择列"],
      exercises: [
        { exerciseId: "query-01", title: "查询全部学生", knowledgePoint: "基础查询", attempts: 2, passed: true, masteryPercent: 90 },
        { exerciseId: "query-02", title: "选择列", knowledgePoint: "选择列", attempts: 0, passed: false, masteryPercent: null },
      ],
    },
  ],
};

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <LearningPathPanel onSelect={() => undefined} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("LearningPathPanel", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "practice.paths") return Promise.resolve({ items: [path] });
      if (method === "knowledge.overview") {
        return Promise.resolve({
          articleCount: 1,
          articles: [
            {
              id: "art-1",
              title: "关系模型",
              courseTitle: "数据库系统",
              sectionTitle: "基础查询",
              visibility: "PUBLISHED",
              currentRevision: 1,
              knowledgePoints: ["选择列"],
              contentHash: "h",
              updatedAt: "2026-09-01T00:00:00Z",
            },
          ],
          hasOfficialBundle: false,
          bundle: null,
          index: { pendingJobs: 0, indexedChunks: 0, failedChunks: 0, mode: "LOCAL", message: "" },
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("renders chapter groups with status badges and selects through clicks (EPATH-2)", async () => {
    renderPanel();

    fireEvent.click(await screen.findByText("学习路径"));
    expect(await screen.findByText(/第 1 章 · 入门查询/)).toBeInTheDocument();
    expect(screen.getByText("1/2 已通过")).toBeInTheDocument();
    expect(screen.getByText(/已通过 · 掌握 90%/)).toBeInTheDocument();
    expect(screen.getByText("未开始")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /查询全部学生/ }));
  });

  it("links the related knowledge article by name mapping (EPATH-3)", async () => {
    renderPanel();

    const link = await screen.findByRole("link", { name: /相关阅读：关系模型/ });
    expect(link.getAttribute("href")).toBe("/knowledge?article=art-1");
  });

  it("hides the whole panel when the bank ships no paths (EPATH-2 fallback)", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "practice.paths") return Promise.resolve({ items: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    const { container } = renderPanel();

    await waitFor(() => expect(requestMock).toHaveBeenCalledWith("practice.paths", {}));
    await waitFor(() => expect(container.querySelector(".learning-path")).toBeNull());
    // 无路径时不应额外请求知识文章。
    expect(requestMock).not.toHaveBeenCalledWith("knowledge.overview");
  });
});

describe("findRelatedArticle", () => {
  const articles = [
    { id: "a1", title: "A", sectionTitle: "聚合与分组", knowledgePoints: ["HAVING"] },
    { id: "a2", title: "B", sectionTitle: "连接查询", knowledgePoints: [] },
  ];

  it("按章节名命中", () => {
    expect(findRelatedArticle(articles, ["连接查询"])?.id).toBe("a2");
  });
  it("按文章知识点命中", () => {
    expect(findRelatedArticle(articles, ["HAVING"])?.id).toBe("a1");
  });
  it("未命中返回空", () => {
    expect(findRelatedArticle(articles, ["不存在"])).toBeUndefined();
    expect(findRelatedArticle(articles, [])).toBeUndefined();
  });
});
