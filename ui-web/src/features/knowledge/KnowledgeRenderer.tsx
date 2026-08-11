import { useEffect, useId, useState, type ComponentProps } from "react";
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
import "katex/dist/katex.min.css";

const remarkSqlTeacherSyntax: Plugin<[], Root> = () => (tree) => {
  visit(tree, "blockquote", (node) => {
    const first = node.children[0];
    const marker = first?.type === "paragraph" ? first.children[0] : undefined;
    if (!marker || marker.type !== "text") return;
    const match = /^\[!([a-z0-9_-]+)]([+-])?[^\S\r\n]*([^\r\n]*)/i.exec(marker.value);
    if (!match) return;
    const [, type, fold, title] = match;
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
      const [destination, alias] = target.split("|");
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
  tagNames: [...(defaultSchema.tagNames ?? []), "aside"],
  attributes: {
    ...defaultSchema.attributes,
    "*": [
      ...(defaultSchema.attributes?.["*"] ?? []),
      "className",
      "dataCallout",
      "dataFold",
      "dataTarget",
    ],
  },
};

export default function KnowledgeRenderer({ markdown }: { markdown: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkFrontmatter, remarkGfm, remarkMath, remarkSqlTeacherSyntax]}
      rehypePlugins={[[rehypeSanitize, sanitizeSchema], [rehypeKatex, { trust: false, maxSize: 10, maxExpand: 1000 }]]}
      components={{ code: MarkdownCode }}
      skipHtml
    >
      {markdown}
    </ReactMarkdown>
  );
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

function MermaidDiagram({ source }: { source: string }) {
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
