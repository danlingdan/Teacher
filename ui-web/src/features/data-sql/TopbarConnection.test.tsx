import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Toaster } from "../../shared/ui";
import TopbarConnection from "./TopbarConnection";
import { openConnectionPanel, subscribeConnectionPanel } from "./connectionPanel";

// v3.4.4 CTB-1：顶栏连接入口——chip 状态、清单快速切换、Dialog 表单、删除确认。
// v3.10.0 HAJ-1 修订：AI 引擎分区撤出本弹层（独立顶栏按钮 TopbarAiEngine，
// 见 TopbarAiEngine.test.tsx），这里只保留连接清单、快速测试与重新输入密码行为。
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
  ],
};

const connectionA = {
  id: "sqlite-demo",
  displayName: "SQLite 演示数据库",
  dialect: "SQLITE",
  readOnly: true,
  enabled: true,
  builtIn: true,
  selected: true,
  databasePath: "C:\\data\\school.db",
};
const connectionB = {
  id: "mysql.course",
  displayName: "MySQL 课程库",
  dialect: "MYSQL",
  readOnly: false,
  enabled: true,
  builtIn: false,
  selected: false,
  host: "db.school.edu",
  port: 3306,
  databaseName: "course",
  username: "teacher",
};

function renderTopbar() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <Toaster>
        <TopbarConnection />
      </Toaster>
    </QueryClientProvider>,
  );
}

// 清单行内定位：chip 标签与行 strong 同文，取落在 li 内的那个。
async function findPopoverRow(displayName: string) {
  const matches = await screen.findAllByText(displayName);
  const row = matches
    .map((el) => el.closest("li"))
    .find((el): el is HTMLLIElement => el !== null);
  if (!row) throw new Error(`popover row not found: ${displayName}`);
  return row;
}

describe("TopbarConnection", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("shows the current connection name and dialect on the chip", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [connectionA] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    const chip = await screen.findByRole("button", { name: /数据库连接：SQLite 演示数据库/ });
    await waitFor(() => expect(chip).toHaveTextContent("SQLite 演示数据库"));
    expect(screen.getByText("SQLite")).toBeInTheDocument();
    expect(screen.getByText("只读")).toBeInTheDocument();
    fireEvent.click(chip);
    // v3.4.4：清单行内展示连接目标（文件名/主机），内置库与自建库一眼可辨。
    expect(await screen.findByText("school.db")).toBeInTheDocument();
  });

  it("guides to connect with an eye-catching chip when no connection exists", async () => {
    renderTopbar();

    const chip = await screen.findByRole("button", { name: /数据库连接，尚未选择连接/ });
    await waitFor(() => expect(chip).toHaveTextContent("连接数据库"));
    fireEvent.click(chip);

    expect(await screen.findByText(/还没有数据库连接/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "新建连接" })).toBeInTheDocument();
  });

  it("opens the popover when the data page requests the shared panel", async () => {
    renderTopbar();
    const opened = vi.fn();
    const unsubscribe = subscribeConnectionPanel(opened);

    act(() => {
      openConnectionPanel();
    });
    expect(await screen.findByRole("dialog", { name: "数据库连接" })).toBeInTheDocument();
    unsubscribe();
  });

  it("keeps re-enter-password available only for server-based rows", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections")
        return Promise.resolve({ items: [connectionA, connectionB] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    fireEvent.click(await screen.findByRole("button", { name: /数据库连接：SQLite 演示数据库/ }));

    // 文件型（SQLite）无凭据，没有「重新输入密码」；服务器型（MySQL）才有。
    const sqliteRow = await findPopoverRow("SQLite 演示数据库");
    expect(within(sqliteRow).queryByRole("button", { name: "重新输入密码" })).not.toBeInTheDocument();
    const mysqlRow = await findPopoverRow("MySQL 课程库");
    expect(within(mysqlRow).getByRole("button", { name: "重新输入密码" })).toBeInTheDocument();
  });

  it("re-verifies a server connection password and confirms within the run", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections")
        return Promise.resolve({ items: [connectionA, connectionB] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.test") {
        return Promise.resolve({
          successful: true,
          message: "连接成功。",
          databaseProduct: "MySQL",
          databaseVersion: "8.0.36",
          elapsed: 3,
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    fireEvent.click(await screen.findByRole("button", { name: /数据库连接：SQLite 演示数据库/ }));
    const reauthButtons = await screen.findAllByRole("button", { name: "重新输入密码" });
    expect(reauthButtons).toHaveLength(1);
    fireEvent.click(reauthButtons[0]!);

    expect(await screen.findByText("重新输入数据库密码")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("数据库密码"), { target: { value: "s3cret-pw" } });
    fireEvent.click(screen.getByRole("button", { name: "验证密码" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.test", {
        connectionId: "mysql.course",
        password: "s3cret-pw",
      }),
    );
    expect(await screen.findByText("密码已验证，本次运行内有效")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText("重新输入数据库密码")).not.toBeInTheDocument(),
    );
  });

  it("tests the current connection from the popover footer", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [connectionA] });
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
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    fireEvent.click(await screen.findByRole("button", { name: /数据库连接：SQLite 演示数据库/ }));
    fireEvent.click(await screen.findByRole("button", { name: "测试当前连接" }));

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.test", {
        connectionId: "sqlite-demo",
      }),
    );
    expect(await screen.findByText(/✓ 连接成功。/)).toBeInTheDocument();
  });

  it("switches the current connection by clicking a list row", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections")
        return Promise.resolve({ items: [connectionA, connectionB] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    fireEvent.click(await screen.findByText("SQLite 演示数据库"));
    const row = screen.getByRole("button", { name: /MySQL 课程库/ });
    fireEvent.click(row);

    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.select", {
        connectionId: "mysql.course",
      }),
    );
  });

  it("edits an existing connection in a dialog and hides dangerous actions for built-in rows", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [connectionA] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    fireEvent.click(await screen.findByText("SQLite 演示数据库"));
    expect(screen.queryByRole("button", { name: "复制" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "删除" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "编辑" }));

    expect(await screen.findByText("编辑数据库连接")).toBeInTheDocument();
    const displayName = (await screen.findByLabelText("显示名称")) as HTMLInputElement;
    expect(displayName.value).toBe("SQLite 演示数据库");
    expect(screen.getByLabelText("连接 ID")).toBeDisabled();
  });

  it("copies a connection into a fresh editable draft", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [connectionB] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    const chip = await screen.findByRole("button", { name: /尚未选择连接/ });
    fireEvent.click(chip);
    fireEvent.click(await screen.findByText("MySQL 课程库"));
    fireEvent.click(screen.getByRole("button", { name: "复制" }));

    expect(await screen.findByText("新建数据库连接")).toBeInTheDocument();
    const displayName = (await screen.findByLabelText("显示名称")) as HTMLInputElement;
    expect(displayName.value).toBe("MySQL 课程库 副本");
    expect((screen.getByLabelText("主机") as HTMLInputElement).value).toBe("db.school.edu");
  });

  it("deletes a connection only after confirmation", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [connectionB] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    const chip = await screen.findByRole("button", { name: /尚未选择连接/ });
    fireEvent.click(chip);
    fireEvent.click(await screen.findByText("MySQL 课程库"));
    fireEvent.click(screen.getByRole("button", { name: "删除" }));
    expect(await screen.findByText(/确认删除“MySQL 课程库”/)).toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalledWith("data.connection.delete", expect.anything());

    fireEvent.click(screen.getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.delete", {
        connectionId: "mysql.course",
      }),
    );
  });

  it("saves a new connection and immediately makes it current", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
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
          selected: false,
          databasePath: "C:\\data\\school.db",
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderTopbar();

    const chip = await screen.findByRole("button", { name: /尚未选择连接/ });
    await waitFor(() => expect(chip).toHaveTextContent("连接数据库"));
    fireEvent.click(chip);
    fireEvent.click(screen.getByRole("button", { name: "新建连接" }));
    const fileInput = await screen.findByLabelText("数据库文件");
    fireEvent.change(fileInput, { target: { value: "C:\\data\\school.db" } });
    fireEvent.click(screen.getByRole("button", { name: "测试并保存" }));

    // 保存成功后 Dialog 关闭（结果反馈随之卸载），行为以「立即成为当前连接」为准。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.save", expect.anything()),
    );
    const saveIndex = requestMock.mock.calls.findIndex((call) => call[0] === "data.connection.save");
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.select", {
        connectionId: "sqlite-school-ab12",
      }),
    );
    const selectIndex = requestMock.mock.calls.findIndex(
      (call) => call[0] === "data.connection.select",
    );
    expect(selectIndex).toBeGreaterThan(saveIndex);
  });
});
