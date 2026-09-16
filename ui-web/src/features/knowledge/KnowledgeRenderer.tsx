import { useEffect, useId, useMemo, useState, type ComponentProps } from "react";
import ReactMarkdown from "react-markdown";
import rehypeKatex from "rehype-katex";
import rehypeSanitize from "rehype-sanitize";
import { defaultSchema } from "hast-util-sanitize";
import remarkBreaks from "remark-breaks";
import remarkFrontmatter from "remark-frontmatter";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import type { Root, RootContent, Text } from "mdast";
import type { Plugin } from "unified";
import { visit } from "unist-util-visit";
import { localAppRequest } from "../../shared/ipc";
import "katex/dist/katex.min.css";

// Obsidian 官方类型表（help.obsidian.md → Callouts → Supported types）：
// 别名归一为标准类型样式；未知类型保留标识、由基础 .callout 默认色回退为 note 观感（官方行为），data-callout 保留原始标识供 CSS 定制。
const CALLOUT_TYPE_ALIASES: Record<string, string> = {
  summary: "abstract",
  tldr: "abstract",
  hint: "tip",
  important: "tip",
  check: "success",
  done: "success",
  help: "question",
  faq: "question",
  caution: "warning",
  attention: "warning",
  fail: "failure",
  missing: "failure",
  error: "danger",
  cite: "quote",
};

const remarkSqlTeacherSyntax: Plugin<[], Root> = () => (tree) => {
  visit(tree, "blockquote", (node) => {
    const first = node.children[0];
    const marker = first?.type === "paragraph" ? first.children[0] : undefined;
    if (!marker || marker.type !== "text") return;
    const match = /^\[!([a-z0-9_-]+)]([+-])?[^\S\r\n]*([^\r\n]*)/i.exec(marker.value);
    if (!match) return;
    const [, rawType, fold, title] = match;
    // 命中正则时捕获组 1 必然存在；这里仅供类型收窄。
    if (!rawType) return;
    const type = rawType.toLowerCase();
    const canonical = CALLOUT_TYPE_ALIASES[type] ?? type;
    marker.value = marker.value.slice(match[0].length).trimStart();
    // 折叠标记 + / -（官方语法）：渲染为 details/summary，+ 默认展开、- 默认收起。
    // 规范类型类驱动配色；原始类型保留为附加类，等价于 Obsidian 的 data-callout 定制点。
    const classes = ["callout", `callout-${canonical}`];
    if (type !== canonical) classes.push(`callout-${type}`);
    if (fold) {
      node.data = {
        ...node.data,
        hName: "details",
        hProperties: {
          className: [...classes, "callout-foldable"],
          ...(fold === "+" ? { open: true } : {}),
        },
      };
    } else {
      node.data = {
        ...node.data,
        hName: "aside",
        hProperties: { className: classes },
      };
    }
    const heading: RootContent = {
      type: "paragraph",
      data: {
        hName: fold ? "summary" : "div",
        hProperties: { className: ["callout-title"] },
      },
      children: [{ type: "strong", children: [{ type: "text", value: title || type }] }],
    };
    node.children.unshift(heading);
  });

  visit(tree, "text", (node: Text, index, parent) => {
    if (index === undefined || !parent || !("children" in parent)) return;
    const pattern = /(!)?\[\[([^\]]+)]]|==([^=\n]+)==|%%([\s\S]*?)%%/g;
    const children: RootContent[] = [];
    let cursor = 0;
    for (const match of node.value.matchAll(pattern)) {
      const offset = match.index ?? 0;
      if (offset > cursor) children.push({ type: "text", value: node.value.slice(cursor, offset) });
      const target = match[2];
      if (target === undefined) {
        if (match[3] !== undefined) {
          // ==高亮==：Obsidian 渲染为 <mark>。
          children.push({
            type: "emphasis",
            data: { hName: "mark" },
            children: [{ type: "text", value: match[3] }],
          });
        }
        // %%注释%%：阅读视图不渲染（官方行为），直接丢弃。
      } else {
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
  tagNames: [...(defaultSchema.tagNames ?? []), "aside", "img", "details", "summary", "mark"],
  attributes: {
    ...defaultSchema.attributes,
    "*": [
      ...(defaultSchema.attributes?.["*"] ?? []),
      "className",
      "dataCallout",
      "dataFold",
      "dataTarget",
    ],
    details: [...(defaultSchema.attributes?.details ?? []), "open"],
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
        // remark-breaks：Obsidian 默认"严格换行"关闭，单换行即断行（对齐官方阅读视图）。
        remarkPlugins={[remarkFrontmatter, remarkGfm, remarkMath, remarkBreaks, remarkSqlTeacherSyntax]}
        // MathJax 与 Obsidian 同引擎：语法覆盖完整（\tag、aligned 等全部按官方行为渲染）。
        // KaTeX 覆盖当前知识库全部公式；\tag/多行 aligned 的解析兼容由
        // normalizeMarkdown 规范化保证（remark-rehype 的 flow 围栏要求 $$ 独立成行）。
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
  if (
    !markdown.includes("\\(") &&
    !markdown.includes("\\[") &&
    !/\*\*[^*\n]+\s+\*\*/.test(markdown) &&
    !markdown.includes("$$")
  ) {
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
  // Obsidian 容忍 $$ 开栏/合栏与公式内容同行（甚至出现在块引用里）；remark-math 的
  // flow 围栏要求 $$ 独占行首，同行内容会被当围栏元数据吞掉，KaTeX 收到残体报
  // "Expected 'EOF', got '&'"。这里按行把 $$ 归位到独立行（保留块引用前缀）。
  const fenceState = { inside: false };
  const withFences: string[] = [];
  for (const line of normalized.split("\n")) {
    splitMathFenceLine(withFences, line, fenceState);
  }
  const result = withFences.join("\n");
  return result.replace(
    /\u0000SQLTEACHER-STASH-(\d+)\u0000/g,
    (_match, index: string) => stashed[Number(index)] ?? "",
  );
}

/** 把与内容同行的 $$ 围栏拆到独立行；state.inside 表示当前已处于未合栏的多行公式块中。 */
function splitMathFenceLine(lines: string[], line: string, state: { inside: boolean }): void {
  const match = /^([ \t]*(?:>[ \t]?)*)?(.*)$/.exec(line);
  const prefix = match?.[1] ?? "";
  const rawBody = match?.[2] ?? line;
  if (state.inside) {
    const close = rawBody.indexOf("$$");
    if (close === -1) {
      lines.push(line);
      return;
    }
    state.inside = false;
    const before = rawBody.slice(0, close);
    const after = rawBody.slice(close + 2);
    if (before.trim()) lines.push(prefix + before);
    lines.push(prefix + "$$");
    if (after.trim()) lines.push(prefix + after);
    return;
  }
  // 段落文本里的行内公式无法承载 \tag（KaTeX 限定 display 模式）：按 Obsidian 的
  // 行为直接剥离标记、保留公式。注意最终必须推送 prefix + 内容——丢失前缀会把
  // 块引用拆成散落的顶层段落。
  const body = stripTagMarkers(rawBody);
  if (body.startsWith("$$")) {
    const rest = body.slice(2);
    // 单行成对的 $$…$$：Obsidian 视作独立 display 块（\tag 等 display 特性只在
    // 块模式合法）。拆成 $$ / 内容 / $$ 三行进入 flow 模式；紧贴段落时补空行断开。
    if (rest.endsWith("$$")) {
      const content = rest.slice(0, -2);
      if (!content.trim()) {
        lines.push(line);
        return;
      }
      const last = lines[lines.length - 1];
      const glued = last !== undefined && last.trim() !== "";
      if (glued) lines.push(prefix);
      lines.push(prefix + "$$");
      lines.push(prefix + content);
      lines.push(prefix + "$$");
      if (glued) lines.push(prefix);
      return;
    }
    if (rest.trim() === "" || rest.includes("$$")) {
      // 纯开栏行，或行内成对且带尾文：解析器原生支持，不动。
      lines.push(line);
      return;
    }
    state.inside = true;
    lines.push(`${prefix}$$`);
    lines.push(prefix + rest);
    return;
  }
  lines.push(prefix + body);
}

/** 移除行内文本中的 \tag{…} 标记（KaTeX 行内模式不支持，Obsidian 同样按块渲染）。 */
function stripTagMarkers(text: string): string {
  const BS = String.fromCharCode(92);
  const marker = BS + "tag";
  let value = text;
  let at = value.indexOf(marker);
  while (at >= 0) {
    const close = value.indexOf("}", at);
    value = close >= 0 ? value.slice(0, at) + value.slice(close + 1) : value.slice(0, at);
    at = value.indexOf(marker);
  }
  return value;
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
  // Obsidian 图片尺寸：![alt|300](src) 或 ![300](src)（官方 |宽度 语法在打包时
  // 保留进 alt），渲染端换算为像素宽并限制不超过容器。
  let displayAlt = alt ?? "";
  let width: number | undefined;
  const sized = /\|\s*(\d{2,4})\s*$/.exec(displayAlt);
  if (sized) {
    width = Number(sized[1]);
    displayAlt = displayAlt.slice(0, sized.index).trim();
  } else if (/^\d{2,4}$/.test(displayAlt.trim())) {
    width = Number(displayAlt.trim());
    displayAlt = "";
  }
  const sizedStyle = width ? { width, maxWidth: "100%", height: "auto" } : { maxWidth: "100%", height: "auto" };
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
  if (!isBundleAsset) return <img src={src} alt={displayAlt} style={sizedStyle} loading="lazy" />;
  if (!articleId || failed)
    return (
      <span className="knowledge-image-missing">
        图片不可用{failReason ? `：${failReason}` : `：${displayAlt || src}`}
      </span>
    );
  if (!dataUrl) return <span className="knowledge-image-loading">正在加载图片…</span>;
  return <img src={dataUrl} alt={displayAlt} style={sizedStyle} loading="lazy" />;
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
