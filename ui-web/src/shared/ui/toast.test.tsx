import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Toaster, useToast } from "./index";

function ToastProbe() {
  const toast = useToast();
  return (
    <div>
      <button onClick={() => toast("success", "操作成功提示")}>成功</button>
      <button onClick={() => toast("error", "操作失败提示")}>失败</button>
    </div>
  );
}

describe("Toaster", () => {
  it("shows and auto-dismisses push notifications", async () => {
    render(
      <Toaster>
        <ToastProbe />
      </Toaster>,
    );
    fireEvent.click(screen.getByRole("button", { name: "成功" }));
    expect(await screen.findByText("操作成功提示")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "失败" }));
    expect(await screen.findByText("操作失败提示")).toBeInTheDocument();
    await waitFor(
      () => expect(screen.queryByText("操作成功提示")).toBeNull(),
      { timeout: 5000 },
    );
  });
});
