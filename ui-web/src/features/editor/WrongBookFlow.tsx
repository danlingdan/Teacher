import { useQuery } from "@tanstack/react-query";
import { useSearchParams } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import type { WrongBookItem } from "../../shared/types";
import { difficultyLabel, exerciseTypeTitleLabel } from "../../shared/labels";
import { Button, EmptyState, Feedback, Stepper } from "../../shared/ui";

/** 错题本独立视图（W2.3）：本机聚合的失败题目 + 一键重练。 */
export function WrongBookFlow() {
  const [searchParams, setSearchParams] = useSearchParams();
  const wrongBook = useQuery({
    queryKey: ["practice", "wrongbook"],
    queryFn: () =>
      localAppRequest<{ items: WrongBookItem[] }>("practice.wrongbook"),
  });
  const openExercise = (exerciseId: string) => {
    const params = new URLSearchParams(searchParams);
    params.delete("tab");
    params.set("exercise", exerciseId);
    setSearchParams(params, { replace: false });
  };
  const items = wrongBook.data?.items ?? [];
  return (
    // 错题本没有目录侧栏，必须单列布局；复用双列 flow-layout 会把内容压进 280px 首列。
    <div className="flow-layout flow-layout-solo">
      <main className="flow-main">
        <Stepper steps={["错题回顾", "重新作答", "对比反馈"]} current={0} />
        {wrongBook.isPending && (
          <section className="page-skeleton">
            <span className="spinner" />
            正在整理错题本
          </section>
        )}
        {wrongBook.isError && (
          <Feedback tone="error" title="错题本读取失败">
            {wrongBook.error.message}
          </Feedback>
        )}
        {!wrongBook.isPending && items.length === 0 && (
          <EmptyState title="错题本是空的" />
        )}
        {items.map((item) => (
          <section className="content-card wrongbook-card" key={item.exerciseId}>
            <header className="editor-toolbar">
              <div>
                <p className="eyebrow">
                  {item.knowledgePoint} · {difficultyLabel(item.difficulty)} ·{" "}
                  {exerciseTypeTitleLabel(item.exerciseType)}
                </p>
                <h2>{item.title}</h2>
              </div>
              <span className="policy-chip">
                {item.attempts} 次尝试
                {item.bestScore != null ? ` · 最佳 ${item.bestScore} 分` : ""}
              </span>
            </header>
            {item.lastFeedback && (
              <p className="muted">最近反馈：{item.lastFeedback}</p>
            )}
            <div className="button-row">
              <Button onClick={() => openExercise(item.exerciseId)}>
                一键重练
              </Button>
            </div>
          </section>
        ))}
      </main>
    </div>
  );
}
