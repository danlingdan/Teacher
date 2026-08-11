import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it, vi } from "vitest";
import { Button, DataTable, Dialog, FormField, Stepper, TreeView } from ".";

describe("Alpha.2 UI primitives", () => {
  it("connects labels, help and validation messages", () => {
    render(<FormField label="课程名称" hint="公开名称" error="必填">{props => <input {...props} />}</FormField>);
    const input = screen.getByLabelText("课程名称");
    expect(input).toHaveAccessibleDescription("公开名称 必填");
  });

  it("supports keyboard dismissal for dialogs", async () => {
    const close = vi.fn();
    render(<Dialog open title="确认操作" onClose={close}><Button>确认</Button></Dialog>);
    expect(screen.getByRole("dialog")).toHaveAccessibleName("确认操作");
    await userEvent.keyboard("{Escape}");
    expect(close).toHaveBeenCalledOnce();
  });

  it("exposes semantic progress, table and tree structures", async () => {
    const view = render(<><Stepper steps={["开始", "完成"]} current={0} /><DataTable caption="活动" rows={[{ name: "查询" }]} columns={[{ key: "name", title: "名称", render: row => row.name }]} /><TreeView label="课程" nodes={[{ id: "1", label: "数据库", children: [{ id: "2", label: "查询" }] }]} /></>);
    expect(screen.getByText("开始").closest("li")).toHaveAttribute("aria-current", "step");
    expect(screen.getByRole("table", { name: "活动" })).toBeInTheDocument();
    expect(screen.getByRole("tree", { name: "课程" })).toBeInTheDocument();
    expect((await axe.run(view.container)).violations).toEqual([]);
  });
});
