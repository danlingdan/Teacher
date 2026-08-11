import { useMutation, useQuery } from "@tanstack/react-query";
import Editor, { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Feedback, FormField } from "../../shared/ui";
import { cancelLocalAppRequest, localAppRequest, localAppRequestWithId, subscribeLocalAppEvents } from "../../shared/ipc";
import type { AiKnowledgeAnswer, ConnectionSummary, DatabaseTable, SqlPage, SqlRisk } from "../../shared/types";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });
const initialSql = "SELECT name, type\nFROM sqlite_master\nWHERE type IN ('table', 'view')\nORDER BY name\nLIMIT 100;";

export default function DataSqlPage() {
  const connections = useQuery({ queryKey: ["data", "connections"], queryFn: () => localAppRequest<{ items: ConnectionSummary[] }>("data.connections") });
  const [connectionId, setConnectionId] = useState("");
  useEffect(() => { if (!connectionId && connections.data?.items.length) setConnectionId((connections.data.items.find(item => item.selected) ?? connections.data.items[0]).id); }, [connectionId, connections.data]);
  const schema = useQuery({ queryKey: ["data", "schema", connectionId], queryFn: () => localAppRequest<{ tables: DatabaseTable[] }>("data.schema", { connectionId }), enabled: Boolean(connectionId) });
  return <div className="data-workspace">
    <aside className="content-card schema-panel"><p className="eyebrow">数据库连接</p><select aria-label="数据库连接" value={connectionId} onChange={event => setConnectionId(event.target.value)}>{connections.data?.items.map(item => <option key={item.id} value={item.id}>{item.displayName} · {item.dialect}{item.readOnly ? " · 只读" : ""}</option>)}</select><div className="schema-tree">{schema.data?.tables.map(table => <details key={table.name} open><summary>{table.name}</summary><ul>{table.columns.map(column => <li key={column.name}><strong>{column.name}</strong><span>{column.typeName}{column.primaryKey ? " · PK" : ""}{column.nullable ? "" : " · NOT NULL"}</span></li>)}</ul></details>)}</div>{schema.isError && <Feedback tone="error" title="结构读取失败">{schema.error.message}</Feedback>}</aside>
    <main className="data-main"><SqlWorkbench connectionId={connectionId} tables={schema.data?.tables ?? []} /><AiAssistant /></main>
  </div>;
}

function SqlWorkbench({ connectionId, tables }: { connectionId: string; tables: DatabaseTable[] }) {
  const [sql, setSql] = useState(initialSql);
  const [risk, setRisk] = useState<SqlRisk>();
  const [page, setPage] = useState<SqlPage>();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const execute = useMutation<SqlPage, Error, string>({ mutationFn: confirmationToken => localAppRequest<SqlPage>("sql.execute", { connectionId, sql, confirmationToken, maxRows: 500, pageSize: 50 }), onSuccess: value => { setPage(value); setConfirmOpen(false); } });
  const analyze = useMutation<SqlRisk, Error, void>({ mutationFn: () => localAppRequest<SqlRisk>("sql.analyze", { connectionId, sql }), onSuccess: value => { setRisk(value); if (value.executable && value.confirmationRequired) setConfirmOpen(true); else if (value.executable) execute.mutate(""); } });
  const nextPage = useMutation<SqlPage, Error, number>({ mutationFn: next => localAppRequest<SqlPage>("sql.result.page", { resultId: page?.resultId, page: next, pageSize: 50 }), onSuccess: setPage });
  const names = tables.flatMap(table => [table.name, ...table.columns.map(column => column.name)]);
  return <section className="content-card sql-workbench"><header className="editor-toolbar"><div><p className="eyebrow">Java 强制安全边界</p><h2>SQL 工作台</h2></div><div className="button-row"><span className="policy-chip">最多 500 行 · 10 秒</span><Button disabled={!connectionId || analyze.isPending || execute.isPending} onClick={() => analyze.mutate()}>分析并运行</Button></div></header><div className="sql-editor"><Editor height="100%" language="sql" path={`sqlteacher://sql/${connectionId || "none"}`} value={sql} onChange={value => setSql(value ?? "")} beforeMount={api => api.languages.registerCompletionItemProvider("sql", { provideCompletionItems: (_model: monaco.editor.ITextModel, position: monaco.Position) => ({ suggestions: names.slice(0, 500).map(label => ({ label, kind: api.languages.CompletionItemKind.Field, insertText: label, range: new api.Range(position.lineNumber, position.column, position.lineNumber, position.column) })) }) })} options={{ automaticLayout: true, minimap: { enabled: false }, fontFamily: "'Cascadia Code', Consolas, monospace", fontSize: 14, padding: { top: 16 }, scrollBeyondLastLine: false }} /></div>
    {risk && <div className={`risk-strip risk-${risk.level.toLowerCase()}`}><strong>{risk.level} · {risk.statementType}</strong><span>{risk.executable ? risk.confirmationRequired ? "需要明确确认" : "允许执行" : "Java 已阻止"}</span>{risk.reasons.map(reason => <small key={reason}>{reason}</small>)}</div>}
    {(analyze.isError || execute.isError) && <Feedback tone="error" title="SQL 未执行">{(analyze.error ?? execute.error)?.message}</Feedback>}
    <SqlResults page={page} pending={execute.isPending || nextPage.isPending} onPage={value => nextPage.mutate(value)} />
    <Dialog open={confirmOpen} title="确认高风险 SQL" onClose={() => setConfirmOpen(false)}><p>以下风险由 Java 分析器判定；令牌将在五分钟后过期且只能使用一次。</p><ul>{risk?.reasons.map(reason => <li key={reason}>{reason}</li>)}</ul><div className="button-row"><Button variant="secondary" onClick={() => setConfirmOpen(false)}>取消</Button><Button variant="danger" onClick={() => execute.mutate(risk?.confirmationToken ?? "")}>确认执行</Button></div></Dialog>
  </section>;
}

function SqlResults({ page, pending, onPage }: { page?: SqlPage; pending: boolean; onPage: (page: number) => void }) {
  if (!page) return <div className="result-empty">分析后，分页结果和 Java 审计状态会显示在这里。</div>;
  return <section className="result-panel"><div className="section-heading"><h3>结果 · {page.totalRows} 行{page.truncated ? "（已截断）" : ""}</h3><span className="safe-chip">{page.auditRecorded ? "已记录审计" : "审计未知"} · {page.durationMillis} ms</span></div>{page.columns.length ? <div className="virtual-table" role="region" aria-label="SQL 分页结果" tabIndex={0}><table><thead><tr>{page.columns.map(column => <th key={column}>{column}</th>)}</tr></thead><tbody>{page.rows.map((row, index) => <tr key={`${page.page}-${index}`}>{page.columns.map(column => <td key={column}>{String(row[column] ?? "NULL")}</td>)}</tr>)}</tbody></table></div> : <Feedback tone="success" title="语句执行成功">影响 {page.affectedRows} 行。{page.message}</Feedback>}<footer className="pager"><Button variant="secondary" disabled={pending || page.page === 0} onClick={() => onPage(page.page - 1)}>上一页</Button><span>第 {page.page + 1} 页</span><Button variant="secondary" disabled={pending || !page.hasMore} onClick={() => onPage(page.page + 1)}>下一页</Button></footer></section>;
}

function AiAssistant() {
  const [question, setQuestion] = useState("");
  const [stream, setStream] = useState("");
  const [requestId, setRequestId] = useState<string>();
  const [answer, setAnswer] = useState<AiKnowledgeAnswer>();
  const activeId = useRef<string | undefined>(undefined); activeId.current = requestId;
  useEffect(() => { let unlisten: (() => void) | undefined; void subscribeLocalAppEvents(event => { if (event.requestId === activeId.current && event.event === "ai.delta") setStream(current => current + String(event.payload.delta ?? "")); }).then(value => { unlisten = value; }); return () => unlisten?.(); }, []);
  const ask = useMutation<AiKnowledgeAnswer, Error, void>({ mutationFn: async () => { const id = crypto.randomUUID(); setRequestId(id); setStream(""); setAnswer(undefined); return localAppRequestWithId<AiKnowledgeAnswer>("ai.knowledge.ask", { question }, id); }, onSuccess: setAnswer, onSettled: () => setRequestId(undefined) });
  return <section className="content-card ai-panel"><div className="section-heading"><div><p className="eyebrow">带引用的安全降级</p><h2>知识 AI 助手</h2></div>{ask.isPending && requestId && <Button variant="danger" onClick={() => void cancelLocalAppRequest(requestId)}>取消</Button>}</div><FormField label="问题" hint="仅发送 Java 策略允许且已脱敏的知识上下文">{ids => <textarea {...ids} value={question} onChange={event => setQuestion(event.target.value)} maxLength={2000} />}</FormField><Button disabled={question.trim().length < 2 || ask.isPending} onClick={() => ask.mutate()}>生成带引用回答</Button>{ask.isError && <Feedback tone="error" title="AI 不可用，未影响核心学习">{ask.error.message}</Feedback>}{(stream || answer) && <div className="ai-answer" aria-live="polite"><p>{stream || answer?.answer}</p>{answer && <><span className="policy-chip">{answer.aiGenerated ? `AI · ${answer.model}` : "确定性降级"}</span><ol>{answer.citations.map(citation => <li key={`${citation.documentId}-${citation.chunkIndex}`}><strong>[{citation.number}] {citation.articleTitle}</strong><span>{citation.snippet}</span></li>)}</ol>{answer.message && <small>{answer.message}</small>}</>}</div>}</section>;
}
