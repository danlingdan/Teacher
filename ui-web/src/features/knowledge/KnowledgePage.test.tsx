import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgePage from "./KnowledgePage";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

describe("KnowledgePage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "course.workspace") {
        return Promise.resolve({
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
          articles: [],
          articleCount: 0,
        });
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
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("keeps course sections collapsed until the user opens them", async () => {
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

    const summary = await screen.findByText("进程调度");
    expect(summary.closest("details")).not.toHaveAttribute("open");
  });
});
