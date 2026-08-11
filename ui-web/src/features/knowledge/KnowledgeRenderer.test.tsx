import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import KnowledgeRenderer from "./KnowledgeRenderer";

vi.mock("mermaid", () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: "<svg><text>safe diagram</text></svg>" }),
  },
}));

describe("KnowledgeRenderer", () => {
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
});
