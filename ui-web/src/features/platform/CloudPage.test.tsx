import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
import { CloudPage } from "./PlatformPages";
import type { CloudAssignment, CloudWorkspace } from "../../shared/types";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => {
  // 与真实 LocalAppError 同形的轻量替身：cloudFailureText 依赖 instanceof 判定错误码。
  class LocalAppError extends Error {
    code: string;
    retryable: boolean;
    constructor(code: string, message: string, retryable = false) {
      super(message);
      this.name = "LocalAppError";
      this.code = code;
      this.retryable = retryable;
    }
  }
  return {
    localAppRequest: (...args: unknown[]) => requestMock(...args),
    LocalAppError,
  };
});

function teacherWorkspace(classes: CloudWorkspace["classes"]): CloudWorkspace {
  return {
    signedIn: true,
    state: "READY",
    message: "云端就绪",
    displayName: "王老师",
    role: "TEACHER",
    recoverable: true,
    sync: { state: "SYNCED", pending: 0, attempt: 0 },
    classes,
  };
}

function studentWorkspace(): CloudWorkspace {
  return {
    signedIn: true,
    state: "READY",
    message: "云端就绪",
    displayName: "李学生",
    role: "STUDENT",
    recoverable: true,
    sync: { state: "SYNCED", pending: 0, attempt: 0 },
    classes: [],
  };
}

function renderCloudPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Toaster>
          <CloudPage />
        </Toaster>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("CloudPage", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace") return Promise.resolve(teacherWorkspace([]));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
  });

  it("surfaces an error toast when adding a section fails instead of failing silently", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace") return Promise.resolve(teacherWorkspace([]));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.courses")
        return Promise.resolve({
          items: [
            {
              id: "course-1",
              name: "数据库基础",
              description: "",
              status: "ACTIVE",
              version: 1,
              createdBy: "t1",
              createdAt: "2026-01-01T00:00:00Z",
              updatedAt: "2026-01-01T00:00:00Z",
            },
          ],
          cached: false,
        });
      if (method === "cloud.course.content")
        return Promise.resolve({
          sections: [],
          knowledgePoints: [],
          exercises: [],
          cached: false,
        });
      if (method === "cloud.course.section.create")
        return Promise.reject(new Error("云端连接中断"));
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByText("共享课程、知识点与版本化任务"));
    fireEvent.click(await screen.findByRole("button", { name: "刷新课程" }));
    await screen.findByText("数据库基础");
    fireEvent.click(screen.getByRole("button", { name: "打开课程" }));
    const sectionInput = await screen.findByLabelText("新章节");
    fireEvent.change(sectionInput, { target: { value: "第一章 SELECT" } });

    fireEvent.click(screen.getByRole("button", { name: "添加章节" }));

    expect(await screen.findByText(/章节添加失败/)).toBeInTheDocument();
    expect(screen.getByText(/云端连接中断/)).toBeInTheDocument();
    expect(sectionInput).toHaveValue("第一章 SELECT");
  });

  it("auto-selects the freshly created class so the assignment card opens", async () => {
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "cloud.workspace") {
        if (params?.refreshRemote)
          return Promise.resolve(
            teacherWorkspace([
              {
                id: "class-2",
                name: "软件2401",
                createdAt: "2026-09-08T00:00:00Z",
                members: [],
              },
            ]),
          );
        return Promise.resolve(teacherWorkspace([]));
      }
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.class.create") {
        expect(String(params?.name)).toBe("软件2401");
        return Promise.resolve({ classroom: { id: "class-2" }, role: "TEACHER" });
      }
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    const nameInput = await screen.findByLabelText("新班级名称");
    fireEvent.change(nameInput, { target: { value: "软件2401" } });
    fireEvent.click(screen.getByRole("button", { name: "创建班级" }));

    expect(await screen.findByText(/已创建/)).toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { level: 2, name: "班级任务：软件2401" }),
      ).toBeInTheDocument(),
    );
    const selectedItem = screen
      .getAllByText("软件2401")
      .map((element) => element.closest("li"))
      .find(Boolean);
    expect(selectedItem).not.toBeNull();
    expect(selectedItem).toHaveClass("selected");
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "cloud.assignments",
        expect.objectContaining({ classroomId: "class-2" }),
      ),
    );
  });

  it("does not add a second “班级已刷新” toast when creation auto-refreshes", async () => {
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "cloud.workspace") {
        if (params?.refreshRemote)
          return Promise.resolve(
            teacherWorkspace([
              {
                id: "class-2",
                name: "软件2401",
                createdAt: "2026-09-08T00:00:00Z",
                members: [],
              },
            ]),
          );
        return Promise.resolve(teacherWorkspace([]));
      }
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.class.create")
        return Promise.resolve({ classroom: { id: "class-2" }, role: "TEACHER" });
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    const nameInput = await screen.findByLabelText("新班级名称");
    fireEvent.change(nameInput, { target: { value: "软件2401" } });
    fireEvent.click(screen.getByRole("button", { name: "创建班级" }));

    expect(await screen.findByText(/已自动选中/)).toBeInTheDocument();
    // 创建班级确实触发了自动刷新（标题来自刷新后的工作区数据）。
    await waitFor(() =>
      expect(
        screen.getByRole("heading", { level: 2, name: "班级任务：软件2401" }),
      ).toBeInTheDocument(),
    );
    // 但自动刷新那次不弹“班级已刷新”，只保留创建成功这一条 toast。
    expect(screen.queryByText("班级已刷新")).not.toBeInTheDocument();
    expect(document.querySelectorAll(".ui-toaster .ui-toast")).toHaveLength(1);
  });

  it("drops a stale assignment response when the classroom switches in flight", async () => {
    let resolveClassA: (value: { items: CloudAssignment[] }) => void = () => undefined;
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "cloud.workspace")
        return Promise.resolve(
          teacherWorkspace([
            {
              id: "class-a",
              name: "甲班",
              createdAt: "2026-09-01T00:00:00Z",
              members: [],
            },
            {
              id: "class-b",
              name: "乙班",
              createdAt: "2026-09-02T00:00:00Z",
              members: [],
            },
          ]),
        );
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments") {
        const requested = String(params?.classroomId);
        if (requested === "class-a") {
          return new Promise((resolve) => {
            resolveClassA = resolve;
          });
        }
        return Promise.resolve({
          items: [
            {
              id: "asg-b",
              classroomId: "class-b",
              exerciseId: "e1",
              title: "乙班任务",
              description: "",
              status: "PUBLISHED",
              createdAt: "2026-09-02T00:00:00Z",
              updatedAt: "2026-09-02T00:00:00Z",
              version: 1,
            },
          ],
        });
      }
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    // 挂载后自动选中甲班（class-a），该请求被挂起；切到乙班立即返回。
    fireEvent.click(await screen.findByRole("button", { name: "乙班" }));
    expect(await screen.findByText("乙班任务")).toBeInTheDocument();

    // 甲班的迟到响应此时才完成：必须被丢弃，不能覆盖乙班的任务列表。
    await act(async () => {
      resolveClassA({
        items: [
          {
            id: "asg-a",
            classroomId: "class-a",
            exerciseId: "e2",
            title: "甲班任务",
            description: "",
            status: "PUBLISHED",
            createdAt: "2026-09-01T00:00:00Z",
            updatedAt: "2026-09-01T00:00:00Z",
            version: 1,
          },
        ],
      });
    });

    expect(screen.getByText("乙班任务")).toBeInTheDocument();
    expect(screen.queryByText("甲班任务")).not.toBeInTheDocument();
  });

  it("auto-refreshes the workspace once when the first load has no classes", async () => {
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "cloud.workspace") {
        if (params?.refreshRemote)
          return Promise.resolve(
            teacherWorkspace([
              {
                id: "class-9",
                name: "数据2501",
                createdAt: "2026-09-10T00:00:00Z",
                members: [],
              },
            ]),
          );
        return Promise.resolve(teacherWorkspace([]));
      }
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    // 首屏不带 refreshRemote 返回空班级时必须自动刷新一次，让班级面板直接可用。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "cloud.workspace",
        expect.objectContaining({ refreshRemote: true }),
      ),
    );
    expect(
      await screen.findByRole("heading", { level: 2, name: "班级任务：数据2501" }),
    ).toBeInTheDocument();
    // 已尝试过后不得循环刷新：仍然只有一次带 refreshRemote 的调用。
    await waitFor(() =>
      expect(
        requestMock.mock.calls.filter(
          ([method, params]) => method === "cloud.workspace" && params?.refreshRemote,
        ),
      ).toHaveLength(1),
    );
  });

  it("shows the target-class selector and member roster for teachers", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(
          teacherWorkspace([
            {
              id: "class-1",
              name: "软件2401",
              createdAt: "2026-09-01T00:00:00Z",
              members: [{ userId: "u1", role: "TEACHER" }],
            },
          ]),
        );
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      if (method === "cloud.class.roster")
        return Promise.resolve({
          members: [
            {
              userId: "u1",
              email: "wang@example.com",
              displayName: "王老师",
              role: "TEACHER",
            },
            {
              userId: "u2",
              email: "li@example.com",
              displayName: "李同学",
              role: "STUDENT",
            },
          ],
        });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    // 成员/任务面板里必须显式提供目标班级选择，并预告新成员的去向（issue #20）。
    fireEvent.click(await screen.findByText("添加成员", { selector: "summary strong" }));
    expect(await screen.findByLabelText("目标班级")).toHaveValue("class-1");
    expect(screen.getByText(/新成员将加入「软件2401」/)).toBeInTheDocument();

    // 成员名单（issue #26）：展开后展示姓名、角色与邮箱。
    fireEvent.click(await screen.findByText("成员名单", { exact: true }));
    await screen.findByText(/学生 · li@example.com/);
    expect(screen.getAllByText(/教师 · wang@example.com/)).toHaveLength(1);
    expect(screen.getByText("李同学")).toBeInTheDocument();
  });

  it("keeps the roster degraded silently when the cloud server lacks the endpoint", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(
          teacherWorkspace([
            {
              id: "class-1",
              name: "软件2401",
              createdAt: "2026-09-01T00:00:00Z",
              members: [],
            },
          ]),
        );
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      if (method === "cloud.class.roster")
        return Promise.reject(new Error("Cloud API request failed (HTTP 404)"));
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByText("成员名单", { exact: true }));
    expect(await screen.findByText(/成员名单暂时不可用/)).toBeInTheDocument();
    // 旧服务端缺端点属于预期降级，不得弹出错误 toast。
    expect(document.querySelectorAll(".ui-toaster .ui-toast")).toHaveLength(0);
  });

  it("shows the class join code to teachers and rotates it after confirmation", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(
          teacherWorkspace([
            { id: "class-1", name: "软件2401", createdAt: "2026-09-01T00:00:00Z", members: [] },
          ]),
        );
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments") return Promise.resolve({ items: [] });
      if (method === "cloud.class.roster") return Promise.resolve({ members: [] });
      if (method === "cloud.class.join-code") return Promise.resolve({ joinCode: "AB234567" });
      if (method === "cloud.class.join-code.rotate")
        return Promise.resolve({ joinCode: "CD345678" });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    // v3.8.0 UIX-2：确认交互改为应用内 ConfirmDialog（原 window.confirm 桩移除）。
    renderCloudPage();

    // v3.4.1 CLS-4：教师在成员名单里可见班级码。
    fireEvent.click(await screen.findByText("成员名单", { exact: true }));
    expect(await screen.findByText("班级码：AB234567")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "重置班级码" }));
    fireEvent.click(await screen.findByRole("button", { name: "重置" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("cloud.class.join-code.rotate", {
        classroomId: "class-1",
      }),
    );
    expect(await screen.findByText("班级码：CD345678")).toBeInTheDocument();
    expect(await screen.findByText(/班级码已重置/)).toBeInTheDocument();
  });

  it("lets students join a class by code and shows the joined class", async () => {
    requestMock.mockImplementation((method: string, params?: Record<string, unknown>) => {
      if (method === "cloud.class.join") {
        return Promise.resolve({
          id: "class-9",
          name: "数据结构 1 班",
          createdAt: "2026-09-01T00:00:00Z",
          members: [],
        });
      }
      if (params?.refreshRemote) {
        return Promise.resolve({
          signedIn: true,
          state: "READY",
          message: "云端就绪",
          displayName: "李学生",
          role: "STUDENT",
          recoverable: true,
          sync: { state: "SYNCED", pending: 0, attempt: 0 },
          classes: [
            {
              id: "class-9",
              name: "数据结构 1 班",
              createdAt: "2026-09-01T00:00:00Z",
              members: [{ userId: "s-1", role: "STUDENT" }],
            },
          ],
        } satisfies CloudWorkspace);
      }
      return Promise.resolve(studentWorkspace());
    });
    renderCloudPage();

    // v3.4.1 CLS-5：学生凭码加入入口；输入统一转大写。
    const codeInput = await screen.findByLabelText("班级码");
    fireEvent.change(codeInput, { target: { value: "ab234567" } });
    expect(codeInput).toHaveValue("AB234567");
    fireEvent.click(screen.getByRole("button", { name: "凭班级码加入" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("cloud.class.join", { code: "AB234567" }),
    );
    expect(await screen.findByText("数据结构 1 班")).toBeInTheDocument();
    expect(await screen.findByText(/已加入班级/)).toBeInTheDocument();
  });

  const teacherClass = [
    { id: "class-1", name: "数据结构 1 班", createdAt: "2026-09-01T00:00:00Z", members: [] },
  ];

  const publishedAssignment: CloudAssignment = {
    id: "asg-1",
    classroomId: "class-1",
    exerciseId: "ex-1",
    title: "SELECT 查询练习",
    description: "完成基础查询",
    status: "PUBLISHED",
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    version: 3,
  };

  const feedbackItem = {
    submissionId: "sub-1",
    assignmentId: "asg-1",
    studentUserId: "student-1",
    status: "NEEDS_WORK",
    comment: "",
    knowledgePointIds: [],
    version: 1,
    authorUserId: "teacher-1",
    updatedAt: "2026-09-10T00:00:00Z",
  };

  it("fills the editable comment with the AI draft and still saves through cloud.feedback.save", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments")
        return Promise.resolve({ items: [publishedAssignment] });
      if (method === "cloud.feedback.list")
        return Promise.resolve({ items: [feedbackItem], cached: false });
      if (method === "cloud.feedback.draft")
        return Promise.resolve({ text: "该生查询思路正确，注意 WHERE 条件。", evidence: ["sub-1"], aiGenerated: true });
      if (method === "cloud.feedback.save") return Promise.resolve({ ...feedbackItem, status: "REVIEWED", version: 2 });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByRole("button", { name: "批阅反馈" }));
    fireEvent.click(await screen.findByRole("button", { name: "AI 起草" }));

    const comment = await screen.findByLabelText("反馈内容");
    await waitFor(() => expect(comment).toHaveValue("该生查询思路正确，注意 WHERE 条件。"));
    expect(screen.getByRole("button", { name: "保存反馈 ·" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "保存反馈 ·" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "cloud.feedback.save",
        expect.objectContaining({
          submissionId: "sub-1",
          comment: "该生查询思路正确，注意 WHERE 条件。",
          expectedVersion: 1,
        }),
      ),
    );
  });

  it("keeps the manual feedback path usable when the AI draft fails", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments")
        return Promise.resolve({ items: [publishedAssignment] });
      if (method === "cloud.feedback.list")
        return Promise.resolve({ items: [{ ...feedbackItem, comment: "手写评语" }], cached: false });
      if (method === "cloud.feedback.draft")
        return Promise.reject(new Error("AI 服务不可用"));
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByRole("button", { name: "批阅反馈" }));
    fireEvent.click(await screen.findByRole("button", { name: "AI 起草" }));
    // v3.8.0 UIX-2：已有评语时先经应用内确认，点「覆盖并起草」后才发起起草。
    fireEvent.click(await screen.findByRole("button", { name: "覆盖并起草" }));

    expect(await screen.findByText(/AI 起草失败，可直接手写评语/)).toBeInTheDocument();
    expect(screen.getByLabelText("反馈内容")).toHaveValue("手写评语");
    expect(requestMock).not.toHaveBeenCalledWith("cloud.feedback.save", expect.anything());
  });

  it("updates a published assignment through the edit form with an optimistic version", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments")
        return Promise.resolve({ items: [publishedAssignment] });
      if (method === "cloud.assignment.update")
        return Promise.resolve({ ...publishedAssignment, title: "SELECT 进阶练习", version: 4 });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    expect(await screen.findByText("SELECT 查询练习")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "编辑" }));

    const title = await screen.findByLabelText("任务标题");
    await waitFor(() => expect(title).toHaveValue("SELECT 查询练习"));
    expect(screen.getByText(/编辑中：SELECT 查询练习/)).toBeInTheDocument();
    expect(screen.getByLabelText("练习")).toBeDisabled();

    fireEvent.change(title, { target: { value: "SELECT 进阶练习" } });
    fireEvent.click(screen.getByRole("button", { name: "保存修改" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "cloud.assignment.update",
        expect.objectContaining({
          classroomId: "class-1",
          assignmentId: "asg-1",
          title: "SELECT 进阶练习",
          expectedVersion: 3,
        }),
      ),
    );
    expect(await screen.findByText(/任务「SELECT 进阶练习」已更新/)).toBeInTheDocument();
  });

  it("hides the edit entry for archived assignments", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.assignments")
        return Promise.resolve({
          items: [
            publishedAssignment,
            { ...publishedAssignment, id: "asg-archived", title: "归档任务", status: "ARCHIVED" },
          ],
        });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    expect(await screen.findByText("归档任务")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "编辑" })).toHaveLength(1);
  });

  it("imports a single course JSON and refreshes the course list", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.courses") return Promise.resolve({ items: [], cached: false });
      if (method === "cloud.course.import")
        return Promise.resolve({ courseId: "course-9", sections: 2, knowledgePoints: 5, exercises: 3 });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByText("共享课程、知识点与版本化任务"));
    fireEvent.change(await screen.findByLabelText(/课程 JSON/), {
      target: { value: '{"course":{"name":"数据库基础"}}' },
    });
    fireEvent.click(screen.getByRole("button", { name: "导入课程 JSON" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("cloud.course.import", {
        content: '{"course":{"name":"数据库基础"}}',
      }),
    );
    expect(await screen.findByText(/课程 JSON 已导入：2 个章节/)).toBeInTheDocument();
  });

  it("surfaces a readable error when the course JSON import is rejected", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace(teacherClass));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      if (method === "cloud.courses") return Promise.resolve({ items: [], cached: false });
      if (method === "cloud.course.import")
        return Promise.reject(new Error("课程文件格式不正确或版本不兼容"));
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    fireEvent.click(await screen.findByText("共享课程、知识点与版本化任务"));
    fireEvent.change(await screen.findByLabelText(/课程 JSON/), {
      target: { value: "{broken}" },
    });
    fireEvent.click(screen.getByRole("button", { name: "导入课程 JSON" }));

    expect(await screen.findByText(/课程 JSON 导入失败：课程文件格式不正确或版本不兼容/)).toBeInTheDocument();
  });
});
