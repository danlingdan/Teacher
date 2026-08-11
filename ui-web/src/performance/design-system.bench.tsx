import { bench, describe } from "vitest";
import { renderToString } from "react-dom/server";
import { DataTable, Stepper, TreeView } from "../shared/ui";

describe("design system server-render baseline", () => {
  bench("renders representative data primitives", () => {
    renderToString(<><Stepper steps={["一", "二", "三"]} current={1} /><DataTable caption="活动" rows={Array.from({ length: 40 }, (_, id) => ({ id }))} columns={[{ key: "id", title: "编号", render: row => row.id }]} /><TreeView label="目录" nodes={[{ id: "root", label: "课程", children: [{ id: "child", label: "知识点" }] }]} /></>);
  });
});
