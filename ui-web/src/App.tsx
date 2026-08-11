import { lazy, Suspense, useEffect } from "react";
import { NavLink, Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./App.css";
import { healthQuery, homeQuery, sessionQuery } from "./app/queries";
import { RoleGuard } from "./app/RoleGuard";
import { Button, EmptyState, Feedback } from "./shared/ui";
import { measure } from "./shared/telemetry";

const KnowledgePage = lazy(() => import("./features/knowledge/KnowledgePage"));
const EditorPage = lazy(() => import("./features/editor/EditorPage"));
const DataSqlPage = lazy(() => import("./features/data-sql/DataSqlPage"));
const DesignSystemPage = lazy(() => import("./features/design-system/DesignSystemPage"));
const TeachingPage = lazy(() => import("./features/platform/PlatformPages").then(module => ({ default: module.TeachingPage })));
const CloudPage = lazy(() => import("./features/platform/PlatformPages").then(module => ({ default: module.CloudPage })));
const SettingsPage = lazy(() => import("./features/platform/PlatformPages").then(module => ({ default: module.SettingsPage })));
const MigrationPage = lazy(() => import("./features/platform/PlatformPages").then(module => ({ default: module.MigrationPage })));

const navigation = [
  { to: "/today", label: "今天", detail: "真实学习摘要" },
  { to: "/knowledge", label: "课程与知识", detail: "安全 Markdown" },
  { to: "/practice", label: "练习与实验", detail: "Monaco 编辑器" },
  { to: "/data", label: "数据与 SQL", detail: "Java 安全执行" },
  { to: "/teaching", label: "教学空间", detail: "题库与学情" },
  { to: "/cloud", label: "班级与云端", detail: "离线可恢复同步" },
  { to: "/settings", label: "设置", detail: "环境探测与向导" },
  { to: "/migration", label: "迁移状态", detail: "2.3 功能对齐" },
  { to: "/design-system", label: "界面基线", detail: "Alpha.2 原语" },
];

export default function App() {
  return <Routes><Route element={<Shell />}><Route index element={<Navigate to="/today" replace />} /><Route path="today" element={<TodayPage />} /><Route path="knowledge" element={<KnowledgePage />} /><Route path="practice" element={<EditorPage />} /><Route path="data" element={<DataSqlPage />} /><Route path="teaching" element={<RoleGuard allow={["TEACHER", "ADMINISTRATOR"]}><TeachingPage /></RoleGuard>} /><Route path="cloud" element={<CloudPage />} /><Route path="settings" element={<SettingsPage />} /><Route path="migration" element={<MigrationPage />} /><Route path="design-system" element={<DesignSystemPage />} /><Route path="*" element={<Navigate to="/today" replace />} /></Route></Routes>;
}

function Shell() {
  const health = useQuery(healthQuery);
  const session = useQuery(sessionQuery);
  const location = useLocation();
  useEffect(() => { const started = performance.now(); requestAnimationFrame(() => measure("route.render", started, { route: location.pathname })); }, [location.pathname]);
  const active = navigation.find(item => location.pathname.startsWith(item.to));
  return <main className="app-shell">
    <aside className="sidebar"><div className="brand"><span className="brand-mark">ST</span><div><strong>SQLTeacher</strong><small>3.0 Alpha.7</small></div></div><nav aria-label="工作区">{navigation.map(item => <NavLink key={item.to} to={item.to} className={({ isActive }) => isActive ? "nav-item active" : "nav-item"}><span>{item.label}</span><small>{item.detail}</small></NavLink>)}</nav><div className="sidebar-status"><span className={health.data ? "status-dot ready" : "status-dot"} /><div><strong>{health.data ? "Java 核心已连接" : "等待 Java 核心"}</strong><small>{session.data ? `${session.data.displayName} · ${session.data.roleLabel ?? session.data.role}` : "本地 IPC / 离线"}</small></div></div></aside>
    <section className="workspace"><header className="topbar"><div><p className="eyebrow">Alpha 功能对齐</p><h1>{active?.label ?? "SQLTeacher"}</h1></div><div className="alpha-badge">ALPHA.7</div></header><Suspense fallback={<PageSkeleton label="正在按需加载页面" />}><Outlet /></Suspense></section>
  </main>;
}

function TodayPage() {
  const queryClient = useQueryClient();
  const summary = useQuery(homeQuery);
  if (summary.isPending) return <PageSkeleton label="正在读取本地学习摘要" />;
  if (summary.isError) return <Feedback tone="error" title="无法读取学习摘要"><p>{summary.error.message}</p><Button variant="secondary" onClick={() => void summary.refetch()}>重试</Button></Feedback>;
  if (!summary.data) return <EmptyState title="尚未连接">请从 Tauri 桌面壳启动，浏览器预览不会模拟学习数据。</EmptyState>;
  const data = summary.data;
  return <div className="page-grid"><section className="hero-card"><div><p className="eyebrow">下一步学习</p><h2>{data.actions[0]?.title ?? "当前没有待办动作"}</h2><p>{data.actions[0]?.description ?? "完成一次活动后，确定性诊断会在这里给出下一步。"}</p></div><Button variant="secondary" onClick={() => void queryClient.invalidateQueries({ queryKey: homeQuery.queryKey })}>刷新诊断</Button></section><section className="metric-row" aria-label="学习摘要"><Metric label="知识点" value={data.knowledgePointCount} /><Metric label="需要练习" value={data.needsPracticeCount} accent /><Metric label="待办动作" value={data.actions.length} /><Metric label="诊断耗时" value={`${data.calculationMillis} ms`} /></section><section className="content-card action-list"><div className="section-heading"><div><p className="eyebrow">确定性队列</p><h2>真实本地建议</h2></div><span className="policy-chip">{data.policyVersion}</span></div>{data.actions.length === 0 ? <p className="muted">没有需要立即处理的学习动作。</p> : <ol>{data.actions.map(action => <li key={action.id}><span className="priority">{action.priority}</span><div><strong>{action.title}</strong><p>{action.description}</p></div><span className="action-type">{action.type}</span></li>)}</ol>}</section></div>;
}

function Metric({ label, value, accent = false }: { label: string; value: string | number; accent?: boolean }) { return <article className={accent ? "metric accent" : "metric"}><span>{label}</span><strong>{value}</strong></article>; }
function PageSkeleton({ label }: { label: string }) { return <section className="page-skeleton" aria-live="polite"><span className="spinner" />{label}</section>; }
