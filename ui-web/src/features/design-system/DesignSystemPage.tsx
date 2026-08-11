import { useState } from "react";
import { Button, DataTable, Dialog, EmptyState, Feedback, FormField, LiveRegion, Stepper, TreeView } from "../../shared/ui";

export default function DesignSystemPage() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [notice, setNotice] = useState("");
  return <div className="design-grid">
    <section className="content-card showcase"><p className="eyebrow">Actions & forms</p><h2>按钮与表单</h2><div className="showcase-row"><Button onClick={() => setNotice("操作已保存")}>主要操作</Button><Button variant="secondary">次要操作</Button><Button variant="danger">危险操作</Button></div><FormField label="课程名称" hint="用于学习空间导航" error="示例：名称不能为空">{ids => <input {...ids} defaultValue="" />}</FormField><LiveRegion>{notice}</LiveRegion></section>
    <section className="content-card showcase"><p className="eyebrow">Progress & feedback</p><h2>步骤与反馈</h2><Stepper steps={["选择", "配置", "完成"]} current={1} /><Feedback tone="success" title="契约已验证">Java 与 TypeScript 使用同一 IPC 版本。</Feedback></section>
    <section className="content-card showcase"><p className="eyebrow">Data</p><h2>表格与树</h2><DataTable caption="学习活动" rows={[{ name: "SQL 查询", state: "可用" }]} columns={[{ key: "name", title: "活动", render: row => row.name }, { key: "state", title: "状态", render: row => row.state }]} /><TreeView label="课程目录" nodes={[{ id: "c", label: "数据库基础", children: [{ id: "k", label: "SELECT 查询" }] }]} /></section>
    <section className="content-card showcase"><p className="eyebrow">States & overlays</p><h2>空状态与对话框</h2><EmptyState title="暂无自定义课程" action={<Button variant="secondary" onClick={() => setDialogOpen(true)}>新建课程</Button>}>导入课程后会显示在这里。</EmptyState></section>
    <Dialog open={dialogOpen} title="新建课程" onClose={() => setDialogOpen(false)}><p>对话框支持 Escape、遮罩关闭与初始焦点。</p><Button onClick={() => setDialogOpen(false)}>知道了</Button></Dialog>
  </div>;
}
