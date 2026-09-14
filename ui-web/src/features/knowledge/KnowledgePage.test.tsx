import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgePage from "./KnowledgePage";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

const workspacePayload = {
  courses: [
    {
      id: "course-1",
      title: "操作系统",
      version: "1",
      sections: [
        {
          id: "section-1",
          title: "进程调度",
          sortOrder: 1,
          activities: [
            {
              id: "activity-1",
              title: "短作业优先调度",
              type: "SIMULATION",
              difficulty: "BEGINNER",
              estimatedMinutes: 12,
              enabled: true,
              knowledgePoints: [],
            },
          ],
        },
      ],
    },
  ],
  articles: [
    {
      id: "article-1",
      courseTitle: "操作系统",
      sectionTitle: "进程调度",
      title: "调度算法概述",
      visibility: "PUBLISHED",
      currentRevision: 2,
      knowledgePoints: [],
      contentHash: "hash-1",
      updatedAt: "2026-09-01T00:00:00Z",
    },
  ],
  articleCount: 1,
};

describe("KnowledgePage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "course.workspace") {
        return Promise.resolve(workspacePayload);
      }
      if (method === "session.current") {
        return Promise.resolve({ role: "STUDENT" });
      }
      if (method === "knowledge.index.status") {
        return Promise.resolve({
          pendingJobs: 0,
          indexedChunks: 0,
          failedChunks: 0,
          mode: "LOCAL",
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

  it("keeps course sections collapsed and never renders activities", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <KnowledgePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    const summary = await screen.findByText(/进程调度/, { selector: "summary" });
    expect(summary.closest("details")).not.toHaveAttribute("open");
    // v3.4.1 KNW-1：课程树只承载知识，活动条目不再平铺在知识页。
    expect(screen.queryByText("短作业优先调度")).not.toBeInTheDocument();
    expect(screen.getByText(/1 篇文档/)).toBeInTheDocument();
  });

  it("opens a section document directly from the course tree", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <KnowledgePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

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
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <KnowledgePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText(/1 篇文档/);
    fireEvent.change(screen.getByLabelText(/检索课程知识/), { target: { value: "调度" } });

    // v3.4.1 KNW-2：检索结果不允许出现点不动的禁用项。
    const hit = (await screen.findByText("常见调度算法对比……")).closest("button");
    expect(hit).not.toBeNull();
    expect(hit).toBeEnabled();
  });
});
