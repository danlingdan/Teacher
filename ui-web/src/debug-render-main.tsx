import { createRoot } from "react-dom/client";
import KnowledgeRenderer from "./features/knowledge/KnowledgeRenderer";
import "./App.css";

const B = String.fromCharCode(92);
const sample = [
  "> [!example] 例题：乘积法则",
  "> 问题变为：解不等式 $" + B + "lvert 2x-8" + B + "rvert<2$：",
  "> $$" + B + "begin{aligned}",
  "> " + B + "lvert 2x-8" + B + "rvert<2 &" + B + "quad" + B + "Longrightarrow" + B + "quad -2<2x-8<2 &&" + B + "text{去绝对值}" + B + B,
  "> &" + B + "quad" + B + "Longrightarrow" + B + "quad 3<x<5 &&" + B + "text{解出 }x",
  "> " + B + "end{aligned}$$ 将 $x$ 保持在范围内。",
  "",
  "> [!info]- 折叠的提示（默认收起）",
  "> 折叠内容",
  "",
  "> [!warning] 警告样式",
  "> 警告内容",
  "",
  "用函数记号，",
  "> $$" + B + "frac{d}{dx}[f(x)g(x)]=f(x)g'(x)+f'(x)g(x), " + B + "quad" + B + "text{或}" + B + "quad(fg)'=fg'+f'g. " + B + "tag{1}$$",
  "",
  "==高亮文本== 与 行内公式 $E=mc^2$。",
].join("\n");

createRoot(document.getElementById("root")!).render(
  <div className="knowledge-workspace" style={{ maxWidth: 720, margin: "24px auto", padding: "0 16px" }}>
    <aside className="knowledge-sidebar content-card" style={{ padding: 16 }}>
      <div className="course-tree" data-testid="callout-sample">
        <KnowledgeRenderer markdown={sample} />
      </div>
    </aside>
  </div>,
);
