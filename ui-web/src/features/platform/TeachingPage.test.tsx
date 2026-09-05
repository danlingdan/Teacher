import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { TeachingPage } from "./PlatformPages";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

describe("TeachingPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method !== "teaching.workspace")
        throw new Error(`Unexpected request: ${method}`);
      return Promise.resolve({
        role: "TEACHER",
        canPublish: true,
        authority: "java-and-cloud-server",
        exercises: [],
        progressOverview: {
          sessions: 17,
          attempts: 17,
          submissions: 17,
          passedSubmissions: 0,
          submissionPassRate: 0,
          averageSubmissionDuration: 0,
          hintsUsed: 0,
          completedExercises: 0,
        },
        progressItems: Array.from({ length: 17 }, (_, index) => ({
          exerciseId: `exercise-${index + 1}`,
          title: `进度题目 ${index + 1}`,
          knowledgePoint: "分页",
          attempts: index + 1,
          failedSubmissions: 0,
          passed: false,
        })),
        datasets: [],
      });
    });
  });

  it("paginates long learning progress lists", async () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <TeachingPage />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("进度题目 1")).toBeInTheDocument();
    expect(screen.getByText("第 1 / 3 页")).toBeInTheDocument();
    expect(screen.queryByText("进度题目 9")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByText("进度题目 9")).toBeInTheDocument();
    expect(screen.queryByText("进度题目 1")).not.toBeInTheDocument();
    expect(screen.getByText("第 2 / 3 页")).toBeInTheDocument();
  });
});
