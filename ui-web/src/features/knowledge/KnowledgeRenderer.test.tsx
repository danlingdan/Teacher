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

    expect(container.querySelector(".callout-important")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "安全链接" })).toHaveAttribute("href", "#knowledge?target=SQL%20%E5%AE%89%E5%85%A8");
    expect(container.querySelector(".knowledge-embed")).toHaveTextContent("学习事件模型");
    expect(container.querySelector(".katex")).toBeInTheDocument();
    expect(await screen.findByLabelText("Mermaid 图表")).toHaveTextContent("safe diagram");
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
