import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ConnectionEditor, emptyConnection, valueToDraft } from "./ConnectionManager";

// v3.4.4 CTB-1：v3.4.3 的连接表单用例随组件抽取迁移到这里（表单本身行为不变）。
const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
}));

const dialectItems = {
  items: [
    { name: "SQLITE", displayName: "SQLite", defaultPort: 0, fileBased: true, generic: false },
    { name: "MYSQL", displayName: "MySQL", defaultPort: 3306, fileBased: false, generic: false },
    {
      name: "DAMENG",
      displayName: "达梦 DM8",
      defaultPort: 5236,
      fileBased: false,
      generic: false,
    },
  ],
};

function renderEditor(initial = emptyConnection(), builtIn = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onSaved = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <ConnectionEditor initial={initial} builtIn={builtIn} onSaved={onSaved} />
    </QueryClientProvider>,
  );
  return onSaved;
}

describe("ConnectionEditor", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("selects quick dialect chips and fills the default port; rare types stay in the more-types dropdown", async () => {
    renderEditor();

    // v3.4.3 CXN-1：常用类型（MySQL/SQLite/PostgreSQL）为快捷 chip，点击即选中并自动填端口。
    const mysqlChip = await screen.findByRole("button", { name: "MySQL" });
    fireEvent.click(mysqlChip);
    const port = (await screen.findByLabelText("端口")) as HTMLInputElement;
    expect(port.value).toBe("3306");
    expect(mysqlChip).toHaveAttribute("aria-pressed", "true");

    // 其余方言收进「更多类型」下拉，选择后同样自动填端口。
    const moreSelect = screen.getByLabelText("更多数据库类型") as HTMLSelectElement;
    expect(screen.getByRole("option", { name: "达梦 DM8" })).toBeInTheDocument();
    fireEvent.change(moreSelect, { target: { value: "DAMENG" } });
    expect(port.value).toBe("5236");
    expect(mysqlChip).toHaveAttribute("aria-pressed", "false");
  });

  it("keeps generic JDBC fields behind the more-types dropdown", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connection.dialects") {
        return Promise.resolve({
          items: [
            ...dialectItems.items,
            { name: "GENERIC", displayName: "通用 JDBC", defaultPort: 0, fileBased: false, generic: true },
          ],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderEditor();

    // 默认 SQLite：只有文件字段，没有 JDBC URL/驱动。
    await screen.findByLabelText("数据库文件");
    expect(screen.queryByLabelText("JDBC URL")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("更多数据库类型"), { target: { value: "GENERIC" } });
    expect(await screen.findByLabelText("JDBC URL")).toBeInTheDocument();
    expect(screen.getByLabelText("驱动类")).toBeInTheDocument();
    expect(screen.getByLabelText("驱动 JAR")).toBeInTheDocument();
  });

  it("tests the connection first, saves with a generated id and display name, and reports the saved summary", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.test") {
        return Promise.resolve({
          successful: true,
          message: "连接成功。",
          databaseProduct: "SQLite",
          databaseVersion: "3.45",
          elapsed: 1,
        });
      }
      if (method === "data.connection.save") {
        return Promise.resolve({
          id: "sqlite-school-ab12",
          displayName: "SQLite school.db",
          dialect: "SQLITE",
          readOnly: false,
          enabled: true,
          builtIn: false,
          selected: true,
          databasePath: "C:\\data\\school.db",
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    const onSaved = renderEditor();

    const fileInput = await screen.findByLabelText("数据库文件");
    fireEvent.change(fileInput, { target: { value: "C:\\data\\school.db" } });
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));

    await screen.findByText("连接成功");
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "data.connection.save",
        expect.objectContaining({
          id: expect.stringMatching(/^[a-z0-9][a-z0-9._-]{0,63}$/),
          displayName: "SQLite school.db",
          port: 0,
        }),
      ),
    );
    const testIndex = requestMock.mock.calls.findIndex(
      (call) => call[0] === "data.connection.test",
    );
    const saveIndex = requestMock.mock.calls.findIndex(
      (call) => call[0] === "data.connection.save",
    );
    expect(testIndex).toBeGreaterThanOrEqual(0);
    expect(saveIndex).toBeGreaterThan(testIndex);
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: "sqlite-school-ab12" })));
  });

  it("does not save when the connection test fails", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.test") {
        return Promise.resolve({
          successful: false,
          message: "连接失败，请检查数据库地址、凭据和服务状态。",
          databaseProduct: "",
          databaseVersion: "",
          elapsed: 1,
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    const onSaved = renderEditor();

    const fileInput = await screen.findByLabelText("数据库文件");
    fireEvent.change(fileInput, { target: { value: "C:\\data\\school.db" } });
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));

    expect(await screen.findAllByText(/连接失败，请检查数据库地址/)).not.toHaveLength(0);
    expect(requestMock).not.toHaveBeenCalledWith("data.connection.save", expect.anything());
    expect(onSaved).not.toHaveBeenCalled();
  });

  it("lists server databases on demand and offers them as a datalist", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.databases") {
        return Promise.resolve({
          items: ["course", "school", "information_schema"],
          message: "",
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderEditor();

    // v3.4.4：MySQL 等服务器方言在填好用户名后可一键列出数据库；SQLite 文件库没有该按钮。
    expect(screen.queryByRole("button", { name: "列出数据库" })).not.toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: "MySQL" }));

    const listButton = screen.getByRole("button", { name: "列出数据库" });
    expect(listButton).toBeDisabled();
    fireEvent.change(screen.getByLabelText("用户名"), { target: { value: "root" } });
    expect(listButton).toBeEnabled();
    fireEvent.click(listButton);

    // v3.4.4：列出结果用显式下拉展示（原生 datalist 会被输入值前缀过滤成空弹层）。
    const picker = await screen.findByLabelText("从服务器数据库列表选择", { selector: "select" });
    expect(picker).toBeInTheDocument();
    fireEvent.change(picker, { target: { value: "course" } });
    expect((screen.getByLabelText("数据库") as HTMLInputElement).value).toBe("course");
  });

  it("locks the connection id for built-in connections", async () => {
    renderEditor(valueToDraft({
      id: "sqlite-demo",
      displayName: "SQLite 演示数据库",
      dialect: "SQLITE",
      readOnly: true,
      enabled: true,
      builtIn: true,
      selected: true,
      databasePath: "C:\\data\\school.db",
    }), true);

    await screen.findByLabelText("数据库文件");
    expect((screen.getByLabelText("连接 ID") as HTMLInputElement).value).toBe("sqlite-demo");
    expect(screen.getByLabelText("连接 ID")).toBeDisabled();
  });
});
