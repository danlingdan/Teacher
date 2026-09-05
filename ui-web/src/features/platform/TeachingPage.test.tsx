import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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

  it("parses a preview before confirming text import", async () => {
    requestMock.mockImplementation(
      (method: string, params: Record<string, unknown>) => {
        if (method === "teaching.workspace")
          return Promise.resolve({
            role: "TEACHER",
            canPublish: true,
            authority: "java-and-cloud-server",
            exercises: [],
            progressOverview: {
              sessions: 0,
              attempts: 0,
              submissions: 0,
              passedSubmissions: 0,
              submissionPassRate: 0,
              averageSubmissionDuration: 0,
              hintsUsed: 0,
              completedExercises: 0,
            },
            progressItems: [],
            datasets: [],
          });
        if (method === "teaching.exercise.parse") {
          expect(String(params.text)).toContain("===[EXERCISE]===");
          return Promise.resolve({
            datasets: [{ id: "d1", name: "数据集" }],
            exercises: [
              {
                id: "e1",
                title: "预览题",
                knowledgePoint: "基础查询",
                difficulty: "BEGINNER",
              },
            ],
          });
        }
        if (method === "teaching.exercise.import") {
          expect(String(params.text)).toContain("===[EXERCISE]===");
          return Promise.resolve({
            datasetsImported: 0,
            exercisesImported: 1,
            importedExerciseIds: ["e1"],
          });
        }
        throw new Error(`Unexpected request: ${method}`);
      },
    );
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <TeachingPage />
      </QueryClientProvider>,
    );
    await screen.findByRole("button", { name: "解析预览" });

    const textarea = screen.getByLabelText("题库导入文字");
    fireEvent.change(textarea, {
      target: { value: "===[EXERCISE]===\nTITLE: 预览题" },
    });

    const importButton = screen.getByRole("button", { name: "导入题库包" });
    expect(importButton).toBeDisabled();

    fireEvent.click(screen.getByRole("button", { name: "解析预览" }));
    expect(await screen.findByText("导入预览")).toBeInTheDocument();
    expect(screen.getByText("预览题")).toBeInTheDocument();

    fireEvent.click(importButton);
    await waitFor(() => expect(textarea).toHaveValue(""));
  });

  it("replaces free text with the AI-generated DSL draft", async () => {
    requestMock.mockImplementation(
      (method: string, params: Record<string, unknown>) => {
        if (method === "teaching.workspace")
          return Promise.resolve({
            role: "TEACHER",
            canPublish: true,
            authority: "java-and-cloud-server",
            exercises: [],
            progressOverview: {
              sessions: 0,
              attempts: 0,
              submissions: 0,
              passedSubmissions: 0,
              submissionPassRate: 0,
              averageSubmissionDuration: 0,
              hintsUsed: 0,
              completedExercises: 0,
            },
            progressItems: [],
            datasets: [],
          });
        if (method === "teaching.exercise.draft") {
          expect(String(params.text)).toContain("查询学生");
          return Promise.resolve({
            text: "===[EXERCISE]===\nTITLE: 草稿题",
            model: "test-model",
          });
        }
        throw new Error(`Unexpected request: ${method}`);
      },
    );
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    render(
      <QueryClientProvider client={client}>
        <TeachingPage />
      </QueryClientProvider>,
    );
    await screen.findByRole("button", { name: "AI 解析" });

    const textarea = screen.getByLabelText("题库导入文字");
    fireEvent.change(textarea, { target: { value: "帮我出题：查询学生" } });

    fireEvent.click(screen.getByRole("button", { name: "AI 解析" }));
    await waitFor(() =>
      expect(textarea).toHaveValue("===[EXERCISE]===\nTITLE: 草稿题"),
    );
  });
});
