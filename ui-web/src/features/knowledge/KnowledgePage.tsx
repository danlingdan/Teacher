import type { KnowledgeSample } from "../../shared/types";
import KnowledgeRenderer from "./KnowledgeRenderer";

export default function KnowledgePage({ sample }: { sample?: KnowledgeSample }) {
  if (!sample) {
    return (
      <section className="empty-state">
        <p className="eyebrow">知识内容</p>
        <h2>文档尚未载入</h2>
        <p>连接 Java Sidecar 后，渲染器会接收受限 Markdown 内容模型。</p>
      </section>
    );
  }
  return (
    <div className="knowledge-layout">
      <aside className="document-outline">
        <p className="eyebrow">真实样本</p>
        <strong>{sample.title}</strong>
        <span className="safe-chip">原始 HTML 禁用</span>
        <span className="safe-chip">外部资源禁用</span>
      </aside>
      <article className="content-card knowledge-document">
        <KnowledgeRenderer markdown={sample.markdown} />
      </article>
    </div>
  );
}
