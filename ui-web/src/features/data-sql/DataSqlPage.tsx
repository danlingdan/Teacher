import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { cancelLocalAppRequest, localAppRequest, localAppRequestWithId } from "../../shared/ipc";
import { useMonacoEditorTheme } from "../../shared/monacoTheme";
import { formatInstant } from "../../shared/instant";
import { connectionsQuery, settingsPreferencesQuery } from "../../app/queries";
import { dialectLabel, useConnectionDialects } from "./ConnectionManager";
import { openConnectionPanel } from "./connectionPanel";
import type {
  AiContextPreview,
  DatabaseTable,
  Nl2SqlSafetyResult,
  SettingsPreferences,
  SqlHistoryItem,
  SqlPage,
  SqlRisk,
} from "../../shared/types";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

// 补全 provider 全局只注册一次。v3.3 W5.2：按模型 URI 过滤——只服务于工作台的
// /sql/<connectionId> 模型，与练习编辑器的 /workspace 模型互不污染；符号槽位经
// useEffect 刷新，不再在渲染期写入模块变量。
let sqlWorkbenchSymbols: string[] = [];
monaco.languages.registerCompletionItemProvider("sql", {
  triggerCharacters: ["."],
  provideCompletionItems: (model, position) => {
    if (model.uri.scheme !== "sqlteacher" || model.uri.path === "/workspace") {
      return { suggestions: [] };
    }
    const word = model.getWordUntilPosition(position);
    const range = new monaco.Range(
      position.lineNumber,
      word.startColumn,
      position.lineNumber,
      word.endColumn,
    );
    return {
      suggestions: sqlWorkbenchSymbols.slice(0, 500).map((label) => ({
        label,
        kind: monaco.languages.CompletionItemKind.Field,
        insertText: label,
        range,
      })),
    };
  },
});
const initialSql =
  "SELECT name, type\nFROM sqlite_master\nWHERE type IN ('table', 'view')\nORDER BY name\nLIMIT 100;";

export default function DataSqlPage() {
  const client = useQueryClient();
  const [searchParams] = useSearchParams();
  const connections = useQuery(connectionsQuery);
  const dialects = useConnectionDialects();
  // 稳定数组身份：深链 effect 依赖它，避免列表未变时重复触发。
  const items = useMemo(() => connections.data?.items ?? [], [connections.data]);
  const selected = items.find((item) => item.selected);
  // v3.4.4 CTB-3：视图连接只认后端 selected（无选中时回退第一条，兼容旧数据），
  // 顶栏切换与页内视图不再双轨。
  const connectionId = selected?.id ?? items[0]?.id ?? "";
  const current = items.find((item) => item.id === connectionId);
  // 命令面板等入口通过 ?connection= 深链：统一写全局选中（data.connection.select）。
  // ref 记录已处理过的深链值：写入选中到列表刷新之间可能存在窗口期，避免重复提交。
  const deepLinkApplied = useRef<string | undefined>(undefined);
  useEffect(() => {
    const fromUrl = searchParams.get("connection");
    if (!fromUrl || fromUrl === deepLinkApplied.current) return;
    if (!items.some((item) => item.id === fromUrl)) return;
    deepLinkApplied.current = fromUrl;
    localAppRequest("data.connection.select", { connectionId: fromUrl })
      .then(() => client.invalidateQueries({ queryKey: connectionsQuery.queryKey }))
      .catch(() => undefined);
  }, [searchParams, items, selected?.id, client]);
  const [sql, setSql] = useState(initialSql);
  const schema = useQuery({
    queryKey: ["data", "schema", connectionId],
    queryFn: () => localAppRequest<{ tables: DatabaseTable[] }>("data.schema", { connectionId }),
    enabled: Boolean(connectionId),
  });
  // 首次运行选择 SQL 安全模式：后端明确返回 developerModeExplicit=false 时弹一次选择框。
  const settings = useQuery(settingsPreferencesQuery);
  const [modeDialogDismissed, setModeDialogDismissed] = useState(false);
  const sqlModeOpen =
    Boolean(settings.data) &&
    settings.data?.developerModeExplicit === false &&
    !modeDialogDismissed;
  const chooseSqlMode = useMutation({
    mutationFn: (developerMode: boolean) =>
      localAppRequest("settings.update", { ...settings.data?.general, developerMode }),
    onSuccess: (_value, chosenDeveloperMode) => {
      // 立即写回缓存，避免下次进入页面时重复弹框。
      if (settings.data)
        client.setQueryData<SettingsPreferences>(settingsPreferencesQuery.queryKey, {
          ...settings.data,
          developerMode: chosenDeveloperMode,
          developerModeExplicit: true,
        });
      setModeDialogDismissed(true);
      void client.invalidateQueries({ queryKey: settingsPreferencesQuery.queryKey });
    },
  });
  return (
    <div className="data-workspace">
      <aside className="content-card schema-panel">
        {items.length === 0 ? (
          <div className="schema-empty">
            <p className="eyebrow">表结构</p>
            <p className="muted">还没有数据库连接。连接后即可在这里查看表结构与执行 SQL。</p>
            <Button onClick={() => openConnectionPanel()}>连接数据库</Button>
          </div>
        ) : (
          <>
            <div className="schema-connection">
              <div>
                <p className="eyebrow">当前连接</p>
                <strong>{current?.displayName}</strong>
                <small>
                  {dialectLabel(dialects, current?.dialect ?? "")}
                  {current?.readOnly ? " · 只读" : ""}
                </small>
              </div>
              <Button variant="secondary" onClick={() => openConnectionPanel()}>
                管理
              </Button>
            </div>
            <SchemaPanel
              tables={schema.data?.tables}
              isFetching={schema.isFetching}
              errorMessage={schema.isError ? schema.error.message : undefined}
            />
          </>
        )}
      </aside>
      <main className="data-main">
        <SqlWorkbench
          connectionId={connectionId}
          dialect={connections.data?.items.find((item) => item.id === connectionId)?.dialect ?? ""}
          tables={schema.data?.tables ?? []}
          sql={sql}
          onSqlChange={setSql}
        />
        <AiAssistant connectionId={connectionId} onDraft={setSql} />
      </main>
      <Dialog
        open={sqlModeOpen}
        title="选择 SQL 安全模式"
        onClose={() => setModeDialogDismissed(true)}
      >
        <p>
          决定写操作（INSERT、UPDATE、DELETE、CREATE 等）是否需要逐条确认。安全边界由 Java
          强制执行，之后可在「设置」中更改。
        </p>
        <div className="button-row">
          <Button onClick={() => chooseSqlMode.mutate(false)}>
            教学模式（推荐）：写操作需逐条确认
          </Button>
          <Button variant="secondary" onClick={() => chooseSqlMode.mutate(true)}>
            开发者模式：INSERT/CREATE 免确认，DROP 等仍需确认
          </Button>
        </div>
      </Dialog>
    </div>
  );
}

// v3.4.1 SQL-1：表结构浏览器。每张表默认收起，支持按表名/列名过滤并显示表总数；
// 树区域限高内滚，避免表多时侧栏被全部展开的列清单撑爆（原实现硬编码 <details open>）。
// v3.4.3 CXN-3：整体外包一层可折叠「表结构」块，每表展开后分区显示列与索引。
function SchemaPanel({
  tables,
  isFetching,
  errorMessage,
}: {
  tables?: DatabaseTable[];
  isFetching: boolean;
  errorMessage?: string;
}) {
  const [filter, setFilter] = useState("");
  const keyword = filter.trim().toLowerCase();
  const visible = (tables ?? []).filter(
    (table) =>
      !keyword ||
      table.name.toLowerCase().includes(keyword) ||
      table.columns.some((column) => column.name.toLowerCase().includes(keyword)),
  );
  return (
    <details className="schema-browser" open>
      <summary className="schema-browser-head">
        <strong>表结构</strong>
        {tables && <span className="schema-count">共 {tables.length} 张表</span>}
      </summary>
      {tables && tables.length > 0 && (
        <input
          className="schema-filter"
          aria-label="筛选表或列"
          placeholder="输入表名或列名筛选"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
        />
      )}
      {isFetching && <p className="muted">正在读取表结构…</p>}
      {tables && tables.length === 0 && <p className="muted">当前连接没有表。</p>}
      {tables && tables.length > 0 && visible.length === 0 && (
        <p className="muted">没有匹配“{filter.trim()}”的表或列。</p>
      )}
      {errorMessage && (
        <Feedback tone="error" title="结构读取失败">
          {errorMessage}
        </Feedback>
      )}
      <div className="schema-tree">
        {visible.map((table) => {
          const indexes = table.indexes ?? [];
          return (
            <details key={table.name}>
              <summary>{table.name}</summary>
              <div className="schema-table-section">
                <p className="schema-section-label">列</p>
                <ul>
                  {table.columns.map((column) => (
                    <li key={column.name}>
                      <strong>{column.name}</strong>
                      <span>
                        {column.typeName}
                        {column.primaryKey ? " · PK" : ""}
                        {column.nullable ? "" : " · NOT NULL"}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
              <div className="schema-table-section">
                <p className="schema-section-label">索引</p>
                {indexes.length === 0 ? (
                  <p className="muted">无</p>
                ) : (
                  <ul>
                    {indexes.map((index) => (
                      <li key={index.name}>
                        <strong>{index.name}</strong>
                        <span>
                          {index.columns.join(", ")}
                          {index.unique ? " · 唯一" : ""}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            </details>
          );
        })}
      </div>
    </details>
  );
}

function SqlWorkbench({
  connectionId,
  dialect,
  tables,
  sql,
  onSqlChange,
}: {
  connectionId: string;
  dialect: string;
  tables: DatabaseTable[];
  sql: string;
  onSqlChange: (sql: string) => void;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const [risk, setRisk] = useState<SqlRisk>();
  const [page, setPage] = useState<SqlPage>();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  // 待确认执行的语句：常规流程是编辑器里的 sql；执行计划兜底确认时为 explain 包裹语句。
  const [confirmStatement, setConfirmStatement] = useState<string>();
  const execute = useMutation<SqlPage, Error, { statement: string; confirmationToken: string }>({
    mutationFn: ({ statement, confirmationToken }) =>
      localAppRequest<SqlPage>("sql.execute", {
        connectionId,
        sql: statement,
        confirmationToken,
        maxRows: 500,
        pageSize: 50,
      }),
    onSuccess: (value) => {
      setPage(value);
      setConfirmOpen(false);
      void client.invalidateQueries({ queryKey: ["sql", "history"] });
    },
  });
  // 执行计划（W3.1）：对当前语句自动包裹 EXPLAIN QUERY PLAN，只读展示计划行。
  // 该语法仅 SQLite 方言有效（其余连接必然报错），非 SQLite 连接禁用按钮并给出提示。
  // BUG-6：explain 语句同样先经 sql.analyze 统一风险门禁，再按分析结果执行，
  // 不再以空确认令牌直连 sql.execute；被阻断时抛错走既有“SQL 未执行”反馈。
  const explainSupported = dialect === "SQLITE";
  const explainPlan = useMutation<SqlPage | undefined, Error, void>({
    mutationFn: async () => {
      const statement = `EXPLAIN QUERY PLAN ${sql}`;
      const analysis = await localAppRequest<SqlRisk>("sql.analyze", {
        connectionId,
        sql: statement,
      });
      setRisk(analysis);
      if (!analysis.executable)
        throw new Error(analysis.reasons.join("；") || "风险分析已阻止该执行计划");
      if (analysis.confirmationRequired) {
        // EXPLAIN QUERY PLAN 正常应判为 LOW；兜底复用既有确认对话框，确认后才执行。
        setConfirmStatement(statement);
        setConfirmOpen(true);
        return undefined;
      }
      return localAppRequest<SqlPage>("sql.execute", {
        connectionId,
        sql: statement,
        confirmationToken: analysis.confirmationToken ?? "",
        maxRows: 500,
        pageSize: 50,
      });
    },
    onSuccess: (value) => {
      if (!value) return;
      setPage(value);
      void client.invalidateQueries({ queryKey: ["sql", "history"] });
    },
  });
  const analyze = useMutation<SqlRisk, Error, void>({
    mutationFn: () => localAppRequest<SqlRisk>("sql.analyze", { connectionId, sql }),
    onSuccess: (value) => {
      setRisk(value);
      if (value.executable && value.confirmationRequired) {
        setConfirmStatement(sql);
        setConfirmOpen(true);
      } else if (value.executable) execute.mutate({ statement: sql, confirmationToken: "" });
    },
  });
  const nextPage = useMutation<SqlPage, Error, number>({
    mutationFn: (next) =>
      localAppRequest<SqlPage>("sql.result.page", {
        resultId: page?.resultId,
        page: next,
        pageSize: 50,
      }),
    onSuccess: setPage,
  });
  const history = useQuery({
    queryKey: ["sql", "history"],
    queryFn: () => localAppRequest<{ items: SqlHistoryItem[] }>("sql.history", { limit: 30 }),
    enabled: historyOpen,
  });
  const clearHistory = useMutation({
    mutationFn: () => localAppRequest("sql.history.clear", {}),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["sql", "history"] });
      toast("success", "执行历史已清空");
    },
    onError: (error: Error) => toast("error", `清空失败：${error.message}`),
  });
  // names 必须稳定：否则每次 render 都会向 Monaco 注入全新的符号表并触发 effect 重跑。
  const names = useMemo(
    () => tables.flatMap((table) => [table.name, ...table.columns.map((column) => column.name)]),
    [tables],
  );
  useEffect(() => {
    sqlWorkbenchSymbols = names;
  }, [names]);
  const [editorTheme, syncEditorTheme] = useMonacoEditorTheme();
  return (
    <section className="content-card sql-workbench">
      <header className="editor-toolbar">
        <div>
          <p className="eyebrow">执行策略</p>
          <h2>SQL 工作台</h2>
        </div>
        <div className="button-row">
          <span className="policy-chip">最多 500 行 · 10 秒</span>
          <Button
            variant="secondary"
            disabled={
              !connectionId ||
              !explainSupported ||
              analyze.isPending ||
              execute.isPending ||
              explainPlan.isPending
            }
            title={explainSupported ? undefined : "执行计划仅支持 SQLite 连接"}
            onClick={() => explainPlan.mutate()}
          >
            执行计划
          </Button>
          <Button
            disabled={!connectionId || analyze.isPending || execute.isPending}
            onClick={() => analyze.mutate()}
          >
            分析并运行
          </Button>
        </div>
      </header>
      <div className="sql-editor">
        <Editor
          height="100%"
          theme={editorTheme}
          beforeMount={syncEditorTheme}
          language="sql"
          path={`sqlteacher://sql/${connectionId || "none"}`}
          value={sql}
          onChange={(value) => onSqlChange(value ?? "")}
          options={{
            automaticLayout: true,
            minimap: { enabled: false },
            fontFamily: "'Cascadia Code', Consolas, monospace",
            fontSize: 14,
            padding: { top: 16 },
            scrollBeyondLastLine: false,
          }}
        />
      </div>
      {risk && (
        <div className={`risk-strip risk-${risk.level.toLowerCase()}`}>
          <strong>
            {risk.level} · {risk.statementType}
          </strong>
          <span>
            {risk.executable
              ? risk.confirmationRequired
                ? "需要明确确认"
                : "允许执行"
              : "Java 已阻止"}
          </span>
          {risk.reasons.map((reason) => (
            <small key={reason}>{reason}</small>
          ))}
        </div>
      )}
      {(analyze.isError || execute.isError || explainPlan.isError) && (
        <Feedback tone="error" title="SQL 未执行">
          {(analyze.error ?? execute.error ?? explainPlan.error)?.message}
        </Feedback>
      )}
      <SqlResults
        page={page}
        pending={execute.isPending || nextPage.isPending}
        onPage={(value) => nextPage.mutate(value)}
      />
      <details
        className="sql-history"
        open={historyOpen}
        onToggle={(event) => setHistoryOpen((event.target as HTMLDetailsElement).open)}
      >
        <summary>
          <strong>执行历史</strong>
          <small> · 本机最近 30 条</small>
        </summary>
        {history.data?.items.length ? (
          <div className="button-row">
            <Button
              variant="secondary"
              busy={clearHistory.isPending}
              onClick={() => clearHistory.mutate()}
            >
              清空历史
            </Button>
          </div>
        ) : (
          <p className="muted">执行过的语句会保存在本机，点击即可找回。</p>
        )}
        {history.isError && (
          <Feedback tone="error" title="历史读取失败">
            {history.error.message}
          </Feedback>
        )}
        <ul className="plain-list history-list">
          {history.data?.items.map((item) => (
            <li key={`${item.createdAt}-${item.connectionId}`}>
              <button
                type="button"
                onClick={() => {
                  onSqlChange(item.sql);
                  toast("success", "语句已填入编辑器");
                }}
              >
                <span
                  className={`history-dot ${item.successful ? "ok" : "bad"}`}
                  aria-hidden="true"
                />
                <span className="history-meta">
                  {formatInstant(item.createdAt)} · {item.connectionName || item.connectionId} ·{" "}
                  {item.successful ? `${item.rowCount} 行` : "失败"} · {item.durationMillis} ms
                </span>
                <code>{item.sql.length > 160 ? `${item.sql.slice(0, 160)}…` : item.sql}</code>
              </button>
            </li>
          ))}
        </ul>
      </details>
      <Dialog
        open={confirmOpen}
        title="确认高风险 SQL"
        onClose={() => {
          setConfirmOpen(false);
          setConfirmStatement(undefined);
        }}
      >
        <p>以下风险由 Java 分析器判定；令牌将在五分钟后过期且只能使用一次。</p>
        <ul>
          {risk?.reasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
        <div className="button-row">
          <Button
            variant="secondary"
            onClick={() => {
              setConfirmOpen(false);
              setConfirmStatement(undefined);
            }}
          >
            取消
          </Button>
          <Button
            variant="danger"
            onClick={() =>
              execute.mutate({
                statement: confirmStatement ?? sql,
                confirmationToken: risk?.confirmationToken ?? "",
              })
            }
          >
            确认执行
          </Button>
        </div>
      </Dialog>
    </section>
  );
}

function SqlResults({
  page,
  pending,
  onPage,
}: {
  page?: SqlPage;
  pending: boolean;
  onPage: (page: number) => void;
}) {
  const toast = useToast();
  const [exporting, setExporting] = useState(false);
  const exportCsv = async () => {
    if (!page) return;
    setExporting(true);
    try {
      const { save } = await import("@tauri-apps/plugin-dialog");
      const target = await save({
        title: "导出查询结果为 CSV",
        defaultPath: `sqlteacher-结果-${new Date().toISOString().slice(0, 10)}.csv`,
        filters: [{ name: "CSV 文件", extensions: ["csv"] }],
      });
      if (!target || typeof target !== "string") return;
      const exported = await localAppRequest<{ rows: number; columns: number }>(
        "sql.result.export",
        { resultId: page.resultId, path: target },
      );
      toast("success", `已导出 ${exported.rows} 行 × ${exported.columns} 列`);
    } catch (error) {
      toast("error", `导出失败：${error instanceof Error ? error.message : "未知错误"}`);
    } finally {
      setExporting(false);
    }
  };
  if (!page) return <div className="result-empty">暂无结果</div>;
  return (
    <section className="result-panel">
      <div className="section-heading">
        <h3>
          结果 · {page.totalRows} 行{page.truncated ? "（已截断）" : ""}
        </h3>
        <span className="safe-chip">
          {page.auditRecorded ? "已记录审计" : "审计未知"} · {page.durationMillis} ms
        </span>
      </div>
      {page.columns.length ? (
        <div className="virtual-table" role="region" aria-label="SQL 分页结果" tabIndex={0}>
          <table>
            <thead>
              <tr>
                {page.columns.map((column) => (
                  <th key={column}>{column}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {page.rows.map((row, index) => (
                <tr key={`${page.page}-${index}`}>
                  {page.columns.map((column) => (
                    <td key={column}>{String(row[column] ?? "NULL")}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <Feedback tone="success" title="语句执行成功">
          影响 {page.affectedRows} 行。{page.message}
        </Feedback>
      )}
      <footer className="pager">
        <span>
          <Button
            variant="secondary"
            busy={exporting}
            disabled={!page.columns.length}
            onClick={() => void exportCsv()}
          >
            导出 CSV
          </Button>
        </span>
        <Button
          variant="secondary"
          disabled={pending || page.page === 0}
          onClick={() => onPage(page.page - 1)}
        >
          上一页
        </Button>
        <span>第 {page.page + 1} 页</span>
        <Button
          variant="secondary"
          disabled={pending || !page.hasMore}
          onClick={() => onPage(page.page + 1)}
        >
          下一页
        </Button>
      </footer>
    </section>
  );
}

function AiAssistant({
  connectionId,
  onDraft,
}: {
  connectionId: string;
  onDraft: (sql: string) => void;
}) {
  const [question, setQuestion] = useState("");
  const [preview, setPreview] = useState<AiContextPreview>();
  const [requestId, setRequestId] = useState<string>();
  const [result, setResult] = useState<Nl2SqlSafetyResult>();
  // v3.4.4：生成一步完成——先在本地构建上下文（不发任何网络请求），再生成草稿；
  // 组装明细折叠收纳，未配置 AI 时由本地确定性生成兜底。
  const generate = useMutation({
    mutationFn: async () => {
      const id = crypto.randomUUID();
      setRequestId(id);
      const context = await localAppRequest<AiContextPreview>("ai.sql.preview", {
        connectionId,
        question,
      });
      setPreview(context);
      return localAppRequestWithId<Nl2SqlSafetyResult>(
        "ai.sql.generate",
        { connectionId, question },
        id,
      );
    },
    onSuccess: (value) => {
      setResult(value);
    },
    onSettled: () => setRequestId(undefined),
  });
  return (
    <section className="content-card ai-panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">仅生成草稿</p>
          <h2>自然语言生成 SQL</h2>
        </div>
        {generate.isPending && requestId && (
          <Button variant="danger" onClick={() => void cancelLocalAppRequest(requestId)}>
            取消
          </Button>
        )}
      </div>
      <FormField label="查询目标" hint="生成时在本地组装上下文（仅结构信息，不含表数据）；未配置 AI 时由本地确定性生成">
        {(ids) => (
          <textarea
            {...ids}
            value={question}
            onChange={(event) => {
              setQuestion(event.target.value);
              setPreview(undefined);
              setResult(undefined);
            }}
            maxLength={2000}
          />
        )}
      </FormField>
      <div className="button-row">
        <Button
          disabled={!connectionId || question.trim().length < 2 || generate.isPending}
          busy={generate.isPending}
          onClick={() => generate.mutate()}
        >
          生成 SQL 草稿
        </Button>
      </div>
      {preview && (
        <details className="ai-context-details">
          <summary>本次使用的上下文：{preview.characterCount} 个字符（本地组装，不会外发）</summary>
          <p>类别：{preview.categories.join("、") || "无"}</p>
          <p>来源：{preview.sources.join("、") || "无"}</p>
          <p>脱敏：{preview.redactions.join("、") || "无需额外脱敏"}</p>
        </details>
      )}
      {generate.isError && (
        <Feedback tone="error" title="AI SQL 未生成，数据库未执行">
          {generate.error?.message}
        </Feedback>
      )}
      {result && (
        <div className="ai-answer">
          <pre>{result.plan.sqlDraft || "（模型没有返回 SQL 草稿）"}</pre>
          <p>{result.plan.explanation}</p>
          <div className={`risk-strip risk-${result.riskAnalysis.level.toLowerCase()}`}>
            <strong>
              {result.riskAnalysis.level} · {result.riskAnalysis.statementType}
            </strong>
            <span>{result.accepted ? "可复制到工作台" : "Java 安全门禁未接受此草稿"}</span>
            {result.riskAnalysis.reasons.map((reason) => (
              <small key={reason}>{reason}</small>
            ))}
          </div>
          <Button disabled={!result.accepted} onClick={() => onDraft(result.plan.sqlDraft)}>
            复制到 SQL 工作台
          </Button>
        </div>
      )}
    </section>
  );
}
