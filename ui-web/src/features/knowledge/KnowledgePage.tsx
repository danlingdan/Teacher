import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { courseWorkspaceQuery, knowledgeQuery } from "../../app/queries";
import { localAppRequest } from "../../shared/ipc";
import type { ImportPreview, ImportReport, KnowledgeArticleDetail, KnowledgeSearchResult } from "../../shared/types";
import { Button, EmptyState, Feedback, FormField, Stepper } from "../../shared/ui";
import KnowledgeRenderer from "./KnowledgeRenderer";

export default function KnowledgePage() {
  const client = useQueryClient();
  const workspace = useQuery(courseWorkspaceQuery);
  const sample = useQuery(knowledgeQuery);
  const [selectedId, setSelectedId] = useState<string>();
  const [query, setQuery] = useState("");
  const [root, setRoot] = useState("");
  const [preview, setPreview] = useState<ImportPreview>();
  const [report, setReport] = useState<ImportReport>();
  const article = useQuery({
    queryKey: ["knowledge", "article", selectedId],
    queryFn: () => localAppRequest<KnowledgeArticleDetail>("knowledge.article", { articleId: selectedId }),
    enabled: Boolean(selectedId),
  });
  const search = useQuery({
    queryKey: ["knowledge", "search", query],
    queryFn: () => localAppRequest<KnowledgeSearchResult>("knowledge.search", { query, limit: 30 }),
    enabled: query.trim().length >= 2,
  });
  const previewImport = useMutation({
    mutationFn: () => localAppRequest<ImportPreview>("knowledge.import.preview", {
      root, courseTitle: "Obsidian 知识库", sectionDepth: 1, includeAttachments: true,
    }),
    onSuccess: value => { setPreview(value); setReport(undefined); },
  });
  const executeImport = useMutation({
    mutationFn: () => localAppRequest<ImportReport>("knowledge.import.execute", { previewToken: preview?.token }),
    onSuccess: value => { setReport(value); void client.invalidateQueries({ queryKey: courseWorkspaceQuery.queryKey }); },
  });
  const currentMarkdown = article.data?.markdown ?? (!selectedId ? sample.data?.markdown : undefined);
  const grouped = useMemo(() => workspace.data?.courses ?? [], [workspace.data]);

  if (workspace.isPending) return <section className="page-skeleton">正在读取课程与知识索引…</section>;
  if (workspace.isError) return <Feedback tone="error" title="课程工作区加载失败">{workspace.error.message}</Feedback>;
  return <div className="knowledge-workspace">
    <aside className="knowledge-sidebar content-card">
      <FormField label="检索课程知识" hint="至少输入 2 个字符">{ids => <input {...ids} value={query} onChange={event => setQuery(event.target.value)} placeholder="标题、正文或知识点" />}</FormField>
      {query.trim().length >= 2 && <div className="search-results" aria-live="polite">{search.data?.items.map(item => <button type="button" disabled={!item.articleId} key={`${item.documentId}-${item.chunkIndex}`} onClick={() => setSelectedId(item.articleId)}><strong>{item.title}</strong><span>{item.snippet}</span></button>)}</div>}
      <div className="course-tree">{grouped.map(course => <section key={course.id}><h3>{course.title}</h3>{course.sections.map(section => <details open key={section.id}><summary>{section.title}</summary><ul>{section.activities.map(activity => <li key={activity.id}><span>{activity.title}</span><small>{activity.type} · {activity.estimatedMinutes} 分钟</small></li>)}</ul></details>)}</section>)}</div>
      <div className="article-list"><h3>知识文档</h3>{workspace.data?.articles.map(item => <button className={selectedId === item.id ? "selected" : ""} type="button" key={item.id} onClick={() => setSelectedId(item.id)}>{item.title}<small>{item.sectionTitle} · r{item.currentRevision}</small></button>)}</div>
    </aside>
    <main className="knowledge-main">
      <section className="content-card knowledge-document">{currentMarkdown ? <KnowledgeRenderer markdown={currentMarkdown} /> : <EmptyState title="选择一篇知识文档">课程树、搜索和导入结果会在这里打开安全 Markdown 阅读视图。</EmptyState>}</section>
      <section className="content-card import-panel">
        <div className="section-heading"><div><p className="eyebrow">Obsidian 增量导入</p><h2>预览冲突后再写入</h2></div><Stepper steps={["选择目录", "冲突预览", "导入报告"]} current={report ? 2 : preview ? 1 : 0} /></div>
        <FormField label="知识库根目录" hint="只读取该规范化目录内的 Markdown 与附件引用">{ids => <input {...ids} value={root} onChange={event => setRoot(event.target.value)} placeholder="D:\\Obsidian\\ComputerKnowledgeBase" />}</FormField>
        <div className="button-row"><Button disabled={!root || previewImport.isPending} onClick={() => previewImport.mutate()}>生成安全预览</Button>{preview && <Button variant="secondary" disabled={executeImport.isPending} onClick={() => executeImport.mutate()}>确认导入 {preview.newFiles + preview.changedFiles} 项</Button>}</div>
        {(previewImport.isError || executeImport.isError) && <Feedback tone="error" title="导入未执行">{(previewImport.error ?? executeImport.error)?.message}</Feedback>}
        {preview && <div className="import-summary"><strong>{preview.markdownFiles} 个 Markdown</strong><span>新增 {preview.newFiles}</span><span>冲突修订 {preview.changedFiles}</span><span>不变 {preview.unchangedFiles}</span><span>缺失附件 {preview.missingAttachments}</span><div className="preview-list">{preview.items.slice(0, 100).map(item => <div key={item.relativePath}><span className={`action-${item.action.toLowerCase()}`}>{item.action}</span><strong>{item.relativePath}</strong><small>{item.wikiLinks} 链接 · {item.attachments} 附件</small></div>)}</div></div>}
        {report && <Feedback tone={report.failed ? "warning" : "success"} title="增量导入完成">新增 {report.imported}，修订 {report.revised}，跳过 {report.skipped}，失败 {report.failed}。</Feedback>}
      </section>
    </main>
  </div>;
}
