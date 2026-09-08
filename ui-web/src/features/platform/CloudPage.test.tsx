import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
import { CloudPage } from "./PlatformPages";
import type { CloudAssignment, CloudWorkspace } from "../../shared/types";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
}));

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
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace([]));
      if (method === "practice.catalog") return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
  });

  it("surfaces an error toast when adding a section fails instead of failing silently", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "cloud.workspace")
        return Promise.resolve(teacherWorkspace([]));
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

    fireEvent.click(
      await screen.findByText("共享课程、知识点与版本化任务"),
    );
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
      if (method === "cloud.assignments")
        return Promise.resolve({ items: [] });
      return Promise.reject(new Error(`Unexpected request: ${method}`));
    });
    renderCloudPage();

    const nameInput = await screen.findByLabelText("新班级名称");
    fireEvent.change(nameInput, { target: { value: "软件2401" } });
    fireEvent.click(screen.getByRole("button", { name: "创建班级" }));

    expect(await screen.findByText(/已创建/)).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByRole("heading", { level: 2, name: "班级任务：软件2401" })).toBeInTheDocument(),
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
    let resolveClassA: (value: { items: CloudAssignment[] }) => void =
      () => undefined;
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
});
