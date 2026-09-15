import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import DataSqlPage from "./DataSqlPage";
import { subscribeConnectionPanel } from "./connectionPanel";

// v3.4.4 CTB：连接管理表单上移到顶栏（ConnectionManager/TopbarConnection 各自的测试文件），
// 本文件聚焦数据页自身：纯表结构侧栏、空态引导、深链、SQL 工作台与安全模式。
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
  class Range {}
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
  ],
};

const preferences = {
  role: "STUDENT",
  developerMode: false,
  canMaintainLocalData: true,
  secretsExposed: false,
  general: {
    automaticUpdateChecks: true,
    skippedVersion: "",
    proxyMode: "SYSTEM",
    proxyHost: "",
    proxyPort: 0,
    reducedMotion: false,
    highContrast: false,
    supportLogging: false,
    supportLoggingExpiresAt: 0,
    updateMirrorsEnabled: false,
    language: "zh",
    nativeNotificationsEnabled: true,
    meteredNetwork: false,
    theme: "system",
    font: "modern",
    density: "comfortable",
  },
  notifications: [],
  tasks: [],
  helpTopics: [],
};

const demoConnection = {
  id: "sqlite-demo",
  displayName: "SQLite 演示数据库",
  dialect: "SQLITE",
  readOnly: true,
  enabled: true,
  builtIn: true,
  selected: true,
  databasePath: "C:\\data\\school.db",
};
const courseConnection = {
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

function renderPage(initialEntry = "/data") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <DataSqlPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("DataSqlPage schema sidebar", () => {
  beforeEach(() => {
    requestMock.mockReset();
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      throw new Error(`Unexpected request: ${method}`);
    });
  });

  it("renders a compact current-connection indicator and delegates management to the topbar panel", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [demoConnection] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.schema") return Promise.resolve({ tables: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    const opened = vi.fn();
    const unsubscribe = subscribeConnectionPanel(opened);
    renderPage();

    // v3.4.4 CTB-2：侧栏只保留当前连接指示与表结构；管理表单不再出现在页面里。
    expect(await screen.findByText("当前连接")).toBeInTheDocument();
    expect(screen.getByText("SQLite 演示数据库")).toBeInTheDocument();
    expect(screen.queryByText("管理连接")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "测试并保存" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "管理" }));
    expect(opened).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("shows a connect guide with no connections and requests the shared panel", async () => {
    const opened = vi.fn();
    const unsubscribe = subscribeConnectionPanel(opened);
    renderPage();

    expect(await screen.findByText(/还没有数据库连接/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "连接数据库" }));
    expect(opened).toHaveBeenCalledTimes(1);
    unsubscribe();
  });

  it("writes the ?connection= deep link through to the global selection", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections")
        return Promise.resolve({ items: [demoConnection, courseConnection] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.connection.select") return Promise.resolve(courseConnection);
      if (method === "data.schema") return Promise.resolve({ tables: [] });
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage("/data?connection=mysql.course");

    // v3.4.4 CTB-3：深链不再只改页面本地视图，而是统一写后端选中。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith("data.connection.select", {
        connectionId: "mysql.course",
      }),
    );
  });

  it("loads and renders the SQL execution history when expanded", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "sql.history") {
        return Promise.resolve({
          items: [
            {
              connectionId: "demo",
              connectionName: "SQLite 演示数据库",
              sql: "select name from student",
              successful: true,
              rowCount: 7,
              durationMillis: 12,
              createdAt: "2026-09-07T01:00:00Z",
            },
          ],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    fireEvent.click(screen.getByText("执行历史"));

    expect(await screen.findByText(/select name from student/)).toBeInTheDocument();
    expect(screen.getByText(/SQLite 演示数据库/)).toBeInTheDocument();
  });

  it("does not prompt for an SQL safety mode when the backend reports no first-run state", async () => {
    // 默认 mock 没有 settings.preferences（旧版后端/字段缺失）——不弹选择框。
    renderPage();

    await screen.findByText(/还没有数据库连接/);
    expect(screen.queryByText("选择 SQL 安全模式")).not.toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalledWith("settings.update", expect.anything());
  });

  it("analyzes the wrapped EXPLAIN statement before executing the execution plan", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [demoConnection] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.schema") return Promise.resolve({ tables: [] });
      if (method === "sql.analyze") {
        return Promise.resolve({
          level: "LOW",
          executable: true,
          confirmationRequired: false,
          multiStatement: false,
          statementType: "SELECT",
          reasons: [],
          enforcedBy: "java",
          maxRows: 500,
          timeoutSeconds: 10,
        });
      }
      if (method === "sql.execute") {
        return Promise.resolve({
          resultId: "r1",
          success: true,
          columns: ["detail"],
          rows: [{ detail: "SCAN student" }],
          page: 0,
          pageSize: 50,
          totalRows: 1,
          hasMore: false,
          truncated: false,
          affectedRows: 0,
          message: "",
          durationMillis: 3,
          auditRecorded: true,
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    const explainButton = await screen.findByRole("button", { name: "执行计划" });
    await waitFor(() => expect(explainButton).toBeEnabled());
    fireEvent.click(explainButton);

    // BUG-6：执行计划必须先经 sql.analyze 统一风险门禁，且分析的是包裹后的语句。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "sql.analyze",
        expect.objectContaining({ sql: expect.stringContaining("EXPLAIN QUERY PLAN") }),
      ),
    );
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "sql.execute",
        expect.objectContaining({ sql: expect.stringContaining("EXPLAIN QUERY PLAN") }),
      ),
    );
    const analyzeIndex = requestMock.mock.calls.findIndex((call) => call[0] === "sql.analyze");
    const executeIndex = requestMock.mock.calls.findIndex((call) => call[0] === "sql.execute");
    expect(analyzeIndex).toBeGreaterThanOrEqual(0);
    expect(executeIndex).toBeGreaterThan(analyzeIndex);
    expect(await screen.findByText("SCAN student")).toBeInTheDocument();
  });

  it("renders collapsed schema tables with a working filter and table count", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [demoConnection] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.schema") {
        return Promise.resolve({
          tables: [
            {
              name: "S",
              columns: [
                { name: "SNO", typeName: "TEXT", primaryKey: true, nullable: false },
                { name: "SNAME", typeName: "TEXT", primaryKey: false, nullable: false },
              ],
              indexes: [],
            },
            {
              name: "SPJ",
              columns: [
                { name: "SNO", typeName: "TEXT", primaryKey: true, nullable: false },
                { name: "QTY", typeName: "INTEGER", primaryKey: false, nullable: true },
              ],
              indexes: [
                { name: "idx_spj_qty", unique: false, columns: ["QTY"] },
                { name: "idx_spj_sno_unique", unique: true, columns: ["SNO"] },
              ],
            },
          ],
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    await screen.findByText("共 2 张表");
    // v3.4.3 CXN-3：整体折叠块默认展开，收起入口是「表结构」summary。
    const outer = screen.getByText("表结构").closest("details");
    expect(outer).toHaveAttribute("open");
    // v3.4.1 SQL-1：表默认收起，不再 <details open> 全量展开。
    expect(screen.getByText("S").closest("details")).not.toHaveAttribute("open");
    expect(screen.getByText("SPJ").closest("details")).not.toHaveAttribute("open");

    // 每表展开后分区显示列与索引；无索引的表显示「无」。
    fireEvent.click(screen.getByText("SPJ"));
    expect(screen.getByText("idx_spj_qty")).toBeInTheDocument();
    // QTY 出现两处：列清单与索引列。
    expect(screen.getAllByText("QTY").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("idx_spj_sno_unique")).toBeInTheDocument();
    expect(screen.getByText(/SNO · 唯一/)).toBeInTheDocument();
    fireEvent.click(screen.getByText("S"));
    const sTable = screen.getByText("S").closest("details");
    expect(sTable?.textContent).toContain("无");

    const filter = screen.getByLabelText("筛选表或列");
    fireEvent.change(filter, { target: { value: "qty" } });
    expect(screen.getByText("SPJ")).toBeInTheDocument();
    expect(screen.queryByText("S")).not.toBeInTheDocument();

    fireEvent.change(filter, { target: { value: "不存在" } });
    expect(screen.getByText(/没有匹配/)).toBeInTheDocument();
  });

  it("generates an NL2SQL draft in one step with locally assembled context details", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [demoConnection] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "data.schema") return Promise.resolve({ tables: [] });
      if (method === "ai.sql.preview") {
        return Promise.resolve({
          taskType: "NL2SQL",
          categories: ["USER_REQUEST", "DATABASE_SCHEMA"],
          sources: ["用户当前请求", "所选数据库结构"],
          characterCount: 467,
          redactions: [],
        });
      }
      if (method === "ai.sql.generate") {
        return Promise.resolve({
          accepted: true,
          plan: { sqlDraft: "SELECT 1;", explanation: "草稿" },
          riskAnalysis: { level: "LOW", statementType: "SELECT", reasons: [] },
        });
      }
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    // v3.4.4：预览与生成合并为一步；上下文明细折叠收纳，不再横向撑爆屏幕。
    await screen.findByText("当前连接");
    const input = screen.getByLabelText(/查询目标/);
    fireEvent.change(input, { target: { value: "查询全部学生" } });
    fireEvent.click(screen.getByRole("button", { name: "生成 SQL 草稿" }));

    expect(await screen.findByText("SELECT 1;")).toBeInTheDocument();
    const previewIndex = requestMock.mock.calls.findIndex((call) => call[0] === "ai.sql.preview");
    const generateIndex = requestMock.mock.calls.findIndex((call) => call[0] === "ai.sql.generate");
    expect(previewIndex).toBeGreaterThanOrEqual(0);
    expect(generateIndex).toBeGreaterThan(previewIndex);
    fireEvent.click(screen.getByText(/本次使用的上下文/));
    expect(screen.getByText(/467 个字符/)).toBeInTheDocument();
    expect(screen.getByText(/无需额外脱敏/)).toBeInTheDocument();
  });

  it("prompts first-run users to choose an SQL safety mode and persists the choice", async () => {
    requestMock.mockImplementation((method: string) => {
      if (method === "data.connections") return Promise.resolve({ items: [] });
      if (method === "data.connection.dialects") return Promise.resolve(dialectItems);
      if (method === "settings.preferences") {
        return Promise.resolve({ ...preferences, developerModeExplicit: false });
      }
      if (method === "settings.update") return Promise.resolve({});
      throw new Error(`Unexpected request: ${method}`);
    });
    renderPage();

    expect(await screen.findByText("选择 SQL 安全模式")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /开发者模式/ }));

    // 写入 developerMode 布尔值，且携带既有 general 偏好一起提交。
    await waitFor(() =>
      expect(requestMock).toHaveBeenCalledWith(
        "settings.update",
        expect.objectContaining({ developerMode: true, language: "zh", theme: "system" }),
      ),
    );
    // 选择完成后选择框关闭，且不会因刷新回来的旧载荷再次弹出。
    await waitFor(() => expect(screen.queryByText("选择 SQL 安全模式")).not.toBeInTheDocument());
    expect(screen.queryByRole("button", { name: /教学模式（推荐）/ })).not.toBeInTheDocument();
  });
});
