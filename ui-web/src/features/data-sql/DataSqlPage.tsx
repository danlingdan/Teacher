import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import { useEffect, useState } from "react";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { cancelLocalAppRequest, localAppRequest, localAppRequestWithId } from "../../shared/ipc";
import type { AiContextPreview, ConnectionDialectOption, ConnectionSummary, ConnectionTestResult, DatabaseTable, Nl2SqlSafetyResult, SqlHistoryItem, SqlPage, SqlRisk } from "../../shared/types";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

// 补全 provider 全局只注册一次，当前连接的表/列符号经模块槽位刷新，
// 避免每次挂载工作台都向 sql 语言累积一个 provider。
let sqlWorkbenchSymbols: string[] = [];
monaco.languages.registerCompletionItemProvider("sql", {
  triggerCharacters: ["."],
  provideCompletionItems: (model, position) => {
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
const initialSql = "SELECT name, type\nFROM sqlite_master\nWHERE type IN ('table', 'view')\nORDER BY name\nLIMIT 100;";

export default function DataSqlPage() {
  const client = useQueryClient();
  const connections = useQuery({ queryKey: ["data", "connections"], queryFn: () => localAppRequest<{ items: ConnectionSummary[] }>("data.connections") });
  const dialectOptions = useConnectionDialects();
  const [connectionId, setConnectionId] = useState("");
  const [sql, setSql] = useState(initialSql);
  useEffect(() => { if (!connectionId && connections.data?.items.length) setConnectionId((connections.data.items.find(item => item.selected) ?? connections.data.items[0]).id); }, [connectionId, connections.data]);
  const schema = useQuery({ queryKey: ["data", "schema", connectionId], queryFn: () => localAppRequest<{ tables: DatabaseTable[] }>("data.schema", { connectionId }), enabled: Boolean(connectionId) });
  return <div className="data-workspace">
    <aside className="content-card schema-panel"><p className="eyebrow">数据库连接</p><select aria-label="数据库连接" value={connectionId} onChange={event => setConnectionId(event.target.value)}>{connections.data?.items.map(item => <option key={item.id} value={item.id}>{item.displayName} · {dialectLabel(dialectOptions, item.dialect)}{item.readOnly ? " · 只读" : ""}</option>)}</select><ConnectionManager items={connections.data?.items ?? []} selectedId={connectionId} dialectOptions={dialectOptions} onSelected={id => { setConnectionId(id); void client.invalidateQueries({ queryKey: ["data", "connections"] }); }} /><div className="schema-tree">{schema.data?.tables.map(table => <details key={table.name} open><summary>{table.name}</summary><ul>{table.columns.map(column => <li key={column.name}><strong>{column.name}</strong><span>{column.typeName}{column.primaryKey ? " · PK" : ""}{column.nullable ? "" : " · NOT NULL"}</span></li>)}</ul></details>)}</div>{schema.isError && <Feedback tone="error" title="结构读取失败">{schema.error.message}</Feedback>}</aside>
    <main className="data-main"><SqlWorkbench connectionId={connectionId} tables={schema.data?.tables ?? []} sql={sql} onSqlChange={setSql} /><AiAssistant connectionId={connectionId} onDraft={setSql} /></main>
  </div>;
}

type ConnectionDraft = {
  id: string; displayName: string; dialect: string; databasePath: string; host: string; port: string;
  databaseName: string; username: string; password: string; jdbcUrl: string; driverClass: string;
  driverJar: string; readOnly: boolean; enabled: boolean;
};
const emptyConnection = (): ConnectionDraft => ({ id: "", displayName: "", dialect: "SQLITE", databasePath: "", host: "localhost", port: "", databaseName: "", username: "", password: "", jdbcUrl: "", driverClass: "", driverJar: "", readOnly: false, enabled: true });
// 方言元数据由 Java 侧 data.connection.dialects 提供；请求失败时退化为裸枚举名，表单仍可用。
const FALLBACK_DIALECTS: ConnectionDialectOption[] = [
  { name: "SQLITE", displayName: "SQLite", defaultPort: 0, fileBased: true, generic: false },
  { name: "DUCKDB", displayName: "DuckDB", defaultPort: 0, fileBased: true, generic: false },
  { name: "H2", displayName: "H2", defaultPort: 0, fileBased: true, generic: false },
  { name: "MYSQL", displayName: "MySQL", defaultPort: 3306, fileBased: false, generic: false },
  { name: "MARIADB", displayName: "MariaDB", defaultPort: 3306, fileBased: false, generic: false },
  { name: "POSTGRESQL", displayName: "PostgreSQL", defaultPort: 5432, fileBased: false, generic: false },
  { name: "SQL_SERVER", displayName: "SQL Server", defaultPort: 1433, fileBased: false, generic: false },
  { name: "ORACLE", displayName: "Oracle", defaultPort: 1521, fileBased: false, generic: false },
  { name: "DB2", displayName: "Db2", defaultPort: 50000, fileBased: false, generic: false },
  { name: "DAMENG", displayName: "达梦 DM8", defaultPort: 5236, fileBased: false, generic: false },
  { name: "TIDB", displayName: "TiDB", defaultPort: 4000, fileBased: false, generic: false },
  { name: "OCEANBASE", displayName: "OceanBase", defaultPort: 2881, fileBased: false, generic: false },
  { name: "GAUSSDB", displayName: "GaussDB", defaultPort: 5432, fileBased: false, generic: false },
  { name: "GENERIC", displayName: "通用 JDBC", defaultPort: 0, fileBased: false, generic: true },
];
const dialectLabel = (options: ConnectionDialectOption[], name: string) => options.find(option => option.name === name)?.displayName ?? name;

function useConnectionDialects(): ConnectionDialectOption[] {
  const query = useQuery({
    queryKey: ["data", "connection-dialects"],
    queryFn: () => localAppRequest<{ items: ConnectionDialectOption[] }>("data.connection.dialects"),
    staleTime: Infinity,
  });
  return query.data?.items.length ? query.data.items : FALLBACK_DIALECTS;
}

// 连接 ID 由显示名生成小写别名加随机后缀，匹配 Java 侧 [a-z0-9][a-z0-9._-]{0,63}。
function connectionIdFrom(displayName: string): string {
  const slug = displayName.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "").slice(0, 48);
  const token = Math.random().toString(36).slice(2, 6);
  return `${slug || "connection"}-${token}`;
}

function defaultDisplayName(draft: ConnectionDraft, dialect?: ConnectionDialectOption): string {
  const label = dialect?.displayName ?? draft.dialect;
  const detail = dialect?.fileBased
    ? draft.databasePath.split(/[\\/]/).pop()
    : draft.databaseName.trim() || draft.host.trim();
  return detail ? `${label} ${detail}` : `${label} 连接`;
}

function missingConnectionFields(draft: ConnectionDraft, dialect?: ConnectionDialectOption): string {
  if (dialect?.fileBased) return draft.databasePath.trim() ? "" : "数据库文件路径";
  if (dialect?.generic) return [draft.jdbcUrl, draft.driverClass, draft.driverJar].every(value => value.trim()) ? "" : "JDBC URL、驱动类与驱动 JAR";
  return [draft.host, draft.databaseName, draft.username].every(value => value.trim()) ? "" : "主机、数据库与用户名";
}

function ConnectionManager({ items, selectedId, dialectOptions, onSelected }: { items: ConnectionSummary[]; selectedId: string; dialectOptions: ConnectionDialectOption[]; onSelected: (id: string) => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [draft, setDraft] = useState<ConnectionDraft>(emptyConnection);
  const [result, setResult] = useState<ConnectionTestResult>();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [panelOpen, setPanelOpen] = useState(items.length === 0);
  // 一个连接都没有时自动展开，避免新用户找不到入口。
  useEffect(() => { if (items.length === 0) setPanelOpen(true); }, [items.length]);
  const current = items.find(item => item.id === selectedId);
  const dialect = dialectOptions.find(option => option.name === draft.dialect);
  const fileBased = dialect?.fileBased ?? false;
  const generic = dialect?.generic ?? false;
  const refresh = () => client.invalidateQueries({ queryKey: ["data", "connections"] });
  // 连接 ID 与显示名称留空时自动生成并回写表单：同一次提交里测试与保存必须用同一个 ID，
  // 否则测试成功暂存的凭据会挂在另一个 ID 下。
  const submitPayload = () => {
    const displayName = draft.displayName.trim() || defaultDisplayName(draft, dialect);
    const id = draft.id.trim() || connectionIdFrom(displayName);
    if (id !== draft.id || displayName !== draft.displayName) {
      setDraft(value => ({ ...value, id, displayName }));
    }
    return { ...draft, id, displayName, port: Number(draft.port || 0) };
  };
  const save = useMutation({
    mutationFn: async () => {
      const payload = submitPayload();
      // 保存前强制真实连接测试：失败的连接不会被保存。
      const tested = await localAppRequest<ConnectionTestResult>("data.connection.test", { ...payload, password: draft.password });
      if (!tested.successful) {
        setResult(tested);
        throw new Error(tested.message);
      }
      setResult(tested);
      return localAppRequest<ConnectionSummary>("data.connection.save", payload);
    },
    onSuccess: value => { setDraft(valueToDraft(value)); onSelected(value.id); void refresh(); toast("success", "连接已保存"); },
    onError: (error: Error) => toast("error", `连接保存失败：${error.message}`),
    onSettled: () => setDraft(value => ({ ...value, password: "" })),
  });
  // 测试成功后清空表单密码是刻意的：Java 侧 DatabaseCredentialSession 已记住本次凭据，
  // 保存时用空密码即可；避免密码长期留在前端表单状态里。
  const test = useMutation({ mutationFn: () => localAppRequest<ConnectionTestResult>("data.connection.test", { ...submitPayload(), password: draft.password }), onSuccess: setResult, onSettled: () => setDraft(value => ({ ...value, password: "" })), onError: (error: Error) => toast("error", `连接测试失败：${error.message}`) });
  const select = useMutation({ mutationFn: () => localAppRequest<ConnectionSummary>("data.connection.select", { connectionId: selectedId }), onSuccess: value => { onSelected(value.id); void refresh(); } });
  const remove = useMutation({ mutationFn: () => localAppRequest("data.connection.delete", { connectionId: selectedId }), onSuccess: async () => { setDeleteOpen(false); setDraft(emptyConnection()); await refresh(); const remaining = items.find(item => item.id !== selectedId); if (remaining) onSelected(remaining.id); } });
  const browseDatabaseFile = async () => {
    try {
      const { open } = await import("@tauri-apps/plugin-dialog");
      const selection = await open({
        multiple: false,
        directory: false,
        title: "选择数据库文件",
        filters: [
          { name: "数据库文件", extensions: ["db", "sqlite", "sqlite3", "duckdb", "mv.db"] },
          { name: "所有文件", extensions: ["*"] },
        ],
      });
      if (typeof selection === "string" && selection.trim()) {
        setDraft(value => ({ ...value, databasePath: selection }));
        setResult(undefined);
      }
    } catch {
      toast("error", "无法打开文件选择器，请直接输入文件路径");
    }
  };
  const edit = (item?: ConnectionSummary) => { setDraft(item ? valueToDraft(item) : emptyConnection()); setResult(undefined); };
  const copySelected = () => {
    if (!current) return;
    const copied = valueToDraft(current);
    setDraft({ ...copied, id: "", displayName: `${copied.displayName} 副本` });
    setResult(undefined);
    toast("success", "已复制配置，确认后点“测试并保存”");
  };
  const missing = missingConnectionFields(draft, dialect);
  const busy = save.isPending || test.isPending;
  return <details className="connection-manager" open={panelOpen} onToggle={event => setPanelOpen((event.target as HTMLDetailsElement).open)}>
    <summary><strong>管理连接</strong>{items.length === 0 && <small> · 暂无连接</small>}</summary>
    {items.length === 0 && <p className="muted">选择数据库类型，填好地址后点“测试并保存”。</p>}
    <div className="button-row"><Button variant="secondary" onClick={() => edit()}>新建</Button>{current && <Button variant="secondary" onClick={() => edit(current)}>编辑所选</Button>}{current && !current.builtIn && <Button variant="secondary" onClick={copySelected}>复制</Button>}<Button variant="secondary" disabled={!current?.enabled || select.isPending} onClick={() => select.mutate()}>设为当前</Button>{current && !current.builtIn && <Button variant="danger" onClick={() => setDeleteOpen(true)}>删除</Button>}</div>
    <div className="settings-grid"><FormField label="显示名称" hint="留空会自动生成">{ids => <input {...ids} value={draft.displayName} onChange={event => setDraft({ ...draft, displayName: event.target.value })} placeholder="留空自动生成" />}</FormField><FormField label="数据库类型">{ids => <select {...ids} value={draft.dialect} onChange={event => { const next = dialectOptions.find(option => option.name === event.target.value); setDraft({ ...draft, dialect: event.target.value, port: next && next.defaultPort > 0 ? String(next.defaultPort) : "" }); }}>{dialectOptions.map(option => <option key={option.name} value={option.name}>{option.displayName}</option>)}</select>}</FormField>
      {fileBased ? <FormField label="数据库文件" hint={draft.dialect === "SQLITE" ? "文件不存在时会创建新的空数据库" : undefined}>{ids => <div className="button-row"><input {...ids} value={draft.databasePath} onChange={event => setDraft({ ...draft, databasePath: event.target.value })} placeholder="选择或输入文件路径" /><Button variant="secondary" disabled={busy} onClick={() => void browseDatabaseFile()}>浏览…</Button></div>}</FormField> : generic ? <><FormField label="JDBC URL">{ids => <input {...ids} value={draft.jdbcUrl} onChange={event => setDraft({ ...draft, jdbcUrl: event.target.value })} />}</FormField><FormField label="驱动类">{ids => <input {...ids} value={draft.driverClass} onChange={event => setDraft({ ...draft, driverClass: event.target.value })} />}</FormField><FormField label="驱动 JAR">{ids => <input {...ids} value={draft.driverJar} onChange={event => setDraft({ ...draft, driverJar: event.target.value })} />}</FormField></> : <><FormField label="主机">{ids => <input {...ids} value={draft.host} onChange={event => setDraft({ ...draft, host: event.target.value })} />}</FormField><FormField label="端口" hint="通常保持默认即可">{ids => <input {...ids} type="number" value={draft.port} onChange={event => setDraft({ ...draft, port: event.target.value })} placeholder={dialect && dialect.defaultPort > 0 ? `默认 ${dialect.defaultPort}` : "默认"} />}</FormField><FormField label="数据库">{ids => <input {...ids} value={draft.databaseName} onChange={event => setDraft({ ...draft, databaseName: event.target.value })} />}</FormField></>}
      {!fileBased && <><FormField label="用户名">{ids => <input {...ids} autoComplete="username" value={draft.username} onChange={event => setDraft({ ...draft, username: event.target.value })} />}</FormField><FormField label="本次测试密码" hint="只保存在当前 Java 进程内存中；编辑时留空会复用已验证的密码">{ids => <input {...ids} type="password" autoComplete="new-password" value={draft.password} onChange={event => setDraft({ ...draft, password: event.target.value })} />}</FormField></>}
      <label className="setting-toggle"><input type="checkbox" checked={draft.readOnly} onChange={event => setDraft({ ...draft, readOnly: event.target.checked })} /><span><strong>只读连接</strong></span></label><label className="setting-toggle"><input type="checkbox" checked={draft.enabled} onChange={event => setDraft({ ...draft, enabled: event.target.checked })} /><span><strong>启用连接</strong></span></label></div>
    <details className="connection-advanced"><summary>高级</summary><FormField label="连接 ID" hint="留空自动生成；仅小写字母、数字、点、横线或下划线">{ids => <input {...ids} disabled={current?.builtIn} value={draft.id} onChange={event => setDraft({ ...draft, id: event.target.value })} placeholder="留空自动生成" />}</FormField></details>
    {missing && <p className="field-gap-note">还需填写：{missing}</p>}
    <div className="button-row"><Button variant="secondary" busy={test.isPending} disabled={busy || Boolean(missing)} onClick={() => test.mutate()}>仅测试</Button><Button busy={save.isPending} disabled={busy || Boolean(missing)} onClick={() => save.mutate()}>测试并保存</Button></div>
    {result && <Feedback tone={result.successful ? "success" : "warning"} title={result.successful ? "连接成功" : "连接失败"}>{result.message}{result.databaseProduct ? ` · ${result.databaseProduct} ${result.databaseVersion}` : ""}{result.successful && !fileBased ? " 密码已暂存，可直接保存。" : ""}</Feedback>}
    {(save.isError || test.isError || select.isError || remove.isError) && <Feedback tone="error" title="连接操作失败">{(save.error ?? test.error ?? select.error ?? remove.error)?.message}</Feedback>}
    <Dialog open={deleteOpen} title="删除数据库连接" onClose={() => setDeleteOpen(false)}><p>确认删除“{current?.displayName}”？密码缓存会同时清除。</p><div className="button-row"><Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button><Button variant="danger" onClick={() => remove.mutate()}>确认删除</Button></div></Dialog>
  </details>;
}

function valueToDraft(item: ConnectionSummary): ConnectionDraft { return { ...emptyConnection(), ...item, port: item.port ? String(item.port) : "", databasePath: item.databasePath ?? "", host: item.host ?? "localhost", databaseName: item.databaseName ?? "", username: item.username ?? "", jdbcUrl: item.jdbcUrl ?? "", driverClass: item.driverClass ?? "", driverJar: item.driverJar ?? "", password: "" }; }

function SqlWorkbench({ connectionId, tables, sql, onSqlChange }: { connectionId: string; tables: DatabaseTable[]; sql: string; onSqlChange: (sql: string) => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [risk, setRisk] = useState<SqlRisk>();
  const [page, setPage] = useState<SqlPage>();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const execute = useMutation<SqlPage, Error, string>({ mutationFn: confirmationToken => localAppRequest<SqlPage>("sql.execute", { connectionId, sql, confirmationToken, maxRows: 500, pageSize: 50 }), onSuccess: value => { setPage(value); setConfirmOpen(false); void client.invalidateQueries({ queryKey: ["sql", "history"] }); } });
  const analyze = useMutation<SqlRisk, Error, void>({ mutationFn: () => localAppRequest<SqlRisk>("sql.analyze", { connectionId, sql }), onSuccess: value => { setRisk(value); if (value.executable && value.confirmationRequired) setConfirmOpen(true); else if (value.executable) execute.mutate(""); } });
  const nextPage = useMutation<SqlPage, Error, number>({ mutationFn: next => localAppRequest<SqlPage>("sql.result.page", { resultId: page?.resultId, page: next, pageSize: 50 }), onSuccess: setPage });
  const history = useQuery({ queryKey: ["sql", "history"], queryFn: () => localAppRequest<{ items: SqlHistoryItem[] }>("sql.history", { limit: 30 }), enabled: historyOpen });
  const clearHistory = useMutation({ mutationFn: () => localAppRequest("sql.history.clear", {}), onSuccess: () => { void client.invalidateQueries({ queryKey: ["sql", "history"] }); toast("success", "执行历史已清空"); }, onError: (error: Error) => toast("error", `清空失败：${error.message}`) });
  const names = tables.flatMap(table => [table.name, ...table.columns.map(column => column.name)]);
  sqlWorkbenchSymbols = names;
  return <section className="content-card sql-workbench"><header className="editor-toolbar"><div><p className="eyebrow">执行策略</p><h2>SQL 工作台</h2></div><div className="button-row"><span className="policy-chip">最多 500 行 · 10 秒</span><Button disabled={!connectionId || analyze.isPending || execute.isPending} onClick={() => analyze.mutate()}>分析并运行</Button></div></header><div className="sql-editor"><Editor height="100%" language="sql" path={`sqlteacher://sql/${connectionId || "none"}`} value={sql} onChange={value => onSqlChange(value ?? "")} options={{ automaticLayout: true, minimap: { enabled: false }, fontFamily: "'Cascadia Code', Consolas, monospace", fontSize: 14, padding: { top: 16 }, scrollBeyondLastLine: false }} /></div>
    {risk && <div className={`risk-strip risk-${risk.level.toLowerCase()}`}><strong>{risk.level} · {risk.statementType}</strong><span>{risk.executable ? risk.confirmationRequired ? "需要明确确认" : "允许执行" : "Java 已阻止"}</span>{risk.reasons.map(reason => <small key={reason}>{reason}</small>)}</div>}
    {(analyze.isError || execute.isError) && <Feedback tone="error" title="SQL 未执行">{(analyze.error ?? execute.error)?.message}</Feedback>}
    <SqlResults page={page} pending={execute.isPending || nextPage.isPending} onPage={value => nextPage.mutate(value)} />
    <details className="sql-history" open={historyOpen} onToggle={event => setHistoryOpen((event.target as HTMLDetailsElement).open)}>
      <summary><strong>执行历史</strong><small> · 本机最近 30 条</small></summary>
      {history.data?.items.length ? <div className="button-row"><Button variant="secondary" busy={clearHistory.isPending} onClick={() => clearHistory.mutate()}>清空历史</Button></div> : <p className="muted">执行过的语句会保存在本机，点击即可找回。</p>}
      {history.isError && <Feedback tone="error" title="历史读取失败">{history.error.message}</Feedback>}
      <ul className="plain-list history-list">
        {history.data?.items.map(item => (
          <li key={`${item.createdAt}-${item.connectionId}`}>
            <button type="button" onClick={() => { onSqlChange(item.sql); toast("success", "语句已填入编辑器"); }}>
              <span className={`history-dot ${item.successful ? "ok" : "bad"}`} aria-hidden="true" />
              <span className="history-meta">{new Date(item.createdAt).toLocaleString()} · {item.connectionName || item.connectionId} · {item.successful ? `${item.rowCount} 行` : "失败"} · {item.durationMillis} ms</span>
              <code>{item.sql.length > 160 ? `${item.sql.slice(0, 160)}…` : item.sql}</code>
            </button>
          </li>
        ))}
      </ul>
    </details>
    <Dialog open={confirmOpen} title="确认高风险 SQL" onClose={() => setConfirmOpen(false)}><p>以下风险由 Java 分析器判定；令牌将在五分钟后过期且只能使用一次。</p><ul>{risk?.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul><div className="button-row"><Button variant="secondary" onClick={() => setConfirmOpen(false)}>取消</Button><Button variant="danger" onClick={() => execute.mutate(risk?.confirmationToken ?? "")}>确认执行</Button></div></Dialog>
  </section>;
}

function SqlResults({ page, pending, onPage }: { page?: SqlPage; pending: boolean; onPage: (page: number) => void }) {
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
      const exported = await localAppRequest<{ rows: number; columns: number }>("sql.result.export", { resultId: page.resultId, path: target });
      toast("success", `已导出 ${exported.rows} 行 × ${exported.columns} 列`);
    } catch (error) {
      toast("error", `导出失败：${error instanceof Error ? error.message : "未知错误"}`);
    } finally {
      setExporting(false);
    }
  };
  if (!page) return <div className="result-empty">暂无结果</div>;
  return <section className="result-panel"><div className="section-heading"><h3>结果 · {page.totalRows} 行{page.truncated ? "（已截断）" : ""}</h3><span className="safe-chip">{page.auditRecorded ? "已记录审计" : "审计未知"} · {page.durationMillis} ms</span></div>{page.columns.length ? <div className="virtual-table" role="region" aria-label="SQL 分页结果" tabIndex={0}><table><thead><tr>{page.columns.map(column => <th key={column}>{column}</th>)}</tr></thead><tbody>{page.rows.map((row, index) => <tr key={`${page.page}-${index}`}>{page.columns.map(column => <td key={column}>{String(row[column] ?? "NULL")}</td>)}</tr>)}</tbody></table></div> : <Feedback tone="success" title="语句执行成功">影响 {page.affectedRows} 行。{page.message}</Feedback>}<footer className="pager"><span><Button variant="secondary" busy={exporting} disabled={!page.columns.length} onClick={() => void exportCsv()}>导出 CSV</Button></span><Button variant="secondary" disabled={pending || page.page === 0} onClick={() => onPage(page.page - 1)}>上一页</Button><span>第 {page.page + 1} 页</span><Button variant="secondary" disabled={pending || !page.hasMore} onClick={() => onPage(page.page + 1)}>下一页</Button></footer></section>;
}

function AiAssistant({ connectionId, onDraft }: { connectionId: string; onDraft: (sql: string) => void }) {
  const [question, setQuestion] = useState("");
  const [preview, setPreview] = useState<AiContextPreview>();
  const [requestId, setRequestId] = useState<string>();
  const [result, setResult] = useState<Nl2SqlSafetyResult>();
  const inspect = useMutation({ mutationFn: () => localAppRequest<AiContextPreview>("ai.sql.preview", { connectionId, question }), onSuccess: value => { setPreview(value); setResult(undefined); } });
  const generate = useMutation({ mutationFn: async () => { const id = crypto.randomUUID(); setRequestId(id); return localAppRequestWithId<Nl2SqlSafetyResult>("ai.sql.generate", { connectionId, question }, id); }, onSuccess: setResult, onSettled: () => setRequestId(undefined) });
  return <section className="content-card ai-panel"><div className="section-heading"><div><p className="eyebrow">仅生成草稿</p><h2>自然语言生成 SQL</h2></div>{generate.isPending && requestId && <Button variant="danger" onClick={() => void cancelLocalAppRequest(requestId)}>取消</Button>}</div><FormField label="查询目标" hint="生成前先预览上下文">{ids => <textarea {...ids} value={question} onChange={event => { setQuestion(event.target.value); setPreview(undefined); setResult(undefined); }} maxLength={2000} />}</FormField><div className="button-row"><Button variant="secondary" disabled={!connectionId || question.trim().length < 2 || inspect.isPending} onClick={() => inspect.mutate()}>预览 AI 上下文</Button>{preview && <Button disabled={generate.isPending} onClick={() => generate.mutate()}>确认并生成草稿</Button>}</div>{preview && <Feedback tone="info" title={`将发送 ${preview.characterCount} 个字符`}><p>类别：{preview.categories.join("、") || "无"}</p><p>来源：{preview.sources.join("、") || "无"}</p><p>脱敏：{preview.redactions.join("、") || "无需额外脱敏"}</p></Feedback>}{(inspect.isError || generate.isError) && <Feedback tone="error" title="AI SQL 未生成，数据库未执行">{(inspect.error ?? generate.error)?.message}</Feedback>}{result && <div className="ai-answer"><pre>{result.plan.sqlDraft || "（模型没有返回 SQL 草稿）"}</pre><p>{result.plan.explanation}</p><div className={`risk-strip risk-${result.riskAnalysis.level.toLowerCase()}`}><strong>{result.riskAnalysis.level} · {result.riskAnalysis.statementType}</strong><span>{result.accepted ? "可复制到工作台" : "Java 安全门禁未接受此草稿"}</span>{result.riskAnalysis.reasons.map(reason => <small key={reason}>{reason}</small>)}</div><Button disabled={!result.accepted} onClick={() => onDraft(result.plan.sqlDraft)}>复制到 SQL 工作台</Button></div>}</section>;
}
