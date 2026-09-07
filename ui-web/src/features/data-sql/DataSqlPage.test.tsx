import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DataSqlPage from "./DataSqlPage";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
}));

vi.mock("monaco-editor/editor/editor.api", () => {
  const languages = {
    registerCompletionItemProvider: vi.fn(),
    CompletionItemKind: { Field: 0 },
  };
  class Range {
  }
  return { languages, Range, default: { languages, Range } };
});
vi.mock("monaco-editor/editor/editor.worker?worker", () => ({ default: class {} }));
vi.mock("monaco-editor/languages/definitions/sql/register", () => ({}));
vi.mock("@monaco-editor/react", () => ({
  default: () => <div data-testid="monaco-mock" />,
  loader: { config: vi.fn() },
}));

const dialectItems = {
  items: [
    { name: "SQLITE", displayName: "SQLite", defaultPort: 0, fileBased: true, generic: false },
    { name: "MYSQL", displayName: "MySQL", defaultPort: 3306, fileBased: false, generic: false },
    { name: "DAMENG", displayName: "达梦 DM8", defaultPort: 5236, fileBased: false, generic: false },
  ],
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <DataSqlPage />
    </QueryClientProvider>,
  );
}

describe("DataSqlPage connection manager", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("shows dialect display names and fills the default port when the dialect changes", async () => {
    renderPage();

    const dialectSelect = (await screen.findByLabelText("数据库类型")) as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "达梦 DM8" })).toBeInTheDocument();

    fireEvent.change(dialectSelect, { target: { value: "MYSQL" } });
    const port = (await screen.findByLabelText("端口")) as HTMLInputElement;
    expect(port.value).toBe("3306");
  });

  it("tests the connection first and saves with a generated id and display name", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.test") {
        return Promise.resolve({ successful: true, message: "连接成功。", databaseProduct: "SQLite", databaseVersion: "3.45", elapsed: 1 });
      }
      if (method === "data.connection.save") {
        return Promise.resolve({
          id: "sqlite-school-ab12", displayName: "SQLite school.db", dialect: "SQLITE", readOnly: false,
          enabled: true, builtIn: false, selected: true, databasePath: "C:\\data\\school.db",
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    const fileInput = await screen.findByLabelText("数据库文件");
    fireEvent.change(fileInput, { target: { value: "C:\\data\\school.db" } });
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));

    await screen.findByText("连接成功");
    await waitFor(() => expect(requestMock).toHaveBeenCalledWith(
      "data.connection.save",
      expect.objectContaining({
        id: expect.stringMatching(/^[a-z0-9][a-z0-9._-]{0,63}$/),
        displayName: "SQLite school.db",
        port: 0,
      }),
    ));
    const testIndex = requestMock.mock.calls.findIndex(call => call[0] === "data.connection.test");
    const saveIndex = requestMock.mock.calls.findIndex(call => call[0] === "data.connection.save");
    expect(testIndex).toBeGreaterThanOrEqual(0);
    expect(saveIndex).toBeGreaterThan(testIndex);
  });

  it("loads and renders the SQL execution history when expanded", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "sql.history") {
        return Promise.resolve({
          items: [{
            connectionId: "demo", connectionName: "SQLite 演示数据库", sql: "select name from student",
            successful: true, rowCount: 7, durationMillis: 12, createdAt: "2026-09-07T01:00:00Z",
          }],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.click(screen.getByText("执行历史"));

    expect(await screen.findByText(/select name from student/)).toBeInTheDocument();
    expect(screen.getByText(/SQLite 演示数据库/)).toBeInTheDocument();
  });

  it("duplicates the selected connection into the form with a fresh id", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") {
        return Promise.resolve({
          items: [{
            id: "mysql.course", displayName: "MySQL 课程库", dialect: "MYSQL", readOnly: false,
            enabled: true, builtIn: false, selected: true, host: "db.school.edu", port: 3306,
            databaseName: "course", username: "teacher",
          }],
        });
      }
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "复制" }));

    const displayName = (await screen.findByLabelText("显示名称")) as HTMLInputElement;
    expect(displayName.value).toBe("MySQL 课程库 副本");
    expect((screen.getByLabelText("数据库类型") as HTMLSelectElement).value).toBe("MYSQL");
    expect((screen.getByLabelText("主机") as HTMLInputElement).value).toBe("db.school.edu");
    expect((screen.getByLabelText("端口") as HTMLInputElement).value).toBe("3306");
  });

  it("does not save when the connection test fails", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.test") {
        return Promise.resolve({ successful: false, message: "连接失败，请检查数据库地址、凭据和服务状态。", databaseProduct: "", databaseVersion: "", elapsed: 1 });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    const fileInput = await screen.findByLabelText("数据库文件");
    fireEvent.change(fileInput, { target: { value: "C:\\data\\school.db" } });
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));

    expect(await screen.findAllByText(/连接失败，请检查数据库地址/)).not.toHaveLength(0);
    expect(requestMock).not.toHaveBeenCalledWith("data.connection.save", expect.anything());
  });
});
