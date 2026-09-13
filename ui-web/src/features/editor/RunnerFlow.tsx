import { useMutation, useQuery } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import {
  cancelLocalAppRequest,
  localAppRequest,
  localAppRequestWithId,
  subscribeLocalAppEvents,
} from "../../shared/ipc";
import type { RunnerCapability, RunnerResult } from "../../shared/types";
import { Button, Dialog, EmptyState, Feedback } from "../../shared/ui";
import { CodeEditor } from "./EditorPage";

const templates: Record<string, string> = {
  JAVA: 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, SQLTeacher");\n    }\n}\n',
  PYTHON: 'print("Hello, SQLTeacher")\n',
  C: '#include <stdio.h>\nint main(void) { puts("Hello, SQLTeacher"); return 0; }\n',
  CPP: '#include <iostream>\nint main() { std::cout << "Hello, SQLTeacher\\n"; }\n',
};

export function RunnerFlow() {
  const capabilities = useQuery({
    queryKey: ["runner", "capabilities"],
    queryFn: () =>
      localAppRequest<{ items: RunnerCapability[] }>("runner.capabilities"),
    staleTime: 30_000,
  });
  const available = useMemo(
    () => capabilities.data?.items ?? [],
    [capabilities.data],
  );
  const [language, setLanguage] = useState<"JAVA" | "PYTHON" | "C" | "CPP">(
    "JAVA",
  );
  const [source, setSource] = useState(templates.JAVA);
  const [input, setInput] = useState("");
  const [result, setResult] = useState<RunnerResult>();
  const [requestId, setRequestId] = useState<string>();
  const [phase, setPhase] = useState("");
  useEffect(() => {
    let disposed = false;
    let unlisten: (() => void) | undefined;
    // 订阅是异步建立的：若在 resolve 前卸载，resolve 后必须立即退订，
    // 否则每次运行/取消都会泄漏一个全局监听器。
    void subscribeLocalAppEvents((event) => {
      if (event.requestId === requestId && event.event === "runner.progress")
        setPhase(String(event.payload.phase ?? ""));
    }).then((value) => {
      if (disposed) value();
      else unlisten = value;
    });
    return () => {
      disposed = true;
      unlisten?.();
    };
  }, [requestId]);
  const run = useMutation({
    mutationFn: async () => {
      const id = crypto.randomUUID();
      setRequestId(id);
      setPhase("starting");
      setResult(undefined);
      return localAppRequestWithId<RunnerResult>(
        "runner.run",
        { language, sourceCode: source, standardInput: input },
        id,
      );
    },
    onSuccess: (value) => {
      setResult(value);
      setPhase("completed");
    },
    onSettled: () => setRequestId(undefined),
  });
  const [languageSwitch, setLanguageSwitch] = useState<
    "JAVA" | "PYTHON" | "C" | "CPP"
  >();
  function applyLanguage(next: "JAVA" | "PYTHON" | "C" | "CPP") {
    setLanguage(next);
    setSource(templates[next]);
    setResult(undefined);
  }
  function selectLanguage(next: "JAVA" | "PYTHON" | "C" | "CPP") {
    // 已改动的源码直接覆盖会丢代码；先让用户确认。
    if (source !== templates[language]) {
      setLanguageSwitch(next);
      return;
    }
    applyLanguage(next);
  }
  const capability = available.find((item) => item.language === language);
  return (
    <div className="runner-layout">
      <section className="content-card coding-card">
        <header className="editor-toolbar">
          <div>
            <p className="eyebrow">本地运行</p>
            <h2>代码运行</h2>
          </div>
          <div className="segmented-control">
            {(["JAVA", "PYTHON", "C", "CPP"] as const).map((item) => (
              <button
                type="button"
                className={language === item ? "selected" : ""}
                key={item}
                onClick={() => selectLanguage(item)}
              >
                {item}
              </button>
            ))}
          </div>
        </header>
        <CodeEditor
          language={language}
          value={source}
          onChange={setSource}
          onRun={() => run.mutate()}
        />
        <label className="stdin-field">
          标准输入
          <textarea
            value={input}
            onChange={(event) => setInput(event.target.value)}
          />
        </label>
        <footer className="editor-actions">
          <span>
            {capabilities.isError
              ? `探测失败：${capabilities.error.message}`
              : capability?.available
                ? "工具链可用"
                : (capability?.reasonCode ?? "正在探测")}{" "}
            · 源码上限 256 KiB · 输出上限 64 KiB
          </span>
          {run.isPending && requestId && (
            <Button
              variant="danger"
              onClick={() => void cancelLocalAppRequest(requestId)}
            >
              取消
            </Button>
          )}
          <Button
            disabled={
              !capability?.available ||
              run.isPending ||
              source.length > 256 * 1024
            }
            onClick={() => run.mutate()}
          >
            运行实验
          </Button>
        </footer>
      </section>
      <section className="content-card output-panel" aria-live="polite">
        <div className="section-heading">
          <h2>运行反馈</h2>
          <span className="policy-chip">{phase || "待运行"}</span>
        </div>
        {!result && !run.isError ? (
          <EmptyState title="等待运行" />
        ) : result ? (
          <>
            <Feedback
              tone={result.failureReason === "NONE" ? "success" : "warning"}
              title={
                result.failureReason === "NONE"
                  ? "运行成功"
                  : result.failureReason
              }
            >
              退出码 {result.exitCode}
            </Feedback>
            <pre>
              {result.standardOutput || result.standardError || "（无输出）"}
            </pre>
          </>
        ) : (
          <Feedback tone="error" title="Runner 失败">
            {run.error?.message}
          </Feedback>
        )}
      </section>
      <Dialog
        open={Boolean(languageSwitch)}
        title="切换语言会覆盖当前代码"
        onClose={() => setLanguageSwitch(undefined)}
      >
        <p>当前代码尚未保存，切换到 {languageSwitch ?? ""} 初始模板后将覆盖现有代码，无法恢复。</p>
        <div className="button-row">
          <Button variant="secondary" onClick={() => setLanguageSwitch(undefined)}>
            取消
          </Button>
          <Button
            variant="danger"
            onClick={() => {
              if (languageSwitch) applyLanguage(languageSwitch);
              setLanguageSwitch(undefined);
            }}
          >
            覆盖并切换
          </Button>
        </div>
      </Dialog>
    </div>
  );
}
