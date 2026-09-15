import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { sessionQuery } from "../../app/queries";
import { localAppRequest } from "../../shared/ipc";
import type {
  AiKnowledgeAnswer,
  ImportPreview,
  ImportReport,
  KnowledgeArticle,
  KnowledgeArticleDetail,
  KnowledgeBundleImportReport,
  KnowledgeBundleUpdateStatus,
  KnowledgeOverview,
  KnowledgeSearchResult,
} from "../../shared/types";
import { Button, Feedback, FormField, Stepper, useToast } from "../../shared/ui";
import KnowledgeRenderer from "./KnowledgeRenderer";

// v3.4.3 KSR-2：知识页三态由 knowledge.overview 驱动，与课程活动完全解耦。
type KnowledgeLibraryState = "empty" | "indexing" | "ready";

export default function KnowledgePage() {
  const [searchParams] = useSearchParams();
  const client = useQueryClient();
  const toast = useToast();
  const session = useQuery(sessionQuery);
  const overview = useQuery({
    queryKey: ["knowledge", "overview"],
    queryFn: () => localAppRequest<KnowledgeOverview>("knowledge.overview"),
  });
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
  const [selectedId, setSelectedId] = useState<string | undefined>(
    () =>
      // 命令面板等入口通过 ?article= 深链到具体文档。
      searchParams.get("article") ?? undefined,
  );
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
  // 命令面板深链 ?article=：页面已挂载时参数变化也要切换文档。
  useEffect(() => {
    const fromUrl = searchParams.get("article");
    if (fromUrl) setSelectedId(fromUrl);
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
  const [updateStatus, setUpdateStatus] = useState<KnowledgeBundleUpdateStatus>();
  const [bundleReport, setBundleReport] = useState<KnowledgeBundleImportReport>();
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
      refresh();
    },
  });
  const refresh = () => {
    void client.invalidateQueries({ queryKey: ["knowledge", "overview"] });
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
    mutationFn: () => localAppRequest("knowledge.article.delete", { articleId: selectedId }),
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
  const checkUpdate = useMutation({
    mutationFn: () => localAppRequest<KnowledgeBundleUpdateStatus>("knowledge.bundle.check"),
    onSuccess: setUpdateStatus,
    onError: (error: Error) => toast("error", `检查更新失败：${error.message}`),
  });
  const downloadBundle = useMutation({
    mutationFn: () => localAppRequest<KnowledgeBundleImportReport>("knowledge.bundle.download"),
    onSuccess: (value) => {
      setBundleReport(value);
      setUpdateStatus(undefined);
      refresh();
      toast("success", `官方知识库已更新到 ${value.version}`);
    },
    onError: (error: Error) => toast("error", `下载失败：${error.message}`),
  });
  const importBundleFile = useMutation({
    mutationFn: async () => {
      const { open } = await import("@tauri-apps/plugin-dialog");
      const selection = await open({
        multiple: false,
        directory: false,
        title: "选择知识库文件",
        filters: [{ name: "知识库压缩包", extensions: ["zip"] }],
      });
      if (typeof selection !== "string" || !selection.trim()) return undefined;
      return localAppRequest<KnowledgeBundleImportReport>("knowledge.bundle.import", {
        path: selection,
      });
    },
    onSuccess: (value) => {
      if (!value) return;
      setBundleReport(value);
      refresh();
      toast("success", `已导入知识库 ${value.version}`);
    },
    onError: (error: Error) => toast("error", `导入失败：${error.message}`),
  });
  const currentMarkdown = article.data?.markdown;
  const articles = overview.data?.articles ?? [];
  const grouped = useMemo(() => groupArticlesByCourse(articles), [articles]);
  // v3.4.3：首启时官方知识库由后台 bootstrap 稍后导入完成；空态页轮询刷新，
  // 导入落地后无需手动切换页面即可看到知识库（最多轮询约 2 分钟）。
  const emptyPolling = (overview.data?.articleCount ?? 0) === 0 && !overview.isError;
  useEffect(() => {
    if (!emptyPolling) return;
    const started = Date.now();
    const timer = window.setInterval(() => {
      if (Date.now() - started > 120_000) {
        window.clearInterval(timer);
        return;
      }
      void client.invalidateQueries({ queryKey: ["knowledge", "overview"] });
    }, 4000);
    return () => window.clearInterval(timer);
  }, [emptyPolling, client]);
  const pageSize = 8;
  const articlePageCount = Math.max(1, Math.ceil(articles.length / pageSize));
  const visibleArticles = articles.slice(articlePage * pageSize, (articlePage + 1) * pageSize);
  useEffect(() => {
    if (articlePage >= articlePageCount) setArticlePage(articlePageCount - 1);
  }, [articlePage, articlePageCount]);
  const canManage = session.data?.role === "TEACHER" || session.data?.role === "ADMINISTRATOR";

  const indexStatus = overview.data?.index;
  const articleCount = overview.data?.articleCount ?? 0;
  const libraryState: KnowledgeLibraryState =
    articleCount === 0 ? "empty" : (indexStatus?.pendingJobs ?? 0) > 0 ? "indexing" : "ready";
  const bundle = overview.data?.bundle ?? null;

  if (overview.isPending)
    return <section className="page-skeleton">正在读取课程与知识索引…</section>;
  if (overview.isError)
    return (
      <Feedback tone="error" title="课程知识加载失败">
        {overview.error.message}
      </Feedback>
    );

  const guidance = (
    <section className="content-card knowledge-empty-guide">
      <div className="section-heading">
        <div>
          <p className="eyebrow">开始使用</p>
          <h2>课程知识库还是空的</h2>
        </div>
      </div>
      <p className="muted">
        这里用于检索与阅读课程知识文档，和「练习与实验」里的课程活动是分开的。获取知识文档有两种方式：
      </p>
      {canManage ? (
        <>
          <div className="button-row">
            <Button
              variant="secondary"
              disabled={checkUpdate.isPending || downloadBundle.isPending}
              busy={checkUpdate.isPending}
              onClick={() => checkUpdate.mutate()}
            >
              检查官方知识库
            </Button>
            <Button
              variant="secondary"
              disabled={importBundleFile.isPending}
              onClick={() => importBundleFile.mutate()}
            >
              导入知识库文件…
            </Button>
          </div>
          <p className="muted">
            也可以展开下方「Obsidian 增量导入」或「单篇文档」把你自己的资料加进来。
          </p>
        </>
      ) : (
        <p className="muted">请联系任课教师导入或发布课程知识文档后再来查看。</p>
      )}
      {checkUpdate.data && !downloadBundle.isPending && (
        <Feedback
          tone={checkUpdate.data.updateAvailable ? "info" : "warning"}
          title={checkUpdate.data.cloudAvailable ? "云端知识库" : "云端暂未提供"}
        >
          {checkUpdate.data.message}
          {checkUpdate.data.updateAvailable && (
            <div className="button-row">
              <Button
                disabled={downloadBundle.isPending}
                busy={downloadBundle.isPending}
                onClick={() => downloadBundle.mutate()}
              >
                下载并安装 {checkUpdate.data.cloudVersion}
              </Button>
            </div>
          )}
        </Feedback>
      )}
      {checkUpdate.isError && (
        <Feedback tone="error" title="检查更新失败">
          {checkUpdate.error.message}
        </Feedback>
      )}
    </section>
  );

  return (
    <div className="knowledge-workspace">
      <aside className="knowledge-sidebar content-card">
        {libraryState === "empty" ? (
          <div className="knowledge-empty-sidebar">
            <p className="muted">暂无知识文档。</p>
          </div>
        ) : (
          <>
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
            {libraryState === "indexing" && (
              <p className="index-progress" role="status">
                正在建立课程知识索引（剩余 {indexStatus?.pendingJobs ?? 0} 个任务）。全文检索已可用
                {indexStatus?.mode === "FTS5" ? "；语义检索待本地向量模型就绪。" : "，语义检索稍后就绪。"}
              </p>
            )}
            {query.trim().length >= 2 && (
              <div className="search-results" aria-live="polite">
                {search.isFetching ? (
                  <p className="muted">正在检索课程知识…</p>
                ) : search.isError ? (
                  <Feedback tone="error" title="检索失败">
                    {search.error.message}
                  </Feedback>
                ) : !search.data || search.data.items.length === 0 ? (
                  <p className="muted">无匹配结果。</p>
                ) : (
                  search.data.items.map((item) => (
                    <button
                      type="button"
                      key={`${item.documentId}-${item.chunkIndex}`}
                      onClick={() => setSelectedId(item.articleId)}
                    >
                      <strong>{item.title}</strong>
                      <span>{item.snippet}</span>
                    </button>
                  ))
                )}
              </div>
            )}
            <div className="course-tree">
              {grouped.map((course) => (
                <section key={course.title}>
                  <h3>{course.title}</h3>
                  {course.sections.map((section) => (
                    <details key={section.title}>
                      <summary>
                        {section.title}
                        <small> · {section.articles.length} 篇文档</small>
                      </summary>
                      <ul>
                        {section.articles.map((item) => (
                          <li key={item.id}>
                            <button type="button" onClick={() => setSelectedId(item.id)}>
                              {item.title}
                            </button>
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
          </>
        )}
      </aside>
      <main className="knowledge-main">
        {libraryState === "empty" ? (
          guidance
        ) : (
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
                  {markRead.isSuccess && <span className="policy-chip">阅读进度已保存</span>}
                  {markRead.isError && (
                    <span className="policy-chip" role="status">
                      进度保存失败：{markRead.error?.message}
                    </span>
                  )}
                </div>
                <KnowledgeRenderer markdown={currentMarkdown} articleId={selectedId} />
              </>
            ) : (
              <div className="knowledge-empty">
                <h2>选择一篇知识文档</h2>
                <p>从左侧「知识文档」列表或课程树中选择一篇文档开始阅读。</p>
              </div>
            )}
          </section>
        )}
        {libraryState !== "empty" && (
          <section className="content-card knowledge-assistant">
            <div className="section-heading">
              <div>
                <p className="eyebrow">引用可追溯</p>
                <h2>知识助教</h2>
              </div>
              <span className="policy-chip">仅使用本地课程资料</span>
            </div>
            <FormField label="针对课程资料提问" hint="回答附引用来源">
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
        )}
        {canManage && (
          <section className="content-card bundle-panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">官方知识库</p>
                <h2>更新与离线导入</h2>
              </div>
              <span className="policy-chip">
                {bundle ? `已安装 ${bundle.version} · ${bundleLabel(bundle.source)}` : "未安装"}
              </span>
            </div>
            <div className="button-row">
              <Button
                variant="secondary"
                disabled={checkUpdate.isPending}
                onClick={() => checkUpdate.mutate()}
              >
                检查更新
              </Button>
              {updateStatus?.updateAvailable && (
                <Button
                  disabled={downloadBundle.isPending}
                  busy={downloadBundle.isPending}
                  onClick={() => downloadBundle.mutate()}
                >
                  下载并安装 {updateStatus.cloudVersion}
                </Button>
              )}
              <Button
                variant="secondary"
                disabled={importBundleFile.isPending}
                onClick={() => importBundleFile.mutate()}
              >
                导入知识库文件…
              </Button>
            </div>
            {updateStatus && (
              <Feedback
                tone={updateStatus.updateAvailable ? "info" : "success"}
                title={updateStatus.cloudAvailable ? "云端知识库" : "云端暂未提供"}
              >
                {updateStatus.message}
              </Feedback>
            )}
            {bundleReport && (
              <Feedback tone={bundleReport.failed ? "warning" : "success"} title="知识库导入完成">
                版本 {bundleReport.version}，共 {bundleReport.totalDocuments} 篇：新增{" "}
                {bundleReport.importedDocuments}，替换 {bundleReport.replacedDocuments}，失败{" "}
                {bundleReport.failedDocuments}。
              </Feedback>
            )}
            {(checkUpdate.isError || downloadBundle.isError || importBundleFile.isError) && (
              <Feedback tone="error" title="知识库操作失败">
                {(checkUpdate.error ?? downloadBundle.error ?? importBundleFile.error)?.message}
              </Feedback>
            )}
          </section>
        )}
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
            <FormField label="知识库根目录" hint="仅支持 Markdown 与附件引用">
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
                      <span className={`action-${item.action.toLowerCase()}`}>{item.action}</span>
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
              <Feedback tone={report.failed ? "warning" : "success"} title="增量导入完成">
                新增 {report.imported}，修订 {report.revised}，跳过 {report.skipped}，失败{" "}
                {report.failed}。
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
                {index.data ? `${index.data.mode} · ${index.data.indexedChunks} 块` : "读取索引"}
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
                disabled={!articlePath || !courseTitle || !sectionTitle || importArticle.isPending}
                onClick={() => importArticle.mutate()}
              >
                导入单篇
              </Button>
              <Button
                variant="secondary"
                disabled={!selectedId || !articlePath || reviseArticle.isPending}
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
                <Button variant="secondary" onClick={() => visibility.mutate("PUBLISHED")}>
                  发布
                </Button>
                <Button variant="secondary" onClick={() => visibility.mutate("PRIVATE")}>
                  设为私有
                </Button>
                <Button variant="secondary" onClick={() => visibility.mutate("INACTIVE")}>
                  停用
                </Button>
                <Button
                  variant="danger"
                  onClick={() => {
                    if (window.confirm("确定删除当前知识文档及其索引吗？")) remove.mutate();
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

function bundleLabel(source: string): string {
  return source === "BUILTIN" ? "随包" : source === "CLOUD" ? "云端" : source === "MANUAL" ? "手动" : source;
}

// v3.4.3 KSR-2：课程树直接从知识文档自身的 courseTitle/sectionTitle 归组，
// 不再依赖 course.workspace（其中的 courses 混有课程活动），从数据层与活动切开。
function groupArticlesByCourse(
  articles: KnowledgeArticle[],
): Array<{ title: string; sections: Array<{ title: string; articles: KnowledgeArticle[] }> }> {
  const courses = new Map<string, Map<string, KnowledgeArticle[]>>();
  for (const article of articles) {
    const sections = courses.get(article.courseTitle) ?? new Map<string, KnowledgeArticle[]>();
    const list = sections.get(article.sectionTitle) ?? [];
    list.push(article);
    sections.set(article.sectionTitle, list);
    courses.set(article.courseTitle, sections);
  }
  return Array.from(courses.entries()).map(([title, sections]) => ({
    title,
    sections: Array.from(sections.entries()).map(([sectionTitle, list]) => ({
      title: sectionTitle,
      articles: list,
    })),
  }));
}

function splitPoints(value: string) {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean);
}
