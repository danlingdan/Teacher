import { Component, type ReactNode } from "react";
import { Button } from "../shared/ui";

type Props = { children: ReactNode };
type State = { error: Error | null };

/** 路由级兜底：IPC 数据结构与前端断言不符等渲染异常不再白屏整棵树。 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error) {
    console.error("Unexpected UI error", error);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <section
        className="content-card"
        role="alert"
        style={{ padding: "48px 40px", display: "grid", gap: 12, justifyItems: "start" }}
      >
        <p className="eyebrow">意外错误</p>
        <h2>页面渲染出现问题</h2>
        <p style={{ color: "var(--muted)" }}>
          {this.state.error.message || "未知错误"}。本地学习数据不受影响，可以重试或返回今天页。
        </p>
        <div className="button-row">
          <Button variant="secondary" onClick={() => this.setState({ error: null })}>
            重试
          </Button>
          <Button
            onClick={() => {
              this.setState({ error: null });
              window.location.hash = "#/today";
            }}
          >
            返回今天
          </Button>
        </div>
      </section>
    );
  }
}
