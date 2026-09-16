import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgeRenderer, { normalizeMarkdown } from "./KnowledgeRenderer";

const requestMock = vi.fn();
vi.mock("../../shared/ipc", () => ({
  localAppRequest: (...args: unknown[]) => requestMock(...args),
  localAppRequestWithId: (...args: unknown[]) => requestMock(...args),
  cancelLocalAppRequest: vi.fn(),
}));

vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: "<svg><text>safe diagram</text></svg>" }),
  },
}));

describe("KnowledgeRenderer", () => {
  beforeEach(() => {
    requestMock.mockReset();
  });

  it("renders callouts, wiki links, embeds, math, and a safe Mermaid boundary", async () => {
    const markdown = `> [!important]- 权威边界
> 确定性规则

$x^2$

[[SQL 安全|安全链接]] ![[学习事件模型]]

\`\`\`mermaid
flowchart LR
A --> B
\`\`\``;

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    // important 是 tip 的官方别名：样式归一到标准类型，原始类型保留为附加类。
    const callout = container.querySelector("details.callout-tip.callout-important");
    expect(callout).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "安全链接" })).toHaveAttribute("href", "#knowledge?target=SQL%20%E5%AE%89%E5%85%A8");
    expect(container.querySelector(".knowledge-embed")).toHaveTextContent("学习事件模型");
    expect(container.querySelector(".katex")).toBeInTheDocument();
    expect(await screen.findByLabelText("Mermaid 图表")).toHaveTextContent("safe diagram");
  });

  it("follows the official callout spec: fold markers, aliases, and note fallback", () => {
    const markdown = `> [!example]+ 展开的例题
> 内容可见

> [!faq]- 常见问题
> faq 是 question 的别名

> [!theorem] 自定义类型
> 未知类型回退 note

> [!plain] 另一个未知类型
> 正文`;

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    const opened = container.querySelector("details.callout-example");
    expect(opened).toHaveAttribute("open");
    expect(opened?.querySelector("summary.callout-title")).toHaveTextContent("展开的例题");
    const collapsed = container.querySelector("details.callout-question.callout-faq");
    expect(collapsed).not.toHaveAttribute("open");
    expect(collapsed?.querySelector("summary.callout-title")).toHaveTextContent("常见问题");
    // 未知类型保留自身标识类，配色由基础 .callout 的 note 默认色回退（官方行为）。
    const theorem = container.querySelector("aside.callout-theorem");
    expect(theorem).toHaveTextContent("自定义类型");
    expect(container.querySelector("aside.callout-plain")).toHaveTextContent("正文");
  });

  it("renders highlights as mark and drops Obsidian comments", () => {
    const markdown = "这是==重点内容==，而%%这些注释不渲染%%。";

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    expect(container.querySelector("mark")).toHaveTextContent("重点内容");
    expect(container).not.toHaveTextContent("这些注释不渲染");
    expect(container).toHaveTextContent("这是重点内容，而。");
  });

  it("renders multi-line aligned display math inside callouts and blockquotes", () => {
    // 用户报告（2026-09-17）：$$ 与 egin{aligned} 同行且位于块引用内时，
    // remark-math 的 flow 围栏识别失败，KaTeX 收到残体报 Expected 'EOF', got '&'。
    const markdown = `> [!example] 例题
> 问题变为：解不等式 $\\lvert 2x-8\\rvert<2$：
> $$\\begin{aligned}
> \\lvert 2x-8\\rvert<2 &\\quad\\Longrightarrow\\quad -2<2x-8<2 &&\\text{去绝对值}\\\\
> &\\quad\\Longrightarrow\\quad 3<x<5 &&\\text{解出 }x
> \\end{aligned}$$ 将 $x$ 保持在范围内。`;

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    expect(container.querySelector(".katex-error")).toBeNull();
    expect(container.querySelector(".katex-display")).not.toBeNull();
    // 行尾 $x$ 是行内公式（渲染为 KaTeX span），只断言中文尾文存在。
    expect(container).toHaveTextContent("保持在范围内");
  });

  it("renders \tag equations as display math instead of erroring", () => {
    // \tag 只在 display 模式受支持：独立成行的成对 $$…$$ 按块渲染（含紧贴段落的情形）；
    // 句中行内公式携带 \tag 时升级为 display 块。
    const B = String.fromCharCode(92);
    const markdown = [
      "用函数记号，",
      "> $$" + B + "frac{d}{dx}[f(x)g(x)]=f(x)g'(x)+f'(x)g(x), " + B + "tag{1}$$",
      "",
      "句中版本 $" + B + "frac{a}{b}" + B + "tag{2}$ 后续文字。",
    ].join("\n");

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    expect(container.querySelector(".katex-error")).toBeNull();
    // 块级公式（独立 $$ 行）走 display 渲染并携带编号；
    // 句中行内公式剥离 \tag 后正常渲染，不再报错。
    expect(container.querySelectorAll(".katex-display").length).toBe(1);
    expect(container).toHaveTextContent("后续文字");
  });

  it("applies Obsidian image sizing from the alt suffix and caps overflow", async () => {
    requestMock.mockResolvedValue({ contentType: "image/png", dataBase64: "QUJD" });
    const markdown = "![图|300](attachments/a/fig.png)\n\n![600](attachments/a/wide.png)";

    render(<KnowledgeRenderer markdown={markdown} articleId="a-1" />);

    const sized = (await screen.findByAltText("图")) as HTMLImageElement;
    expect(sized.style.width).toBe("300px");
    expect(sized.style.maxWidth).toBe("100%");
    const wide = (await screen.findByAltText("")) as HTMLImageElement;
    expect(wide.style.width).toBe("600px");
  });

  it("breaks single newlines like Obsidian reading view", () => {
    const markdown = "第一行末尾没有空行\n第二行直接接上。";

    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    expect(container.querySelector("br")).not.toBeNull();
    expect(container.textContent).toContain("第一行末尾没有空行");
    expect(container.textContent).toContain("第二行直接接上。");
  });

  it("does not execute raw HTML or Dataview", () => {
    const markdown = `<script>alert('x')</script>

\`\`\`dataview
TABLE status
\`\`\``;
    const { container } = render(<KnowledgeRenderer markdown={markdown} />);

    expect(container.querySelector("script")).not.toBeInTheDocument();
    expect(screen.getByText(/动态 Dataview 不执行/)).toBeInTheDocument();
  });

  it("resolves official-bundle attachments through the bridge into data URLs", async () => {
    requestMock.mockResolvedValue({ contentType: "image/png", dataBase64: "QUJD" });
    const markdown = "![E-R 图](attachments/chap1/intro/fig.png)";

    const { container } = render(<KnowledgeRenderer markdown={markdown} articleId="a-1" />);

    const img = (await screen.findByAltText("E-R 图")) as HTMLImageElement;
    expect(img.getAttribute("src")).toBe("data:image/png;base64,QUJD");
    expect(requestMock).toHaveBeenCalledWith("knowledge.article.asset", {
      articleId: "a-1",
      path: "chap1/intro/fig.png",
    });
    expect(container.querySelector(".knowledge-image-missing")).not.toBeInTheDocument();
  });

  it("shows a placeholder when a bundle image cannot be resolved", async () => {
    requestMock.mockRejectedValue(new Error("KNOWLEDGE_ASSET_NOT_FOUND"));
    const markdown = "![缺失图](attachments/chap1/intro/gone.png)";

    render(<KnowledgeRenderer markdown={markdown} articleId="a-1" />);

    // v3.4.4：失败占位直接显示桥端错误码，便于区分未打包/缺失/权限等原因。
    expect(await screen.findByText(/图片不可用：KNOWLEDGE_ASSET_NOT_FOUND/)).toBeInTheDocument();
  });

  it("does not call the bridge for bundle images when no article context is present", () => {
    const markdown = "![E-R 图](attachments/chap1/intro/fig.png)";

    render(<KnowledgeRenderer markdown={markdown} />);

    expect(screen.getByText(/图片不可用/)).toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalled();
  });
});

describe("normalizeMarkdown", () => {
  it.each([
    ["\\(E=mc^2\\)", "$E=mc^2$"],
    ["\\[\nE=mc^2\n\\]", "$$\nE=mc^2\n$$"],
  ])("converts Obsidian math delimiters to dollar syntax (%s)", (input, expected) => {
    expect(normalizeMarkdown(input)).toBe(expected);
  });

  it("collapses stray spaces before closing bold markers", () => {
    expect(normalizeMarkdown("由**表 (table) **构成")).toBe("由**表 (table)**构成");
    expect(normalizeMarkdown("**正常加粗**保持不变")).toBe("**正常加粗**保持不变");
  });

  it("leaves dollar math and plain text untouched", () => {
    expect(normalizeMarkdown("$x$ 与 $y$")).toBe("$x$ 与 $y$");
    expect(normalizeMarkdown("普通文本")).toBe("普通文本");
  });

  it("never rewrites backslash brackets inside fenced or inline code", () => {
    const fenced = "```\n\\(not math\\)\n\\[also not\\]\n```";
    expect(normalizeMarkdown(fenced)).toBe(fenced);
    const inline = "行内代码 `\\(x\\)` 与 \\(y\\)";
    expect(normalizeMarkdown(inline)).toBe("行内代码 `\\(x\\)` 与 $y$");
  });
});
