import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import KnowledgeRenderer from "./KnowledgeRenderer";

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

    expect(await screen.findByText(/图片不可用：缺失图/)).toBeInTheDocument();
  });

  it("does not call the bridge for bundle images when no article context is present", () => {
    const markdown = "![E-R 图](attachments/chap1/intro/fig.png)";

    render(<KnowledgeRenderer markdown={markdown} />);

    expect(screen.getByText(/图片不可用/)).toBeInTheDocument();
    expect(requestMock).not.toHaveBeenCalled();
  });
});
