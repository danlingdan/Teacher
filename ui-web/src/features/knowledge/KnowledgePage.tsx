import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { sessionQuery } from "../../app/queries";
import { localAppRequest } from "../../shared/ipc";
import type {
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
  // v3.4.4：知识助教移入独立子窗口——阅读区占满主列，提问与阅读并存互不挤压。
  // 子窗口经 hash 路由挂到同一前端产物，携带当前文档上下文（课程/章节/标题）。
  const openAssistant = async () => {
    const { WebviewWindow } = await import("@tauri-apps/api/webviewWindow");
    const context = article.data?.article;
    const params = new URLSearchParams({ window: "assistant" });
    if (context?.courseTitle) params.set("course", context.courseTitle);
    if (context?.sectionTitle) params.set("section", context.sectionTitle);
    if (context?.title) params.set("title", context.title);
    const webview = new WebviewWindow(`assistant-${Date.now().toString(36)}`, {
      url: `/#/assistant-window?${params.toString()}`,
      title: "知识助教",
      width: 440,
      height: 720,
      minWidth: 360,
      minHeight: 480,
      center: true,
    });
    webview.once("tauri://error", () => toast("error", "无法打开知识助教窗口，请重试"));
  };
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
  // 稳定数组身份：下方三个 useMemo 依赖它，避免每次渲染重建映射。
  const articles = useMemo(() => overview.data?.articles ?? [], [overview.data]);
  const grouped = useMemo(() => groupArticlesByCourse(articles), [articles]);
  // v3.4.4 KUI-2：检索结果标注所属课程/章节，用 overview 既有文章清单映射，不加 IPC。
  const articleById = useMemo(() => new Map(articles.map((item) => [item.id, item])), [articles]);
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
        <p className="muted">可在左下角「知识库更新」下载官方知识库，或联系任课教师导入。</p>
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
            <FormField label="检索课程知识" hint="输入至少 2 个字符后自动检索">
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
                {indexStatus?.mode === "FTS5"
                  ? "；语义检索待本地向量模型就绪。"
                  : "，语义检索稍后就绪。"}
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
                  <>
                    <p className="muted">没有匹配“{query.trim()}”的内容。</p>
                    <p className="muted">换个关键词试试，或浏览下方课程树。</p>
                  </>
                ) : (
                  <>
                    <div className="search-results-head">
                      <span>共 {search.data.items.length} 条结果</span>
                      <button
                        type="button"
                        className="search-clear"
                        onClick={() => {
                          setQueryInput("");
                          setQuery("");
                        }}
                      >
                        清除
                      </button>
                    </div>
                    {search.data.items.map((item) => {
                      const origin = articleById.get(item.articleId);
                      return (
                        <button
                          type="button"
                          key={`${item.documentId}-${item.chunkIndex}`}
                          onClick={() => setSelectedId(item.articleId)}
                        >
                          <strong>{item.title}</strong>
                          {origin && (
                            <small>
                              来自 {origin.courseTitle} · {origin.sectionTitle}
                            </small>
                          )}
                          <span>
                            {highlightParts(item.snippet, query).map((part, partIndex) =>
                              part.hit ? <mark key={partIndex}>{part.text}</mark> : part.text,
                            )}
                          </span>
                        </button>
                      );
                    })}
                  </>
                )}
              </div>
            )}
            <div className="course-tree">
              {/* v3.4.4：课程与章节双层折叠的单一导航树，去掉与课程树重复的平铺文档列表。 */}
              {grouped.map((course) => (
                <details key={course.title} className="course-node" open>
                  <summary>
                    {course.title}
                    <small>
                      {" "}
                      ·{" "}
                      {course.sections.reduce(
                        (total, section) => total + section.articles.length,
                        0,
                      )}{" "}
                      篇文档
                    </small>
                  </summary>
                  {course.sections.map((section) => (
                    <details key={section.title} open>
                      <summary>
                        {section.title}
                        <small> · {section.articles.length} 篇</small>
                      </summary>
                      <ul>
                        {section.articles.map((item) => (
                          <li key={item.id}>
                            <button
                              type="button"
                              className={selectedId === item.id ? "selected" : ""}
                              onClick={() => setSelectedId(item.id)}
                            >
                              {item.title}
                              <small>第 {item.currentRevision} 版</small>
                            </button>
                          </li>
                        ))}
                      </ul>
                    </details>
                  ))}
                </details>
              ))}
            </div>
          </>
        )}
        <details className="knowledge-bundle-update">
          <summary>
            知识库更新
            <small>{bundle ? `已安装 v${bundle.version}` : "未安装官方知识库"}</small>
          </summary>
          <div className="bundle-update-body">
            <p className="muted">从云端获取官方知识库更新，或手动导入知识库压缩包。</p>
            <div className="button-row">
              <Button
                variant="secondary"
                disabled={checkUpdate.isPending || downloadBundle.isPending}
                busy={checkUpdate.isPending}
                onClick={() => checkUpdate.mutate()}
              >
                检查云端更新
              </Button>
              <Button
                variant="secondary"
                disabled={importBundleFile.isPending}
                onClick={() => importBundleFile.mutate()}
              >
                手动导入…
              </Button>
            </div>
            {updateStatus && (
              <Feedback
                tone={updateStatus.updateAvailable ? "info" : "success"}
                title={updateStatus.cloudAvailable ? "云端知识库" : "云端暂未提供"}
              >
                {updateStatus.message}
                {updateStatus.updateAvailable && (
                  <div className="button-row">
                    <Button
                      disabled={downloadBundle.isPending}
                      busy={downloadBundle.isPending}
                      onClick={() => downloadBundle.mutate()}
                    >
                      下载并安装 {updateStatus.cloudVersion}
                    </Button>
                  </div>
                )}
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
          </div>
        </details>
      </aside>
      <main className="knowledge-main">
        {libraryState === "empty" ? (
          guidance
        ) : (
          <section className="content-card knowledge-document">
            {currentMarkdown ? (
              <>
                <header className="knowledge-doc-header">
                  <h2>{article.data?.article.title}</h2>
                  <p className="muted">
                    {article.data?.article.courseTitle} · {article.data?.article.sectionTitle} · 第{" "}
                    {article.data?.revision} 版
                  </p>
                </header>
                <div className="button-row">
                  <Button variant="secondary" onClick={() => void openAssistant()}>
                    知识助教
                  </Button>
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
                <p>从左侧课程树中选择一篇文档开始阅读。</p>
              </div>
            )}
          </section>
        )}
        {canManage && (
          <details className="teacher-admin">
            <summary>
              教师管理
              <small>文档导入 · 单篇与索引</small>
            </summary>
            <div className="teacher-admin-body">
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
                  <Feedback tone={report.failed ? "warning" : "success"} title="增量导入完成">
                    新增 {report.imported}，修订 {report.revised}，跳过 {report.skipped}，失败{" "}
                    {report.failed}。
                  </Feedback>
                )}
              </section>
              <section className="content-card knowledge-admin-panel">
                <div className="section-heading">
                  <div>
                    <p className="eyebrow">文档管理</p>
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
                      !articlePath || !courseTitle || !sectionTitle || importArticle.isPending
                    }
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
            </div>
          </details>
        )}
      </main>
    </div>
  );
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
  // v3.4.4：课程/章节/文档按「第N部分、第N章」数字自然排序，修正字符串序的乱序观感。
  const sortedCourses = Array.from(courses.entries()).sort(([a], [b]) => naturalCompare(a, b));
  return sortedCourses.map(([title, sections]) => ({
    title,
    sections: Array.from(sections.entries())
      .sort(([a], [b]) => naturalCompare(a, b))
      .map(([sectionTitle, list]) => ({
        title: sectionTitle,
        articles: [...list].sort((a, b) => naturalCompare(a.title, b.title)),
      })),
  }));
}

// 数字段按数值比较（"第2部分" < "第10部分"），其余按字典序。
function naturalCompare(a: string, b: string): number {
  const parts = (value: string) =>
    value.split(/(\d+)/).map((part) => (/^\d+$/.test(part) ? Number(part) : part));
  const left = parts(a);
  const right = parts(b);
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const x = left[index];
    const y = right[index];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    if (x !== y) return x < y ? -1 : 1;
  }
  return 0;
}

function splitPoints(value: string) {
  return value
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

// v3.4.4 KUI-2：snippet 按不可信文本处理——只做分片高亮渲染，绝不使用
// dangerouslySetInnerHTML，脚本内容经 React 默认转义按纯文本显示。
function highlightParts(text: string, query: string): Array<{ text: string; hit: boolean }> {
  const tokens = [...new Set(query.trim().split(/\s+/).filter(Boolean))];
  if (tokens.length === 0) return [{ text, hit: false }];
  const lowered = new Set(tokens.map((token) => token.toLowerCase()));
  const pattern = tokens.map(escapeRegExp).join("|");
  return text
    .split(new RegExp(`(${pattern})`, "gi"))
    .filter((part) => part !== "")
    .map((part) => ({ text: part, hit: lowered.has(part.toLowerCase()) }));
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
