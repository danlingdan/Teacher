import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { localAppRequest } from "../../shared/ipc";
import type { KnowledgeOverview, LearningPathView } from "../../shared/types";
import { Feedback } from "../../shared/ui";

/**
 * v3.5.0 EPATH-2：练习页「学习路径」入口。按章节分组展示题库分发的练习路径，
 * 完成态/掌握态来自既有进度与掌握快照；点击题目进入既有练习流，不改变推荐与
 * 错题本入口。EPATH-3：章节知识点标签与课程知识文章的章节名按名称映射，
 * 命中时提供「相关阅读」深链；无映射不显示。
 */
export function LearningPathPanel({ onSelect }: { onSelect: (exerciseId: string) => void }) {
  const paths = useQuery({
    queryKey: ["practice", "paths"],
    queryFn: () =>
      localAppRequest<{ items: LearningPathView[] }>("practice.paths", {}),
    staleTime: 30_000,
  });
  const knowledge = useQuery({
    queryKey: ["knowledge", "overview"],
    queryFn: () => localAppRequest<KnowledgeOverview>("knowledge.overview"),
    staleTime: 60_000,
    enabled: paths.isSuccess && (paths.data?.items.length ?? 0) > 0,
  });
  const items = paths.data?.items ?? [];
  // 无路径数据时整体隐藏，练习页保持现状（EPATH-2 回退语义）。
  if (paths.isSuccess && items.length === 0) return null;
  const articles = knowledge.data?.articles ?? [];
  return (
    <details className="content-card learning-path">
      <summary>
        <span className="learning-path-caret" aria-hidden="true">
          ▸
        </span>
        <span className="learning-path-title">
          <strong>学习路径</strong>
          <small>
            按教材章节练习{items.length > 0 ? ` · ${items.length} 条路径` : ""} · 点击展开逐章练习
          </small>
        </span>
      </summary>
      {paths.isError && (
        <Feedback tone="error" title="学习路径读取失败">
          {paths.error.message}
        </Feedback>
      )}
      {items.map((path) => (
        <section key={path.id} className="learning-path-block">
          <p className="eyebrow">{path.name}</p>
          {path.chapters.map((chapter) => {
            const related = findRelatedArticle(articles, chapter.knowledgeTags);
            const passedCount = chapter.exercises.filter((item) => item.passed).length;
            return (
              <div key={chapter.order} className="learning-path-chapter">
                <header>
                  <strong>
                    第 {chapter.order} 章 · {chapter.title}
                  </strong>
                  <span className="policy-chip">
                    {passedCount}/{chapter.exercises.length} 已通过
                  </span>
                  {related && (
                    <Link className="related-reading" to={`/knowledge?article=${encodeURIComponent(related.id)}`}>
                      相关阅读：{related.title}
                    </Link>
                  )}
                </header>
                <ul>
                  {chapter.exercises.map((exercise) => (
                    <li key={exercise.exerciseId}>
                      <button type="button" onClick={() => onSelect(exercise.exerciseId)}>
                        <span className="path-exercise-title">{exercise.title}</span>
                        <span className={`path-status ${exercise.passed ? "ok" : exercise.attempts > 0 ? "tried" : ""}`}>
                          {exercise.passed
                            ? "已通过"
                            : exercise.attempts > 0
                              ? `已尝试 ${exercise.attempts} 次`
                              : "未开始"}
                          {exercise.masteryPercent != null ? ` · 掌握 ${exercise.masteryPercent}%` : ""}
                        </span>
                      </button>
                    </li>
                  ))}
                </ul>
              </div>
            );
          })}
        </section>
      ))}
    </details>
  );
}

/** EPATH-3：章节标签 ↔ 文章章节名/知识点按名称精确映射；未命中返回空。 */
export function findRelatedArticle(
  articles: Array<{ id: string; title: string; sectionTitle: string; knowledgePoints: string[] }>,
  knowledgeTags: string[],
): { id: string; title: string } | undefined {
  for (const tag of knowledgeTags) {
    const normalized = tag.trim().toLowerCase();
    if (!normalized) continue;
    const hit = articles.find(
      (article) =>
        article.sectionTitle.trim().toLowerCase() === normalized ||
        article.knowledgePoints.some((point) => point.trim().toLowerCase() === normalized),
    );
    if (hit) return { id: hit.id, title: hit.title };
  }
  return undefined;
}
