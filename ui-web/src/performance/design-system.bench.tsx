import { test } from "vitest";
import { renderToString } from "react-dom/server";
import { DataTable, Stepper, TreeView } from "../shared/ui";

// vitest 5 迁移：bench 由顶层导入改为 test 上下文 fixture（Tinybench 驱动），
// 单基准经注册返回值 .run() 执行；npm run test:performance 入口不变。
test("design system server-render baseline", async ({ bench }) => {
  await bench("renders representative data primitives", () => {
    renderToString(<><Stepper steps={["一", "二", "三"]} current={1} /><DataTable caption="活动" rows={Array.from({ length: 40 }, (_, id) => ({ id }))} columns={[{ key: "id", title: "编号", render: row => row.id }]} /><TreeView label="目录" nodes={[{ id: "root", label: "课程", children: [{ id: "child", label: "知识点" }] }]} /></>);
  }).run();
});
