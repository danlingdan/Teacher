import { useState } from "react";
import Editor, { loader, type BeforeMount } from "@monaco-editor/react";
import * as monaco from "monaco-editor/editor/editor.api";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import "monaco-editor/languages/definitions/sql/register";
import "monaco-editor/languages/definitions/java/register";

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};
loader.config({ monaco });

const sqlSample = `SELECT course_id, COUNT(*) AS activity_count
FROM learning_activities
WHERE status = 'PUBLISHED'
GROUP BY course_id
ORDER BY activity_count DESC;`;

const javaSample = `record LearningSignal(String activityId, boolean successful) {}

final class DeterministicPolicy {
    boolean accepts(LearningSignal signal) {
        return signal.successful();
    }
}`;

export default function EditorPage() {
  const [language, setLanguage] = useState<"sql" | "java">("sql");
  const [value, setValue] = useState(sqlSample);
  const configure: BeforeMount = (monaco) => {
    monaco.languages.registerCompletionItemProvider("sql", {
      provideCompletionItems: () => ({ suggestions: [
        {
          label: "learning_activities",
          kind: monaco.languages.CompletionItemKind.Struct,
          insertText: "learning_activities",
          detail: "SQLTeacher activity table",
          range: new monaco.Range(1, 1, 1, 1),
        },
        {
          label: "safe select",
          kind: monaco.languages.CompletionItemKind.Snippet,
          insertText: "SELECT ${1:*} FROM ${2:table} LIMIT ${3:100};",
          insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
          range: new monaco.Range(1, 1, 1, 1),
        },
      ] }),
    });
  };
  function switchLanguage(next: "sql" | "java") {
    setLanguage(next);
    setValue(next === "sql" ? sqlSample : javaSample);
  }
  return (
    <section className="editor-workspace">
      <header className="editor-toolbar">
        <div>
          <p className="eyebrow">Monaco 编辑器尖峰</p>
          <h2>受限本地编辑模型</h2>
        </div>
        <div className="segmented-control" aria-label="编辑语言">
          <button className={language === "sql" ? "selected" : ""} onClick={() => switchLanguage("sql")} type="button">SQL</button>
          <button className={language === "java" ? "selected" : ""} onClick={() => switchLanguage("java")} type="button">Java</button>
        </div>
      </header>
      <div className="editor-frame">
        <Editor
          beforeMount={configure}
          height="100%"
          language={language}
          onChange={(next) => setValue(next ?? "")}
          options={{
            automaticLayout: true,
            fontFamily: "'Cascadia Code', Consolas, monospace",
            fontSize: 14,
            minimap: { enabled: false },
            padding: { top: 18 },
            scrollBeyondLastLine: false,
            wordWrap: "on",
          }}
          theme="vs"
          value={value}
        />
      </div>
      <footer className="editor-status">
        <span>UTF-8</span><span>{language.toUpperCase()}</span><span>{value.length.toLocaleString()} 字符</span>
      </footer>
    </section>
  );
}
