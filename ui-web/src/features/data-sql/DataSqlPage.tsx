import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import { useEffect, useState } from "react";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { cancelLocalAppRequest, localAppRequest, localAppRequestWithId } from "../../shared/ipc";
import type { AiContextPreview, ConnectionSummary, ConnectionTestResult, DatabaseTable, Nl2SqlSafetyResult, SqlPage, SqlRisk } from "../../shared/types";

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
  const [connectionId, setConnectionId] = useState("");
  const [sql, setSql] = useState(initialSql);
  useEffect(() => { if (!connectionId && connections.data?.items.length) setConnectionId((connections.data.items.find(item => item.selected) ?? connections.data.items[0]).id); }, [connectionId, connections.data]);
  const schema = useQuery({ queryKey: ["data", "schema", connectionId], queryFn: () => localAppRequest<{ tables: DatabaseTable[] }>("data.schema", { connectionId }), enabled: Boolean(connectionId) });
  return <div className="data-workspace">
    <aside className="content-card schema-panel"><p className="eyebrow">数据库连接</p><select aria-label="数据库连接" value={connectionId} onChange={event => setConnectionId(event.target.value)}>{connections.data?.items.map(item => <option key={item.id} value={item.id}>{item.displayName} · {item.dialect}{item.readOnly ? " · 只读" : ""}</option>)}</select><ConnectionManager items={connections.data?.items ?? []} selectedId={connectionId} onSelected={id => { setConnectionId(id); void client.invalidateQueries({ queryKey: ["data", "connections"] }); }} /><div className="schema-tree">{schema.data?.tables.map(table => <details key={table.name} open><summary>{table.name}</summary><ul>{table.columns.map(column => <li key={column.name}><strong>{column.name}</strong><span>{column.typeName}{column.primaryKey ? " · PK" : ""}{column.nullable ? "" : " · NOT NULL"}</span></li>)}</ul></details>)}</div>{schema.isError && <Feedback tone="error" title="结构读取失败">{schema.error.message}</Feedback>}</aside>
    <main className="data-main"><SqlWorkbench connectionId={connectionId} tables={schema.data?.tables ?? []} sql={sql} onSqlChange={setSql} /><AiAssistant connectionId={connectionId} onDraft={setSql} /></main>
  </div>;
}

type ConnectionDraft = {
  id: string; displayName: string; dialect: string; databasePath: string; host: string; port: string;
  databaseName: string; username: string; password: string; jdbcUrl: string; driverClass: string;
  driverJar: string; readOnly: boolean; enabled: boolean;
};
const emptyConnection = (): ConnectionDraft => ({ id: "", displayName: "", dialect: "SQLITE", databasePath: "", host: "localhost", port: "", databaseName: "", username: "", password: "", jdbcUrl: "", driverClass: "", driverJar: "", readOnly: false, enabled: true });
const dialects = ["SQLITE", "DUCKDB", "H2", "MYSQL", "MARIADB", "POSTGRESQL", "SQL_SERVER", "ORACLE", "DB2", "DAMENG", "TIDB", "OCEANBASE", "GAUSSDB", "GENERIC"];

function ConnectionManager({ items, selectedId, onSelected }: { items: ConnectionSummary[]; selectedId: string; onSelected: (id: string) => void }) {
  const client = useQueryClient();
  const toast = useToast();
  const [draft, setDraft] = useState<ConnectionDraft>(emptyConnection);
  const [result, setResult] = useState<ConnectionTestResult>();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [panelOpen, setPanelOpen] = useState(items.length === 0);
  // 一个连接都没有时自动展开，避免新用户找不到入口。
  useEffect(() => { if (items.length === 0) setPanelOpen(true); }, [items.length]);
  const current = items.find(item => item.id === selectedId);
  const payload = { ...draft, port: Number(draft.port || 0) };
  const refresh = () => client.invalidateQueries({ queryKey: ["data", "connections"] });
  const save = useMutation({ mutationFn: () => localAppRequest<ConnectionSummary>("data.connection.save", payload), onSuccess: value => { setDraft(valueToDraft(value)); onSelected(value.id); void refresh(); toast("success", "连接已保存"); }, onError: (error: Error) => toast("error", `连接保存失败：${error.message}`) });
  // 测试成功后清空表单密码是刻意的：Java 侧 DatabaseCredentialSession 已记住本次凭据，
  // 保存时用空密码即可；避免密码长期留在前端表单状态里。
  const test = useMutation({ mutationFn: () => localAppRequest<ConnectionTestResult>("data.connection.test", payload), onSuccess: setResult, onSettled: () => setDraft(value => ({ ...value, password: "" })), onError: (error: Error) => toast("error", `连接测试失败：${error.message}`) });
  const select = useMutation({ mutationFn: () => localAppRequest<ConnectionSummary>("data.connection.select", { connectionId: selectedId }), onSuccess: value => { onSelected(value.id); void refresh(); } });
  const remove = useMutation({ mutationFn: () => localAppRequest("data.connection.delete", { connectionId: selectedId }), onSuccess: async () => { setDeleteOpen(false); setDraft(emptyConnection()); await refresh(); const remaining = items.find(item => item.id !== selectedId); if (remaining) onSelected(remaining.id); } });
  const fileBased = ["SQLITE", "DUCKDB", "H2"].includes(draft.dialect);
  const generic = draft.dialect === "GENERIC";
  const edit = (item?: ConnectionSummary) => { setDraft(item ? valueToDraft(item) : emptyConnection()); setResult(undefined); };
  return <details className="connection-manager" open={panelOpen} onToggle={event => setPanelOpen((event.target as HTMLDetailsElement).open)}>
    <summary><strong>管理连接</strong>{items.length === 0 && <small> · 暂无连接</small>}</summary>
    {items.length === 0 && <p className="muted">新建后先测试连接，再保存。</p>}
    <div className="button-row"><Button variant="secondary" onClick={() => edit()}>新建</Button>{current && <Button variant="secondary" onClick={() => edit(current)}>编辑所选</Button>}<Button variant="secondary" disabled={!current?.enabled || select.isPending} onClick={() => select.mutate()}>设为当前</Button>{current && !current.builtIn && <Button variant="danger" onClick={() => setDeleteOpen(true)}>删除</Button>}</div>
    <div className="settings-grid"><FormField label="连接 ID" hint="小写字母、数字、点、横线或下划线">{ids => <input {...ids} disabled={current?.builtIn} value={draft.id} onChange={event => setDraft({ ...draft, id: event.target.value })} />}</FormField><FormField label="显示名称">{ids => <input {...ids} value={draft.displayName} onChange={event => setDraft({ ...draft, displayName: event.target.value })} />}</FormField><FormField label="数据库类型">{ids => <select {...ids} value={draft.dialect} onChange={event => setDraft({ ...draft, dialect: event.target.value, port: "" })}>{dialects.map(item => <option key={item}>{item}</option>)}</select>}</FormField>
      {fileBased ? <FormField label="数据库文件">{ids => <input {...ids} value={draft.databasePath} onChange={event => setDraft({ ...draft, databasePath: event.target.value })} />}</FormField> : generic ? <><FormField label="JDBC URL">{ids => <input {...ids} value={draft.jdbcUrl} onChange={event => setDraft({ ...draft, jdbcUrl: event.target.value })} />}</FormField><FormField label="驱动类">{ids => <input {...ids} value={draft.driverClass} onChange={event => setDraft({ ...draft, driverClass: event.target.value })} />}</FormField><FormField label="驱动 JAR">{ids => <input {...ids} value={draft.driverJar} onChange={event => setDraft({ ...draft, driverJar: event.target.value })} />}</FormField></> : <><FormField label="主机">{ids => <input {...ids} value={draft.host} onChange={event => setDraft({ ...draft, host: event.target.value })} />}</FormField><FormField label="端口">{ids => <input {...ids} type="number" value={draft.port} onChange={event => setDraft({ ...draft, port: event.target.value })} />}</FormField><FormField label="数据库">{ids => <input {...ids} value={draft.databaseName} onChange={event => setDraft({ ...draft, databaseName: event.target.value })} />}</FormField></>}
      {!fileBased && <><FormField label="用户名">{ids => <input {...ids} autoComplete="username" value={draft.username} onChange={event => setDraft({ ...draft, username: event.target.value })} />}</FormField><FormField label="本次测试密码" hint="只保存在当前 Java 进程内存中">{ids => <input {...ids} type="password" autoComplete="new-password" value={draft.password} onChange={event => setDraft({ ...draft, password: event.target.value })} />}</FormField></>}
      <label className="setting-toggle"><input type="checkbox" checked={draft.readOnly} onChange={event => setDraft({ ...draft, readOnly: event.target.checked })} /><span><strong>只读连接</strong></span></label><label className="setting-toggle"><input type="checkbox" checked={draft.enabled} onChange={event => setDraft({ ...draft, enabled: event.target.checked })} /><span><strong>启用连接</strong></span></label></div>
    <div className="button-row"><Button variant="secondary" busy={test.isPending} disabled={!draft.id || !draft.displayName || test.isPending} onClick={() => test.mutate()}>测试连接</Button><Button busy={save.isPending} disabled={!draft.id || !draft.displayName || save.isPending} onClick={() => save.mutate()}>保存连接</Button></div>
    {result && <Feedback tone={result.successful ? "success" : "warning"} title={result.successful ? "连接成功" : "连接失败"}>{result.message}{result.databaseProduct ? ` · ${result.databaseProduct} ${result.databaseVersion}` : ""}{result.successful && !fileBased ? "。密码已暂存，可直接保存。" : ""}</Feedback>}
    {(save.isError || test.isError || select.isError || remove.isError) && <Feedback tone="error" title="连接操作失败">{(save.error ?? test.error ?? select.error ?? remove.error)?.message}</Feedback>}
    <Dialog open={deleteOpen} title="删除数据库连接" onClose={() => setDeleteOpen(false)}><p>确认删除“{current?.displayName}”？密码缓存会同时清除。</p><div className="button-row"><Button variant="secondary" onClick={() => setDeleteOpen(false)}>取消</Button><Button variant="danger" onClick={() => remove.mutate()}>确认删除</Button></div></Dialog>
  </details>;
}

function valueToDraft(item: ConnectionSummary): ConnectionDraft { return { ...emptyConnection(), ...item, port: item.port ? String(item.port) : "", databasePath: item.databasePath ?? "", host: item.host ?? "localhost", databaseName: item.databaseName ?? "", username: item.username ?? "", jdbcUrl: item.jdbcUrl ?? "", driverClass: item.driverClass ?? "", driverJar: item.driverJar ?? "", password: "" }; }

function SqlWorkbench({ connectionId, tables, sql, onSqlChange }: { connectionId: string; tables: DatabaseTable[]; sql: string; onSqlChange: (sql: string) => void }) {
  const [risk, setRisk] = useState<SqlRisk>();
  const [page, setPage] = useState<SqlPage>();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const execute = useMutation<SqlPage, Error, string>({ mutationFn: confirmationToken => localAppRequest<SqlPage>("sql.execute", { connectionId, sql, confirmationToken, maxRows: 500, pageSize: 50 }), onSuccess: value => { setPage(value); setConfirmOpen(false); } });
  const analyze = useMutation<SqlRisk, Error, void>({ mutationFn: () => localAppRequest<SqlRisk>("sql.analyze", { connectionId, sql }), onSuccess: value => { setRisk(value); if (value.executable && value.confirmationRequired) setConfirmOpen(true); else if (value.executable) execute.mutate(""); } });
  const nextPage = useMutation<SqlPage, Error, number>({ mutationFn: next => localAppRequest<SqlPage>("sql.result.page", { resultId: page?.resultId, page: next, pageSize: 50 }), onSuccess: setPage });
  const names = tables.flatMap(table => [table.name, ...table.columns.map(column => column.name)]);
  sqlWorkbenchSymbols = names;
  return <section className="content-card sql-workbench"><header className="editor-toolbar"><div><p className="eyebrow">执行策略</p><h2>SQL 工作台</h2></div><div className="button-row"><span className="policy-chip">最多 500 行 · 10 秒</span><Button disabled={!connectionId || analyze.isPending || execute.isPending} onClick={() => analyze.mutate()}>分析并运行</Button></div></header><div className="sql-editor"><Editor height="100%" language="sql" path={`sqlteacher://sql/${connectionId || "none"}`} value={sql} onChange={value => onSqlChange(value ?? "")} options={{ automaticLayout: true, minimap: { enabled: false }, fontFamily: "'Cascadia Code', Consolas, monospace", fontSize: 14, padding: { top: 16 }, scrollBeyondLastLine: false }} /></div>
    {risk && <div className={`risk-strip risk-${risk.level.toLowerCase()}`}><strong>{risk.level} · {risk.statementType}</strong><span>{risk.executable ? risk.confirmationRequired ? "需要明确确认" : "允许执行" : "Java 已阻止"}</span>{risk.reasons.map(reason => <small key={reason}>{reason}</small>)}</div>}
    {(analyze.isError || execute.isError) && <Feedback tone="error" title="SQL 未执行">{(analyze.error ?? execute.error)?.message}</Feedback>}
    <SqlResults page={page} pending={execute.isPending || nextPage.isPending} onPage={value => nextPage.mutate(value)} />
    <Dialog open={confirmOpen} title="确认高风险 SQL" onClose={() => setConfirmOpen(false)}><p>以下风险由 Java 分析器判定；令牌将在五分钟后过期且只能使用一次。</p><ul>{risk?.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul><div className="button-row"><Button variant="secondary" onClick={() => setConfirmOpen(false)}>取消</Button><Button variant="danger" onClick={() => execute.mutate(risk?.confirmationToken ?? "")}>确认执行</Button></div></Dialog>
  </section>;
}

function SqlResults({ page, pending, onPage }: { page?: SqlPage; pending: boolean; onPage: (page: number) => void }) {
  if (!page) return <div className="result-empty">暂无结果</div>;
  return <section className="result-panel"><div className="section-heading"><h3>结果 · {page.totalRows} 行{page.truncated ? "（已截断）" : ""}</h3><span className="safe-chip">{page.auditRecorded ? "已记录审计" : "审计未知"} · {page.durationMillis} ms</span></div>{page.columns.length ? <div className="virtual-table" role="region" aria-label="SQL 分页结果" tabIndex={0}><table><thead><tr>{page.columns.map(column => <th key={column}>{column}</th>)}</tr></thead><tbody>{page.rows.map((row, index) => <tr key={`${page.page}-${index}`}>{page.columns.map(column => <td key={column}>{String(row[column] ?? "NULL")}</td>)}</tr>)}</tbody></table></div> : <Feedback tone="success" title="语句执行成功">影响 {page.affectedRows} 行。{page.message}</Feedback>}<footer className="pager"><Button variant="secondary" disabled={pending || page.page === 0} onClick={() => onPage(page.page - 1)}>上一页</Button><span>第 {page.page + 1} 页</span><Button variant="secondary" disabled={pending || !page.hasMore} onClick={() => onPage(page.page + 1)}>下一页</Button></footer></section>;
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
