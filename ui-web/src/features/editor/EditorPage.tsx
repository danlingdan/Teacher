import Editor, {
  loader,
  type OnMount,
} from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import "monaco-editor/languages/definitions/java/register";
import "monaco-editor/languages/definitions/python/register";
import "monaco-editor/languages/definitions/cpp/register";
import { useEffect, useRef } from "react";
import { useSearchParams } from "react-router-dom";
import { useMonacoEditorTheme } from "../../shared/monacoTheme";
import { ActivityFlow } from "./ActivityFlow";
import { ExerciseFlow } from "./ExerciseFlow";
import { RunnerFlow } from "./RunnerFlow";
import { WrongBookFlow } from "./WrongBookFlow";

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
loader.config({ monaco });

// Monaco 补全 provider 注册在全局语言上；模块级只注册一次。v3.3 W5.2：按模型 URI
// 过滤（仅服务于练习编辑器的 /workspace 模型），与工作台的数据编辑器互不污染；
// schema 符号经 useEffect 刷新到模块槽位，不再在渲染期写入模块变量。
let practiceEditorSchema = "";
monaco.languages.registerCompletionItemProvider("sql", {
  provideCompletionItems: (model: monaco.editor.ITextModel, position: monaco.Position) => {
    if (model.uri.scheme !== "sqlteacher" || model.uri.path !== "/workspace") {
      return { suggestions: [] };
    }
    const word = model.getWordUntilPosition(position);
    const range = new monaco.Range(
      position.lineNumber,
      word.startColumn,
      position.lineNumber,
      word.endColumn,
    );
    const names = Array.from(
      new Set(practiceEditorSchema.match(/[A-Za-z_][A-Za-z0-9_]*/g) ?? []),
    );
    const suggestions: monaco.languages.CompletionItem[] = names
      .slice(0, 200)
      .map((label) => ({
        label,
        kind: monaco.languages.CompletionItemKind.Field,
        insertText: label,
        detail: "当前练习结构",
        range,
      }));
    suggestions.push({
      label: "safe select",
      kind: monaco.languages.CompletionItemKind.Snippet,
      insertText: "SELECT ${1:*} FROM ${2:table} LIMIT ${3:100};",
      insertTextRules:
        monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
      range,
    });
    return { suggestions };
  },
});

const monacoLanguage: Record<string, string> = {
  JAVA: "java",
  PYTHON: "python",
  C: "cpp",
  CPP: "cpp",
  SQL: "sql",
};

export default function EditorPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const modeParam = searchParams.get("tab");
  const mode: "exercise" | "activity" | "runner" | "wrongbook" =
    modeParam === "activity" || modeParam === "runner" || modeParam === "wrongbook"
      ? modeParam
      : "exercise";
  const setMode = (
    next: "exercise" | "activity" | "runner" | "wrongbook",
  ) => {
    const params = new URLSearchParams(searchParams);
    if (next === "exercise") params.delete("tab");
    else params.set("tab", next);
    setSearchParams(params, { replace: true });
  };

  return (
    <section className="practice-workspace">
      <div className="segmented-control workspace-tabs" aria-label="练习类型">
        <button
          type="button"
          className={mode === "exercise" ? "selected" : ""}
          onClick={() => setMode("exercise")}
        >
          SQL 练习
        </button>
        <button
          type="button"
          className={mode === "activity" ? "selected" : ""}
          onClick={() => setMode("activity")}
        >
          课程活动
        </button>
        <button
          type="button"
          className={mode === "runner" ? "selected" : ""}
          onClick={() => setMode("runner")}
        >
          自由编程
        </button>
        <button
          type="button"
          className={mode === "wrongbook" ? "selected" : ""}
          onClick={() => setMode("wrongbook")}
        >
          错题本
        </button>
      </div>
      {mode === "exercise" ? (
        <ExerciseFlow />
      ) : mode === "activity" ? (
        <ActivityFlow />
      ) : mode === "wrongbook" ? (
        <WrongBookFlow />
      ) : (
        <RunnerFlow />
      )}
    </section>
  );
}

// 旧的组件级 provider 注册已上移到模块顶部（全局只注册一次）。

export function CodeEditor({
  language,
  value,
  onChange,
  schema = "",
  onRun,
  onSubmit,
  onHint,
}: {
  language: string;
  value: string;
  onChange: (value: string) => void;
  schema?: string;
  onRun: () => void;
  onSubmit?: () => void;
  onHint?: () => void;
}) {
  // 副作用中刷新模块级 schema 槽位（渲染期不写模块变量），补全始终拿到当前练习的结构。
  useEffect(() => {
    practiceEditorSchema = schema;
  }, [schema]);
  const [editorTheme, syncEditorTheme] = useMonacoEditorTheme();
  // 快捷键命令只在挂载时注册一次；用 ref 持有最新回调，命令触发时再解引用。
  // 否则 Ctrl+Enter 提交的是挂载帧的旧代码，F1 也会绕过提示按钮当前的禁用状态。
  const callbacks = useRef({ onRun, onSubmit, onHint });
  callbacks.current = { onRun, onSubmit, onHint };
  const mount: OnMount = (editor, api) => {
    editor.addCommand(
      api.KeyMod.CtrlCmd | api.KeyCode.Enter,
      () => callbacks.current.onRun(),
    );
    editor.addCommand(
      api.KeyMod.CtrlCmd | api.KeyMod.Shift | api.KeyCode.Enter,
      () => callbacks.current.onSubmit?.(),
    );
    editor.addCommand(api.KeyCode.F1, () => callbacks.current.onHint?.());
    const model = editor.getModel();
    if (!model) return;
    // 256 KiB 超限警告跟随内容变化；只在挂载时算一次会永远过期。
    const updateMarkers = () => {
      const markers =
        model.getValue().length > 256 * 1024
          ? [
              {
                severity: api.MarkerSeverity.Warning,
                message: "内容超过 Runner 的 256 KiB 上限",
                startLineNumber: 1,
                startColumn: 1,
                endLineNumber: 1,
                endColumn: 2,
              },
            ]
          : [];
      api.editor.setModelMarkers(model, "sqlteacher", markers);
    };
    updateMarkers();
    editor.onDidChangeModelContent(updateMarkers);
  };
  return (
    <div className="editor-frame">
      <Editor
        onMount={mount}
        beforeMount={syncEditorTheme}
        height="100%"
        theme={editorTheme}
        language={monacoLanguage[language]}
        path={`sqlteacher://${language.toLowerCase()}/workspace`}
        value={value}
        onChange={(next) => onChange(next ?? "")}
        options={{
          automaticLayout: true,
          fontFamily: "'Cascadia Code', Consolas, monospace",
          fontSize: 14,
          minimap: { enabled: false },
          padding: { top: 16 },
          scrollBeyondLastLine: false,
          wordWrap: "on",
          quickSuggestions: true,
        }}
      />
    </div>
  );
}
