import { useEffect, useId, useMemo, useState, type ComponentProps } from "react";
import ReactMarkdown from "react-markdown";
import rehypeKatex from "rehype-katex";
import rehypeSanitize from "rehype-sanitize";
import { defaultSchema } from "hast-util-sanitize";
import remarkFrontmatter from "remark-frontmatter";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import type { Root, RootContent, Text } from "mdast";
import type { Plugin } from "unified";
import { visit } from "unist-util-visit";
import { localAppRequest } from "../../shared/ipc";
import "katex/dist/katex.min.css";

const remarkSqlTeacherSyntax: Plugin<[], Root> = () => (tree) => {
  visit(tree, "blockquote", (node) => {
    const first = node.children[0];
    const marker = first?.type === "paragraph" ? first.children[0] : undefined;
    if (!marker || marker.type !== "text") return;
    const match = /^\[!([a-z0-9_-]+)]([+-])?[^\S\r\n]*([^\r\n]*)/i.exec(marker.value);
    if (!match) return;
    const [, type, fold, title] = match;
    // 命中正则时捕获组 1 必然存在；这里仅供类型收窄。
    if (!type) return;
    marker.value = marker.value.slice(match[0].length).trimStart();
    node.data = {
      ...node.data,
      hName: "aside",
      hProperties: {
        className: ["callout", `callout-${type.toLowerCase()}`, fold ? "callout-foldable" : ""].filter(Boolean),
        "data-callout": type.toLowerCase(),
        "data-fold": fold ?? "",
      },
    };
    const heading: RootContent = {
      type: "paragraph",
      data: { hName: "div", hProperties: { className: ["callout-title"] } },
      children: [{ type: "strong", children: [{ type: "text", value: title || type }] }],
    };
    node.children.unshift(heading);
  });

  visit(tree, "text", (node: Text, index, parent) => {
    if (index === undefined || !parent || !("children" in parent)) return;
    const pattern = /(!)?\[\[([^\]]+)]]/g;
    const children: RootContent[] = [];
    let cursor = 0;
    for (const match of node.value.matchAll(pattern)) {
      const offset = match.index ?? 0;
      if (offset > cursor) children.push({ type: "text", value: node.value.slice(cursor, offset) });
      const target = match[2];
      if (target === undefined) continue;
      const parts = target.split("|");
      // split 至少返回一个元素；undefined 时回退到原始 target。
      const destination = parts[0] ?? target;
      const alias = parts[1];
      if (match[1]) {
        children.push({
          type: "text",
          value: alias || destination,
          data: {
            hName: "span",
            hProperties: { className: ["knowledge-embed"], "data-target": destination },
          },
        });
      } else {
        children.push({ type: "link", url: `#knowledge?target=${encodeURIComponent(destination)}`, children: [
          { type: "text", value: alias || destination },
        ] });
      }
      cursor = offset + match[0].length;
    }
    if (children.length === 0) return;
    if (cursor < node.value.length) children.push({ type: "text", value: node.value.slice(cursor) });
    parent.children.splice(index, 1, ...children);
    return index + children.length;
  });
};

const sanitizeSchema = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames ?? []), "aside", "img"],
  attributes: {
    ...defaultSchema.attributes,
    "*": [
      ...(defaultSchema.attributes?.["*"] ?? []),
      "className",
      "dataCallout",
      "dataFold",
      "dataTarget",
    ],
    // v3.4.3 OKB-7: keep official-bundle image references (relative attachments/… paths);
    // the actual bytes are swapped in at render time via the bridge, never from raw markdown.
    img: [...(defaultSchema.attributes?.img ?? []), "src", "alt", "title", "loading"],
  },
};

export default function KnowledgeRenderer({
  markdown,
  articleId,
}: {
  markdown: string;
  articleId?: string;
}) {
  const components = useMemo(
    () => ({
      code: MarkdownCode,
      img: (props: ComponentProps<"img">) => <KnowledgeImage {...props} articleId={articleId} />,
    }),
    [articleId],
  );
  return (
    <div data-no-translate>
      <ReactMarkdown
        remarkPlugins={[remarkFrontmatter, remarkGfm, remarkMath, remarkSqlTeacherSyntax]}
        rehypePlugins={[[rehypeSanitize, sanitizeSchema], [rehypeKatex, { trust: false, maxSize: 10, maxExpand: 1000 }]]}
        components={components}
        skipHtml
      >
        {normalizeMarkdown(markdown)}
      </ReactMarkdown>
    </div>
  );
}

const STASH_PREFIX = "\u0000SQLTEACHER-STASH-";

// v3.4.4：渲染前对 Obsidian 源做轻量归一化——
// ① \(…\) 与 \[…\] 数学定界符转 $…$ / $$…$$（remark-math 只认 $ 定界，否则公式原样漏出）；
// ② 「**加粗 **」闭合前的多余空格（CommonMark 不识别，粗体会失效）。
// 围栏/行内代码先摘出再还原，避免误改代码内容。
export function normalizeMarkdown(markdown: string): string {
  if (!markdown.includes("\\(") && !markdown.includes("\\[") && !/\*\*[^*\n]+\s+\*\*/.test(markdown)) {
    return markdown;
  }
  const stashed: string[] = [];
  const stash = (value: string) => {
    stashed.push(value);
    return `${STASH_PREFIX}${stashed.length - 1}\u0000`;
  };
  const withoutCode = markdown
    .replace(/```[\s\S]*?```|~~~[\s\S]*?~~~/g, stash)
    .replace(/`[^`\n]*`/g, stash);
  const normalized = withoutCode
    .replace(/\\\(([\s\S]*?)\\\)/g, (_match, body: string) => `$${body}$`)
    .replace(/\\\[([\s\S]*?)\\\]/g, (_match, body: string) => `$$${body}$$`)
    .replace(/\*\*([^*\n]+?)\s+\*\*/g, (_match, body: string) => `**${body}**`);
  return normalized.replace(
    /\u0000SQLTEACHER-STASH-(\d+)\u0000/g,
    (_match, index: string) => stashed[Number(index)] ?? "",
  );
}

// v3.4.4：Java 桥并发许可有限（超限返回 BUSY），图片资产请求走小并发队列串行放行。
const ASSET_CONCURRENCY = 2;
const assetWaiters: Array<() => void> = [];
let activeAssetRequests = 0;
function acquireAssetSlot(): Promise<void> {
  return new Promise((resolve) => {
    if (activeAssetRequests < ASSET_CONCURRENCY) {
      activeAssetRequests += 1;
      resolve();
    } else {
      assetWaiters.push(resolve);
    }
  });
}
function releaseAssetSlot() {
  const next = assetWaiters.shift();
  if (next) next();
  else activeAssetRequests -= 1;
}

// v3.4.3 OKB-7: official-bundle documents reference images as attachments/<docId>/<file>.
// The WebView cannot read local files directly, so resolve them through the Java bridge, which
// scopes the read to the article's bundle asset root and returns image bytes as a data URL.
function KnowledgeImage({
  src,
  alt,
  articleId,
}: {
  src?: string;
  alt?: string;
  articleId?: string;
}) {
  const isBundleAsset = typeof src === "string" && src.startsWith("attachments/");
  const [dataUrl, setDataUrl] = useState<string>();
  const [failed, setFailed] = useState(false);
  const [failReason, setFailReason] = useState("");
  useEffect(() => {
    if (!isBundleAsset || !articleId || !src) return;
    let active = true;
    let released = false;
    const path = decodeURIComponent(src.slice("attachments/".length));
    void acquireAssetSlot().then(() => {
      // 卸载后才拿到配额：立刻归还，不发请求。
      if (!active) {
        releaseAssetSlot();
        released = true;
        return;
      }
      localAppRequest<{ contentType: string; dataBase64: string }>("knowledge.article.asset", {
        articleId,
        path,
      })
        .then((value) => {
          if (active) setDataUrl(`data:${value.contentType};base64,${value.dataBase64}`);
        })
        .catch((error: Error) => {
          if (active) {
            setFailed(true);
            setFailReason(error.message);
          }
        })
        .finally(() => {
          if (!released) releaseAssetSlot();
        });
    });
    return () => {
      active = false;
    };
  }, [isBundleAsset, articleId, src]);
  if (!isBundleAsset) return <img src={src} alt={alt} loading="lazy" />;
  if (!articleId || failed)
    return (
      <span className="knowledge-image-missing">
        图片不可用{failReason ? `：${failReason}` : `：${alt || src}`}
      </span>
    );
  if (!dataUrl) return <span className="knowledge-image-loading">正在加载图片…</span>;
  return <img src={dataUrl} alt={alt} loading="lazy" />;
}

function MarkdownCode({ className, children, ...props }: ComponentProps<"code">) {
  const language = /language-([\w-]+)/.exec(className ?? "")?.[1];
  const source = String(children).replace(/\n$/, "");
  if (language === "mermaid") return <MermaidDiagram source={source} />;
  if (language === "dataview") {
    return <code className="unsupported-syntax" {...props}>动态 Dataview 不执行{"\n"}{source}</code>;
  }
  return <code className={className} {...props}>{children}</code>;
}

// v3.5.0 SCH-3：导出供数据页「外键关系图」复用；严格模式渲染保持只读。
export function MermaidDiagram({ source }: { source: string }) {
  const id = `mermaid-${useId().replace(/:/g, "")}`;
  const [svg, setSvg] = useState<string>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let active = true;
    void import("mermaid").then(async ({ default: mermaid }) => {
      mermaid.initialize({ startOnLoad: false, securityLevel: "strict", maxTextSize: 50_000, theme: "neutral" });
      try {
        const result = await mermaid.render(id, source);
        if (active) setSvg(result.svg);
      } catch {
        if (active) setFailed(true);
      }
    });
    return () => { active = false; };
  }, [id, source]);
  if (failed) return <pre className="unsupported-syntax">Mermaid 无法安全渲染{"\n"}{source}</pre>;
  if (!svg) return <div className="diagram-placeholder">正在渲染 Mermaid…</div>;
  return <div className="mermaid-diagram" role="img" aria-label="Mermaid 图表" dangerouslySetInnerHTML={{ __html: svg }} />;
}
