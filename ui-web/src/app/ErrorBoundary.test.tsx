import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ErrorBoundary } from "./ErrorBoundary";

function Bomb({ message }: { message: string }): never {
  throw new Error(message);
}

// 仅在 shouldThrow 为真时抛错，用于验证“重试”后的恢复路径。
let shouldThrow: boolean;
function FlakyChild() {
  if (shouldThrow) throw new Error("首次渲染失败");
  return <div>内容已恢复</div>;
}

describe("ErrorBoundary", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("shows the fallback alert UI with the error message when a child throws", () => {
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    render(
      <ErrorBoundary>
        <Bomb message="IPC 数据结构与前端断言不符" />
      </ErrorBoundary>,
    );

    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByText("页面渲染出现问题")).toBeInTheDocument();
    expect(screen.getByText(/IPC 数据结构与前端断言不符/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "返回今天" })).toBeInTheDocument();
    expect(errorSpy).toHaveBeenCalled();
  });

  it("renders children again after the retry button resets the boundary", () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    shouldThrow = true;
    render(
      <ErrorBoundary>
        <FlakyChild />
      </ErrorBoundary>,
    );
    expect(screen.getByRole("alert")).toBeInTheDocument();

    shouldThrow = false;
    fireEvent.click(screen.getByRole("button", { name: "重试" }));

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("内容已恢复")).toBeInTheDocument();
  });
});
