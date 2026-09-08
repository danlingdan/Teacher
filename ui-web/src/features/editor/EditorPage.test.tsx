import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useEffect, useRef } from "react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { OnMount } from "@monaco-editor/react";
import EditorPage from "./EditorPage";

// 观察并手动触发 Monaco 挂载时注册的快捷键命令。
const monacoTest = vi.hoisted(() => ({
  commands: new Map<number, () => void>(),
  latest: {
    onChange: undefined as ((value: string | undefined) => void) | undefined,
  },
}));

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
  subscribeLocalAppEvents: vi.fn(() => Promise.resolve(() => undefined)),
}));

vi.mock("monaco-editor/editor/editor.api", () => {
  const languages = {
    registerCompletionItemProvider: vi.fn(),
    CompletionItemKind: { Field: 0, Snippet: 1 },
    CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
  };
  class Range {}
  return {
    languages,
    Range,
    editor: {},
    KeyMod: {},
    KeyCode: {},
    MarkerSeverity: {},
    default: { languages, Range },
  };
});
vi.mock("monaco-editor/editor/editor.worker?worker", () => ({
  default: class {},
}));
vi.mock("monaco-editor/languages/definitions/sql/register", () => ({}));
vi.mock("monaco-editor/languages/definitions/java/register", () => ({}));
vi.mock("monaco-editor/languages/definitions/python/register", () => ({}));
vi.mock("monaco-editor/languages/definitions/cpp/register", () => ({}));

type MonacoMockProps = {
  onMount?: OnMount;
  onChange?: (value: string | undefined) => void;
  height?: string;
  language?: string;
  path?: string;
  value?: string;
  options?: Record<string, unknown>;
};

// 与 CodeEditor.onMount 的键位一致：CtrlCmd=1、Shift=2、Enter=16、F1=32。
const KEY_CTRL_ENTER = 1 | 16;
const KEY_CTRL_SHIFT_ENTER = 1 | 2 | 16;
const KEY_F1 = 32;

vi.mock("@monaco-editor/react", () => ({
  loader: { config: vi.fn() },
  default: (props: MonacoMockProps) => {
    const mountedRef = useRef(false);
    monacoTest.latest.onChange = props.onChange;
    useEffect(() => {
      if (mountedRef.current) return;
      mountedRef.current = true;
      const editor = {
        addCommand: (keycode: number, handler: () => void) => {
          monacoTest.commands.set(keycode, handler);
        },
        onDidChangeModelContent: vi.fn(),
        getModel: () => ({ getValue: () => "" }),
      };
      const api = {
        KeyMod: { CtrlCmd: 1, Shift: 2 },
        KeyCode: { Enter: 16, F1: 32 },
        MarkerSeverity: { Warning: 8 },
        editor: { setModelMarkers: vi.fn() },
      };
      const mount = props.onMount;
      if (mount) (mount as (editor: unknown, api: unknown) => void)(editor, api);
    }, []);
    return <div data-testid="monaco-mock" />;
  },
}));

const exerciseView = {
  id: "e1",
  title: "查询练习",
  knowledgePoint: "基础查询",
  difficulty: "BEGINNER",
  version: 1,
  enabled: true,
  attempts: 0,
  passed: false,
  lastAttemptAt: null,
  description: "返回全部学生",
  schemaSummary: "student(id, name)",
};

function renderEditorPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/practice"]}>
        <EditorPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function openSession() {
  renderEditorPage();
  fireEvent.click(await screen.findByRole("button", { name: /查询练习/ }));
  fireEvent.click(
    await screen.findByRole("button", { name: "确认并开始作答" }),
  );
  await screen.findByText(/Ctrl\+Enter 运行/);
  expect(monacoTest.commands.get(KEY_CTRL_ENTER)).toBeDefined();
}

describe("EditorPage CodeEditor shortcuts", () => {
  beforeEach(() => {
    monacoTest.commands.clear();
    monacoTest.latest.onChange = undefined;
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "practice.catalog")
        return Promise.resolve({ items: [exerciseView] });
      if (method === "practice.preview") return Promise.resolve(exerciseView);
      if (method === "practice.start")
        return Promise.resolve({
          id: "session-1",
          exercise: exerciseView,
          startedAt: "2026-09-09T00:00:00Z",
          hintsUsed: 0,
          completed: false,
        });
      if (method === "practice.run")
        return Promise.resolve({
          attemptId: "a-run",
          sessionId: "session-1",
          status: "RUN",
          occurredAt: "2026-09-09T00:00:01Z",
        });
      if (method === "practice.submit")
        return Promise.resolve({
          attemptId: "a-submit",
          sessionId: "session-1",
          status: "PASSED",
          evaluation: { passed: true, feedback: "通过", errorCode: "", criteria: [] },
          occurredAt: "2026-09-09T00:00:02Z",
        });
      if (method === "practice.hint")
        return Promise.resolve({
          level: 3,
          text: "参考 SELECT 语句",
          exhausted: true,
        });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
  });

  it("runs and submits the latest answer when shortcut commands fire after edits", async () => {
    await openSession();

    // 挂载后修改作答内容；快捷键必须调用最新回调提交当前代码，而不是挂载帧的旧代码。
    await act(async () => {
      monacoTest.latest.onChange?.("SELECT name FROM student WHERE id = 1;");
    });
    await act(async () => {
      monacoTest.commands.get(KEY_CTRL_ENTER)?.();
    });
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("practice.run", {
        sessionId: "session-1",
        answer: "SELECT name FROM student WHERE id = 1;",
      }),
    );

    await act(async () => {
      monacoTest.commands.get(KEY_CTRL_SHIFT_ENTER)?.();
    });
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("practice.submit", {
        sessionId: "session-1",
        answer: "SELECT name FROM student WHERE id = 1;",
      }),
    );
  });

  it("submits the freshly edited source when a CODE activity runs via Ctrl+Enter", async () => {
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "course.workspace")
        return Promise.resolve({
          courses: [
            {
              id: "c1",
              title: "程序设计",
              version: "1",
              sections: [
                {
                  id: "s1",
                  title: "入门",
                  sortOrder: 1,
                  activities: [
                    {
                      id: "act-1",
                      title: "编码活动",
                      type: "CODE",
                      difficulty: "BEGINNER",
                      estimatedMinutes: 5,
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
      if (method === "activity.definition") {
        expect(String(params?.activityId)).toBe("act-1");
        return Promise.resolve({
          id: "act-1",
          courseId: "c1",
          sectionId: "s1",
          title: "编码活动",
          description: "编写并运行代码",
          knowledgePointIds: [],
          difficulty: "BEGINNER",
          estimatedMinutes: 5,
          version: 1,
          enabled: true,
          type: "CODE",
          nextSubmissionVersion: 1,
          specification: { language: "PYTHON", starterCode: "" },
        });
      }
      if (method === "activity.submit") return Promise.resolve({
        sessionId: "s-act",
        evaluationId: "ev-1",
        occurredAt: "2026-09-09T00:00:00Z",
        evaluation: {
          status: "PASSED",
          passed: true,
          summary: "通过",
          reasonCode: "OK",
          criteria: [],
        },
      });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderEditorPage();

    fireEvent.click(screen.getByRole("button", { name: "课程活动" }));
    fireEvent.click(await screen.findByRole("button", { name: /编码活动/ }));
    fireEvent.click(
      await screen.findByRole("button", { name: "确认并开始活动" }),
    );
    expect(await screen.findByText(/运行并评价/)).toBeInTheDocument();
    expect(monacoTest.commands.get(KEY_CTRL_ENTER)).toBeDefined();

    // 挂载后在编辑器中输入代码；Ctrl+Enter 必须提交最新源码，而非挂载帧的 starterCode。
    await act(async () => {
      monacoTest.latest.onChange?.("print(42)");
    });
    await act(async () => {
      monacoTest.commands.get(KEY_CTRL_ENTER)?.();
    });

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "activity.submit",
        expect.objectContaining({
          activityId: "act-1",
          artifact: { language: "PYTHON", sourceCode: "print(42)" },
        }),
      ),
    );
  });

  it("stops F1 hints once the hint budget is exhausted", async () => {
    await openSession();

    await act(async () => {
      monacoTest.commands.get(KEY_F1)?.();
    });
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("practice.hint", {
        sessionId: "session-1",
      }),
    );
    expect(await screen.findByText("第 3 级提示")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "获取提示" })).toBeDisabled();

    // 提示用尽后 F1 不得绕过按钮的禁用状态再次请求。
    requestMock.mockClear();
    await act(async () => {
      monacoTest.commands.get(KEY_F1)?.();
    });
    expect(requestMock).not.toHaveBeenCalled();
  });
});
