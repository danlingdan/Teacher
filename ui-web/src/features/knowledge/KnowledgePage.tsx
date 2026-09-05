import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { courseWorkspaceQuery, sessionQuery } from "../../app/queries";
import { localAppRequest } from "../../shared/ipc";
import type {
  AiKnowledgeAnswer,
  ImportPreview,
  ImportReport,
  KnowledgeArticleDetail,
  KnowledgeSearchResult,
} from "../../shared/types";
import { Button, Feedback, FormField, Stepper } from "../../shared/ui";
import KnowledgeRenderer from "./KnowledgeRenderer";

export default function KnowledgePage() {
  const [searchParams] = useSearchParams();
  const client = useQueryClient();
  const workspace = useQuery(courseWorkspaceQuery);
  const session = useQuery(sessionQuery);
  const index = useQuery({
    queryKey: ["knowledge", "index"],
    queryFn: () =>
      localAppRequest<{
        pendingJobs: number;
        indexedChunks: number;
        failedChunks: number;
        mode: string;
        message: string;
      }>("knowledge.index.status"),
  });
  const [selectedId, setSelectedId] = useState<string>();
  const [queryInput, setQueryInput] = useState(() => searchParams.get("query") ?? "");
  const [query, setQuery] = useState(queryInput);
  // 搜索输入防抖 300ms：避免每个按键都触发一次 FTS 检索 IPC。
  useEffect(() => {
    const timer = window.setTimeout(() => setQuery(queryInput), 300);
    return () => window.clearTimeout(timer);
  }, [queryInput]);
  // 外部跳转（如今天页“查看知识点”）携带 query 参数时立即同步。
  useEffect(() => {
    const fromUrl = searchParams.get("query");
    if (fromUrl !== null) {
      setQueryInput(fromUrl);
      setQuery(fromUrl);
    }
  }, [searchParams]);
  const [root, setRoot] = useState("");
  const [preview, setPreview] = useState<ImportPreview>();
  const [report, setReport] = useState<ImportReport>();
  const [articlePath, setArticlePath] = useState("");
  const [courseTitle, setCourseTitle] = useState("");
  const [sectionTitle, setSectionTitle] = useState("");
  const [knowledgePoints, setKnowledgePoints] = useState("");
  const [question, setQuestion] = useState("");
  const [answer, setAnswer] = useState<AiKnowledgeAnswer>();
  const [articlePage, setArticlePage] = useState(0);
  const article = useQuery({
    queryKey: ["knowledge", "article", selectedId],
    queryFn: () =>
      localAppRequest<KnowledgeArticleDetail>("knowledge.article", {
        articleId: selectedId,
      }),
    enabled: Boolean(selectedId),
  });
  const search = useQuery({
    queryKey: ["knowledge", "search", query],
    queryFn: () =>
      localAppRequest<KnowledgeSearchResult>("knowledge.search", {
        query,
        limit: 30,
      }),
    enabled: query.trim().length >= 2,
  });
  const previewImport = useMutation({
    mutationFn: () =>
      localAppRequest<ImportPreview>("knowledge.import.preview", {
        root,
        courseTitle: "Obsidian 知识库",
        sectionDepth: 1,
        includeAttachments: true,
      }),
    onSuccess: (value) => {
      setPreview(value);
      setReport(undefined);
    },
  });
  const executeImport = useMutation({
    mutationFn: () =>
      localAppRequest<ImportReport>("knowledge.import.execute", {
        previewToken: preview?.token,
      }),
    onSuccess: (value) => {
      setReport(value);
      void client.invalidateQueries({
        queryKey: courseWorkspaceQuery.queryKey,
      });
    },
  });
  const refresh = () => {
    void client.invalidateQueries({ queryKey: courseWorkspaceQuery.queryKey });
    void client.invalidateQueries({ queryKey: ["knowledge", "index"] });
  };
  const markRead = useMutation({
    mutationFn: () =>
      localAppRequest("knowledge.read.mark", {
        articleId: selectedId,
        revision: article.data?.revision,
        progressPercent: 100,
      }),
  });
  const rebuild = useMutation({
    mutationFn: () => localAppRequest("knowledge.index.rebuild"),
    onSuccess: refresh,
  });
  const importArticle = useMutation({
    mutationFn: () =>
      localAppRequest("knowledge.article.import", {
        path: articlePath,
        courseTitle,
        sectionTitle,
        knowledgePoints: splitPoints(knowledgePoints),
      }),
    onSuccess: refresh,
  });
  const reviseArticle = useMutation({
    mutationFn: () =>
      localAppRequest("knowledge.article.revise", {
        articleId: selectedId,
        path: articlePath,
        knowledgePoints: splitPoints(knowledgePoints),
      }),
    onSuccess: refresh,
  });
  const visibility = useMutation({
    mutationFn: (value: string) =>
      localAppRequest("knowledge.article.visibility", {
        articleId: selectedId,
        visibility: value,
      }),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: () =>
      localAppRequest("knowledge.article.delete", { articleId: selectedId }),
    onSuccess: () => {
      setSelectedId(undefined);
      refresh();
    },
  });
  const ask = useMutation({
    mutationFn: () => {
      // Java 端只消费 question 文本；把当前打开资料的上下文拼进去，
      // 检索才会优先命中学生正在阅读的这篇内容。
      const context = article.data?.article;
      const groundedQuestion = context
        ? `（课程：${context.courseTitle} / 章节：${context.sectionTitle} / 资料标题：${context.title}）\n${question}`
        : question;
      return localAppRequest<AiKnowledgeAnswer>("ai.knowledge.ask", {
        question: groundedQuestion,
      });
    },
    onSuccess: setAnswer,
  });
  const currentMarkdown = article.data?.markdown;
  const grouped = useMemo(
    () => workspace.data?.courses ?? [],
    [workspace.data],
  );
  const articles = workspace.data?.articles ?? [];
  const pageSize = 8;
  const articlePageCount = Math.max(1, Math.ceil(articles.length / pageSize));
  const visibleArticles = articles.slice(
    articlePage * pageSize,
    (articlePage + 1) * pageSize,
  );
  useEffect(() => {
    if (articlePage >= articlePageCount) setArticlePage(articlePageCount - 1);
  }, [articlePage, articlePageCount]);
  const canManage =
    session.data?.role === "TEACHER" || session.data?.role === "ADMINISTRATOR";

  if (workspace.isPending)
    return <section className="page-skeleton">正在读取课程与知识索引…</section>;
  if (workspace.isError)
    return (
      <Feedback tone="error" title="课程工作区加载失败">
        {workspace.error.message}
      </Feedback>
    );
  return (
    <div className="knowledge-workspace">
      <aside className="knowledge-sidebar content-card">
        <FormField label="检索课程知识" hint="至少输入 2 个字符">
          {(ids) => (
            <input
              {...ids}
              value={queryInput}
              onChange={(event) => setQueryInput(event.target.value)}
              placeholder="标题、正文或知识点"
            />
          )}
        </FormField>
        {query.trim().length >= 2 && (
          <div className="search-results" aria-live="polite">
            {search.data?.items.map((item) => (
              <button
                type="button"
                disabled={!item.articleId}
                key={`${item.documentId}-${item.chunkIndex}`}
                onClick={() => setSelectedId(item.articleId)}
              >
                <strong>{item.title}</strong>
                <span>{item.snippet}</span>
              </button>
            ))}
          </div>
        )}
        <div className="course-tree">
          {grouped.map((course) => (
            <section key={course.id}>
              <h3>{course.title}</h3>
              {course.sections.map((section) => (
                <details key={section.id}>
                  <summary>{section.title}</summary>
                  <ul>
                    {section.activities.map((activity) => (
                      <li key={activity.id}>
                        <span>{activity.title}</span>
                        <small>
                          {activity.type} · {activity.estimatedMinutes} 分钟
                        </small>
                      </li>
                    ))}
                  </ul>
                </details>
              ))}
            </section>
          ))}
        </div>
        <div className="article-list">
          <h3>
            知识文档 <small>共 {articles.length} 篇</small>
          </h3>
          {visibleArticles.map((item) => (
            <button
              className={selectedId === item.id ? "selected" : ""}
              type="button"
              key={item.id}
              onClick={() => setSelectedId(item.id)}
            >
              {item.title}
              <small>
                {item.sectionTitle} · 第 {item.currentRevision} 版
              </small>
            </button>
          ))}
          {articles.length > pageSize && (
            <div className="compact-pager">
              <Button
                variant="secondary"
                disabled={articlePage === 0}
                onClick={() => setArticlePage((value) => value - 1)}
              >
                上一页
              </Button>
              <span>
                {articlePage + 1} / {articlePageCount}
              </span>
              <Button
                variant="secondary"
                disabled={articlePage + 1 >= articlePageCount}
                onClick={() => setArticlePage((value) => value + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </div>
      </aside>
      <main className="knowledge-main">
        <section className="content-card knowledge-document">
          {currentMarkdown ? (
            <>
              <div className="button-row">
                <Button
                  variant="secondary"
                  disabled={markRead.isPending}
                  onClick={() => markRead.mutate()}
                >
                  标记为已读
                </Button>
                {markRead.isSuccess && (
                  <span className="policy-chip">阅读进度已保存</span>
                )}
              </div>
              <KnowledgeRenderer markdown={currentMarkdown} />
            </>
          ) : (
            <div className="knowledge-empty">
              <h2>选择一篇知识文档</h2>
              <p>
                  </p>
            </div>
          )}
        </section>
        <section className="content-card knowledge-assistant">
          <div className="section-heading">
            <div>
              <p className="eyebrow">引用可追溯</p>
              <h2>知识助教</h2>
            </div>
            <span className="policy-chip">仅使用本地课程资料</span>
          </div>
          <FormField
            label="针对课程资料提问"
            hint="回答附引用来源"
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
            <Button
              disabled={question.trim().length < 2 || ask.isPending}
              onClick={() => ask.mutate()}
            >
              生成有引用的解释
            </Button>
          </div>
          {ask.isError && (
            <Feedback tone="error" title="知识助教不可用">
              {ask.error.message}
            </Feedback>
          )}
          {answer && (
            <Feedback
              tone={answer.aiGenerated ? "info" : "warning"}
              title={answer.model || "确定性回退"}
            >
              <p>{answer.answer || answer.message}</p>
              {answer.citations.map((item) => (
                <p key={`${item.documentId}-${item.chunkIndex}`}>
                  [{item.number}] {item.articleTitle} 第 {item.revision} 版：
                  {item.snippet}
                </p>
              ))}
            </Feedback>
          )}
        </section>
        {canManage && (
          <section className="content-card import-panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">Obsidian 增量导入</p>
                <h2>预览冲突后再写入</h2>
              </div>
              <Stepper
                steps={["选择目录", "冲突预览", "导入报告"]}
                current={report ? 2 : preview ? 1 : 0}
              />
            </div>
            <FormField
              label="知识库根目录"
              hint="仅支持 Markdown 与附件引用"
            >
              {(ids) => (
                <input
                  {...ids}
                  value={root}
                  onChange={(event) => setRoot(event.target.value)}
                  placeholder="D:\\Obsidian\\ComputerKnowledgeBase"
                />
              )}
            </FormField>
            <div className="button-row">
              <Button
                disabled={!root || previewImport.isPending}
                onClick={() => previewImport.mutate()}
              >
                生成安全预览
              </Button>
              {preview && (
                <Button
                  variant="secondary"
                  disabled={executeImport.isPending}
                  onClick={() => executeImport.mutate()}
                >
                  确认导入 {preview.newFiles + preview.changedFiles} 项
                </Button>
              )}
            </div>
            {(previewImport.isError || executeImport.isError) && (
              <Feedback tone="error" title="导入未执行">
                {(previewImport.error ?? executeImport.error)?.message}
              </Feedback>
            )}
            {preview && (
              <div className="import-summary">
                <strong>{preview.markdownFiles} 个 Markdown</strong>
                <span>新增 {preview.newFiles}</span>
                <span>冲突修订 {preview.changedFiles}</span>
                <span>不变 {preview.unchangedFiles}</span>
                <span>缺失附件 {preview.missingAttachments}</span>
                <div className="preview-list">
                  {preview.items.slice(0, 100).map((item) => (
                    <div key={item.relativePath}>
                      <span className={`action-${item.action.toLowerCase()}`}>
                        {item.action}
                      </span>
                      <strong>{item.relativePath}</strong>
                      <small>
                        {item.wikiLinks} 链接 · {item.attachments} 附件
                      </small>
                    </div>
                  ))}
                </div>
              </div>
            )}
            {report && (
              <Feedback
                tone={report.failed ? "warning" : "success"}
                title="增量导入完成"
              >
                新增 {report.imported}，修订 {report.revised}，跳过{" "}
                {report.skipped}，失败 {report.failed}。
              </Feedback>
            )}
          </section>
        )}
        {canManage && (
          <section className="content-card knowledge-admin-panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">教师管理</p>
                <h2>单篇文档与索引</h2>
              </div>
              <span className="policy-chip">
                {index.data
                  ? `${index.data.mode} · ${index.data.indexedChunks} 块`
                  : "读取索引"}
              </span>
            </div>
            <FormField label="文档路径" hint="导入新文档或修订当前文档">
              {(ids) => (
                <input
                  {...ids}
                  value={articlePath}
                  onChange={(event) => setArticlePath(event.target.value)}
                />
              )}
            </FormField>
            <div className="form-grid">
              <FormField label="课程标题">
                {(ids) => (
                  <input
                    {...ids}
                    value={courseTitle}
                    onChange={(event) => setCourseTitle(event.target.value)}
                  />
                )}
              </FormField>
              <FormField label="章节标题">
                {(ids) => (
                  <input
                    {...ids}
                    value={sectionTitle}
                    onChange={(event) => setSectionTitle(event.target.value)}
                  />
                )}
              </FormField>
            </div>
            <FormField label="知识点" hint="用逗号分隔">
              {(ids) => (
                <input
                  {...ids}
                  value={knowledgePoints}
                  onChange={(event) => setKnowledgePoints(event.target.value)}
                />
              )}
            </FormField>
            <div className="button-row">
              <Button
                disabled={
                  !articlePath ||
                  !courseTitle ||
                  !sectionTitle ||
                  importArticle.isPending
                }
                onClick={() => importArticle.mutate()}
              >
                导入单篇
              </Button>
              <Button
                variant="secondary"
                disabled={
                  !selectedId || !articlePath || reviseArticle.isPending
                }
                onClick={() => reviseArticle.mutate()}
              >
                修订当前文档
              </Button>
              <Button
                variant="secondary"
                disabled={rebuild.isPending}
                onClick={() => rebuild.mutate()}
              >
                重建检索索引
              </Button>
            </div>
            {selectedId && (
              <div className="button-row">
                <Button
                  variant="secondary"
                  onClick={() => visibility.mutate("PUBLISHED")}
                >
                  发布
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => visibility.mutate("PRIVATE")}
                >
                  设为私有
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => visibility.mutate("INACTIVE")}
                >
                  停用
                </Button>
                <Button
                  variant="danger"
                  onClick={() => {
                    if (window.confirm("确定删除当前知识文档及其索引吗？"))
                      remove.mutate();
                  }}
                >
                  删除
                </Button>
              </div>
            )}
            {(importArticle.isError ||
              reviseArticle.isError ||
              visibility.isError ||
              remove.isError ||
              rebuild.isError) && (
              <Feedback tone="error" title="知识管理操作失败">
                {
                  (
                    importArticle.error ??
                    reviseArticle.error ??
                    visibility.error ??
                    remove.error ??
                    rebuild.error
                  )?.message
                }
              </Feedback>
            )}
          </section>
        )}
      </main>
    </div>
  );
}

function splitPoints(value: string) {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean);
}
