import { useMutation, useQuery } from "@tanstack/react-query";
import Editor, { loader, type BeforeMount, type OnMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import "monaco-editor/languages/definitions/java/register";
import "monaco-editor/languages/definitions/python/register";
import "monaco-editor/languages/definitions/cpp/register";
import { useEffect, useMemo, useState } from "react";
import { cancelLocalAppRequest, localAppRequest, localAppRequestWithId, subscribeLocalAppEvents } from "../../shared/ipc";
import type { ExerciseAttempt, ExerciseSession, ExerciseSummary, ExerciseView, RunnerCapability, RunnerResult } from "../../shared/types";
import { Button, EmptyState, Feedback, Stepper } from "../../shared/ui";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

const templates: Record<string, string> = {
  JAVA: "public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello, SQLTeacher\");\n    }\n}\n",
  PYTHON: "print(\"Hello, SQLTeacher\")\n",
  C: "#include <stdio.h>\nint main(void) { puts(\"Hello, SQLTeacher\"); return 0; }\n",
  CPP: "#include <iostream>\nint main() { std::cout << \"Hello, SQLTeacher\\n\"; }\n",
};
const monacoLanguage: Record<string, string> = { JAVA: "java", PYTHON: "python", C: "cpp", CPP: "cpp", SQL: "sql" };

export default function EditorPage() {
  const [mode, setMode] = useState<"exercise" | "runner">("exercise");
  return <section className="practice-workspace">
    <div className="segmented-control workspace-tabs" aria-label="练习类型"><button type="button" className={mode === "exercise" ? "selected" : ""} onClick={() => setMode("exercise")}>SQL 练习</button><button type="button" className={mode === "runner" ? "selected" : ""} onClick={() => setMode("runner")}>编程实验</button></div>
    {mode === "exercise" ? <ExerciseFlow /> : <RunnerFlow />}
  </section>;
}

function ExerciseFlow() {
  const catalog = useQuery({ queryKey: ["practice", "catalog"], queryFn: () => localAppRequest<{ items: ExerciseSummary[] }>("practice.catalog") });
  const [selectedId, setSelectedId] = useState<string>();
  const [answer, setAnswer] = useState("SELECT *\nFROM ");
  const [session, setSession] = useState<ExerciseSession>();
  const [feedback, setFeedback] = useState<ExerciseAttempt>();
  const preview = useQuery({ queryKey: ["practice", "preview", selectedId], queryFn: () => localAppRequest<ExerciseView>("practice.preview", { exerciseId: selectedId }), enabled: Boolean(selectedId) });
  const start = useMutation({ mutationFn: () => localAppRequest<ExerciseSession>("practice.start", { exerciseId: selectedId }), onSuccess: value => { setSession(value); setFeedback(undefined); } });
  const attempt = useMutation({ mutationFn: (submit: boolean) => localAppRequest<ExerciseAttempt>(submit ? "practice.submit" : "practice.run", { sessionId: session?.id, answer }), onSuccess: setFeedback });
  const step = feedback ? 4 : session ? 3 : preview.data ? 1 : selectedId ? 0 : 0;
  return <div className="flow-layout">
    <aside className="content-card selection-panel"><p className="eyebrow">题目目录</p>{catalog.data?.items.map(item => <button type="button" className={selectedId === item.id ? "selected" : ""} key={item.id} onClick={() => { setSelectedId(item.id); setSession(undefined); setFeedback(undefined); }}>{item.title}<small>{item.knowledgePoint} · {item.difficulty}</small></button>)}</aside>
    <main className="flow-main"><Stepper steps={["选题", "预览", "确认", "作答", "反馈"]} current={step} />
      {!selectedId && <EmptyState title="先选择练习">编辑器会在预览并确认练习后加载。</EmptyState>}
      {preview.data && !session && <section className="content-card preview-card"><p className="eyebrow">作答前预览</p><h2>{preview.data.title}</h2><p>{preview.data.description}</p><dl><div><dt>知识点</dt><dd>{preview.data.knowledgePoint}</dd></div><div><dt>难度</dt><dd>{preview.data.difficulty}</dd></div></dl><pre>{preview.data.schemaSummary}</pre><Button disabled={start.isPending} onClick={() => start.mutate()}>确认并开始作答</Button></section>}
      {session && <section className="content-card coding-card"><header className="editor-toolbar"><div><p className="eyebrow">确定性 SQL 练习</p><h2>{session.exercise.title}</h2></div><span className="policy-chip">Java 评价</span></header><CodeEditor language="SQL" value={answer} onChange={setAnswer} schema={session.exercise.schemaSummary} onRun={() => attempt.mutate(false)} onSubmit={() => attempt.mutate(true)} /><footer className="editor-actions"><span>{answer.length.toLocaleString()} 字符 · Ctrl+Enter 运行 · Ctrl+Shift+Enter 提交</span><Button variant="secondary" disabled={attempt.isPending} onClick={() => attempt.mutate(false)}>运行</Button><Button disabled={attempt.isPending} onClick={() => attempt.mutate(true)}>提交评价</Button></footer></section>}
      {feedback && <Feedback tone={feedback.evaluation?.passed ? "success" : "warning"} title={feedback.evaluation?.passed ? "练习通过" : `状态：${feedback.status}`}><p>{feedback.evaluation?.feedback ?? feedback.execution?.message}</p>{feedback.evaluation?.criteria.map(item => <p key={item.criterion}>{item.passed ? "✓" : "×"} {item.criterion}：{item.feedback}</p>)}</Feedback>}
      {(catalog.isError || preview.isError || start.isError || attempt.isError) && <Feedback tone="error" title="练习流程失败">{(catalog.error ?? preview.error ?? start.error ?? attempt.error)?.message}</Feedback>}
    </main>
  </div>;
}

function RunnerFlow() {
  const capabilities = useQuery({ queryKey: ["runner", "capabilities"], queryFn: () => localAppRequest<{ items: RunnerCapability[] }>("runner.capabilities") });
  const available = useMemo(() => capabilities.data?.items ?? [], [capabilities.data]);
  const [language, setLanguage] = useState<"JAVA" | "PYTHON" | "C" | "CPP">("JAVA");
  const [source, setSource] = useState(templates.JAVA);
  const [input, setInput] = useState("");
  const [result, setResult] = useState<RunnerResult>();
  const [requestId, setRequestId] = useState<string>();
  const [phase, setPhase] = useState("");
  useEffect(() => { let unlisten: (() => void) | undefined; void subscribeLocalAppEvents(event => { if (event.requestId === requestId && event.event === "runner.progress") setPhase(String(event.payload.phase ?? "")); }).then(value => { unlisten = value; }); return () => unlisten?.(); }, [requestId]);
  const run = useMutation({
    mutationFn: async () => {
      const id = crypto.randomUUID(); setRequestId(id); setPhase("starting"); setResult(undefined);
      return localAppRequestWithId<RunnerResult>("runner.run", { language, sourceCode: source, standardInput: input }, id);
    },
    onSuccess: value => { setResult(value); setPhase("completed"); },
    onSettled: () => setRequestId(undefined),
  });
  function selectLanguage(next: typeof language) { setLanguage(next); setSource(templates[next]); setResult(undefined); }
  const capability = available.find(item => item.language === language);
  return <div className="runner-layout"><section className="content-card coding-card"><header className="editor-toolbar"><div><p className="eyebrow">本地编程实验</p><h2>多语言 Runner</h2></div><div className="segmented-control">{(["JAVA", "PYTHON", "C", "CPP"] as const).map(item => <button type="button" className={language === item ? "selected" : ""} key={item} onClick={() => selectLanguage(item)}>{item}</button>)}</div></header><CodeEditor language={language} value={source} onChange={setSource} onRun={() => run.mutate()} /><label className="stdin-field">标准输入<textarea value={input} onChange={event => setInput(event.target.value)} /></label><footer className="editor-actions"><span>{capability?.available ? "工具链可用" : capability?.reasonCode ?? "正在探测"} · 源码上限 256 KiB · 输出上限 64 KiB</span>{run.isPending && requestId && <Button variant="danger" onClick={() => void cancelLocalAppRequest(requestId)}>取消</Button>}<Button disabled={!capability?.available || run.isPending || source.length > 256 * 1024} onClick={() => run.mutate()}>运行实验</Button></footer></section><section className="content-card output-panel" aria-live="polite"><div className="section-heading"><h2>运行反馈</h2><span className="policy-chip">{phase || "idle"}</span></div>{!result && !run.isError ? <EmptyState title="等待运行">编译、运行、超时和取消状态会显示在这里。</EmptyState> : result ? <><Feedback tone={result.failureReason === "NONE" ? "success" : "warning"} title={result.failureReason === "NONE" ? "运行成功" : result.failureReason}>退出码 {result.exitCode}</Feedback><pre>{result.standardOutput || result.standardError || "（无输出）"}</pre></> : <Feedback tone="error" title="Runner 失败">{run.error?.message}</Feedback>}</section></div>;
}

function CodeEditor({ language, value, onChange, schema = "", onRun, onSubmit }: { language: string; value: string; onChange: (value: string) => void; schema?: string; onRun: () => void; onSubmit?: () => void }) {
  const configure: BeforeMount = api => {
    api.languages.registerCompletionItemProvider("sql", { provideCompletionItems: (model: monaco.editor.ITextModel, position: monaco.Position) => {
      const word = model.getWordUntilPosition(position); const range = new api.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
      const names = Array.from(new Set(schema.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? []));
      const suggestions: monaco.languages.CompletionItem[] = names.slice(0, 200).map(label => ({ label, kind: api.languages.CompletionItemKind.Field, insertText: label, detail: "当前练习结构", range }));
      suggestions.push({ label: "safe select", kind: api.languages.CompletionItemKind.Snippet, insertText: "SELECT ${1:*} FROM ${2:table} LIMIT ${3:100};", insertTextRules: api.languages.CompletionItemInsertTextRule.InsertAsSnippet, range });
      return { suggestions };
    }});
  };
  const mount: OnMount = (editor, api) => {
    editor.addCommand(api.KeyMod.CtrlCmd | api.KeyCode.Enter, onRun);
    if (onSubmit) editor.addCommand(api.KeyMod.CtrlCmd | api.KeyMod.Shift | api.KeyCode.Enter, onSubmit);
    const model = editor.getModel(); if (!model) return;
    const markers = value.length > 256 * 1024 ? [{ severity: api.MarkerSeverity.Warning, message: "内容超过 Runner 的 256 KiB 上限", startLineNumber: 1, startColumn: 1, endLineNumber: 1, endColumn: 2 }] : [];
    api.editor.setModelMarkers(model, "sqlteacher", markers);
  };
  return <div className="editor-frame"><Editor beforeMount={configure} onMount={mount} height="100%" language={monacoLanguage[language]} path={`sqlteacher://${language.toLowerCase()}/workspace`} value={value} onChange={next => onChange(next ?? "")} options={{ automaticLayout: true, fontFamily: "'Cascadia Code', Consolas, monospace", fontSize: 14, minimap: { enabled: false }, padding: { top: 16 }, scrollBeyondLastLine: false, wordWrap: "on", quickSuggestions: true }} /></div>;
}
