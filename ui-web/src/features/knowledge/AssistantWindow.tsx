import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Button, Feedback, FormField } from "../../shared/ui";
import { useAssistantTurns } from "./useAssistantTurns";
import { useAppearanceEffects } from "../../shared/useAppearanceEffects";

// v3.4.4：知识助教独立子窗口。经主窗口「知识助教」按钮以 WebviewWindow 打开，
// 路由 /#/assistant-window?course=…&section=…&title=… 携带当前文档上下文；
// v3.6.0 KBF-1：上下文以结构化字段随提问传给 Java 端做检索过滤（不再拼进问题文本），
// 每轮提问仍是独立的单轮检索问答，引用在此窗口内为纯文本（原文跳转在主窗口完成）。
export default function AssistantWindow() {
  const [searchParams] = useSearchParams();
  useAppearanceEffects();
  const course = searchParams.get("course") ?? "";
  const section = searchParams.get("section") ?? "";
  const title = searchParams.get("title") ?? "";
  const hasContext = Boolean(course || section || title);
  const { turns, askPending, submitQuestion, cancelTurn, clearTurns } = useAssistantTurns({
    courseTitle: course,
    sectionTitle: section,
  });
  const [question, setQuestion] = useState("");

  const submit = () => {
    if (submitQuestion(question)) setQuestion("");
  };

  return (
    <main className="assistant-window-page">
      <section className="content-card knowledge-assistant">
        <div className="section-heading">
          <div>
            <p className="eyebrow">引用可追溯</p>
            <h2>知识助教</h2>
          </div>
          <div className="button-row">
            {turns.length > 0 && (
              <Button variant="secondary" onClick={clearTurns}>
                清空对话
              </Button>
            )}
            <span className="policy-chip">仅使用本地课程资料</span>
          </div>
        </div>
        <p className="muted assistant-context-line">
          {hasContext
            ? `将结合正在阅读的资料：${title || course || section}`
            : "未关联具体文档；可在主窗口阅读文档时点「知识助教」携带上下文。"}
        </p>
        {turns.length > 0 && (
          <div className="assistant-turns" aria-live="polite">
            {turns.map((turn) => (
              <article className="assistant-turn" key={turn.id}>
                <p className="assistant-question">{turn.question}</p>
                {turn.pending && (
                  <div className="assistant-pending">
                    <p className="muted" role="status">
                      正在检索课程资料并生成回答…
                    </p>
                    <Button variant="secondary" onClick={() => cancelTurn(turn)}>
                      取消
                    </Button>
                  </div>
                )}
                {turn.error && (
                  <Feedback tone="error" title="知识助教不可用">
                    {turn.error}
                  </Feedback>
                )}
                {turn.answer && (
                  <Feedback
                    tone={turn.answer.aiGenerated ? "info" : "warning"}
                    title={turn.answer.model || "确定性回退"}
                  >
                    <p>{turn.answer.answer || turn.answer.message}</p>
                    {turn.answer.citations.map((item) => (
                      <p className="assistant-citation" key={`${item.documentId}-${item.chunkIndex}`}>
                        [{item.number}] {item.articleTitle} 第 {item.revision} 版：
                        {item.snippet}
                      </p>
                    ))}
                  </Feedback>
                )}
              </article>
            ))}
          </div>
        )}
        <FormField
          label="针对课程资料提问"
          hint="回答附引用来源；可继续追问"
        >
          {(ids) => (
            <textarea
              {...ids}
              rows={3}
              value={question}
              onChange={(event) => setQuestion(event.target.value)}
              placeholder="例如：为什么短作业优先调度能降低平均等待时间？"
            />
          )}
        </FormField>
        <div className="button-row">
          <Button disabled={question.trim().length < 2 || askPending} onClick={submit}>
            生成有引用的解释
          </Button>
        </div>
      </section>
    </main>
  );
}
