import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import "./App.css";

import { LocalAppError, localAppRequest } from "./shared/ipc";
import type { HealthResult, HomeSummary, KnowledgeSample } from "./shared/types";

const KnowledgePage = lazy(() => import("./features/knowledge/KnowledgePage"));
const EditorPage = lazy(() => import("./features/editor/EditorPage"));

type Workspace = "today" | "knowledge" | "editor";

const navigation: Array<{ id: Workspace; label: string; detail: string }> = [
  { id: "today", label: "今天", detail: "真实学习摘要" },
  { id: "knowledge", label: "课程与知识", detail: "安全 Markdown" },
  { id: "editor", label: "练习与实验", detail: "Monaco 尖峰" },
];

function App() {
  const [workspace, setWorkspace] = useState<Workspace>("today");
  const [health, setHealth] = useState<HealthResult>();
  const [summary, setSummary] = useState<HomeSummary>();
  const [knowledge, setKnowledge] = useState<KnowledgeSample>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<LocalAppError>();

  const title = useMemo(
    () => navigation.find((item) => item.id === workspace)?.label ?? "今天",
    [workspace],
  );

  useEffect(() => {
    void refreshHome();
  }, []);

  async function refreshHome() {
    setLoading(true);
    setError(undefined);
    try {
      const ready = await localAppRequest<HealthResult>("system.health");
      setHealth(ready);
      setSummary(await localAppRequest<HomeSummary>("home.summary"));
    } catch (cause) {
      setError(LocalAppError.from(cause));
    } finally {
      setLoading(false);
    }
  }

  async function openKnowledge() {
    setWorkspace("knowledge");
    if (knowledge) return;
    setError(undefined);
    try {
      setKnowledge(await localAppRequest<KnowledgeSample>("knowledge.sample"));
    } catch (cause) {
      setError(LocalAppError.from(cause));
    }
  }

  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">ST</span>
          <div>
            <strong>SQLTeacher</strong>
            <small>3.0 Alpha.1</small>
          </div>
        </div>
        <nav aria-label="工作区">
          {navigation.map((item) => (
            <button
              className={workspace === item.id ? "nav-item active" : "nav-item"}
              key={item.id}
              onClick={() => (item.id === "knowledge" ? void openKnowledge() : setWorkspace(item.id))}
              type="button"
            >
              <span>{item.label}</span>
              <small>{item.detail}</small>
            </button>
          ))}
        </nav>
        <div className="sidebar-status">
          <span className={health ? "status-dot ready" : "status-dot"} />
          <div>
            <strong>{health ? "Java 核心已连接" : "等待 Java 核心"}</strong>
            <small>{health ? `${health.javaVendor} ${health.javaVersion}` : "本地 IPC / 离线"}</small>
          </div>
        </div>
      </aside>

      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">架构与性能验证</p>
            <h1>{title}</h1>
          </div>
          <div className="alpha-badge">ALPHA.1</div>
        </header>

        {error && <ErrorNotice error={error} onRetry={() => void refreshHome()} />}
        {workspace === "today" && (
          <TodayPage loading={loading} summary={summary} onRefresh={() => void refreshHome()} />
        )}
        {workspace === "knowledge" && (
          <Suspense fallback={<PageSkeleton label="正在加载知识渲染器" />}>
            <KnowledgePage sample={knowledge} />
          </Suspense>
        )}
        {workspace === "editor" && (
          <Suspense fallback={<PageSkeleton label="正在按需加载 Monaco" />}>
            <EditorPage />
          </Suspense>
        )}
      </section>
    </main>
  );
}

function TodayPage({
  loading,
  summary,
  onRefresh,
}: {
  loading: boolean;
  summary?: HomeSummary;
  onRefresh: () => void;
}) {
  if (loading) return <PageSkeleton label="正在读取本地学习摘要" />;
  if (!summary) {
    return (
      <section className="empty-state">
        <p className="eyebrow">尚未连接</p>
        <h2>请从 Tauri 桌面壳启动</h2>
        <p>浏览器预览不会模拟学习数据。桌面壳会启动受控 Java Sidecar 并读取真实 SQLite 摘要。</p>
        <button className="primary-button" onClick={onRefresh} type="button">重新连接</button>
      </section>
    );
  }
  return (
    <div className="page-grid">
      <section className="hero-card">
        <div>
          <p className="eyebrow">下一步学习</p>
          <h2>{summary.actions[0]?.title ?? "当前没有待办动作"}</h2>
          <p>{summary.actions[0]?.description ?? "完成一次活动后，确定性诊断会在这里给出下一步。"}</p>
        </div>
        <button className="primary-button" onClick={onRefresh} type="button">刷新诊断</button>
      </section>

      <section className="metric-row" aria-label="学习摘要">
        <Metric label="知识点" value={summary.knowledgePointCount} />
        <Metric label="需要练习" value={summary.needsPracticeCount} accent />
        <Metric label="待办动作" value={summary.actions.length} />
        <Metric label="诊断耗时" value={`${summary.calculationMillis} ms`} />
      </section>

      <section className="content-card action-list">
        <div className="section-heading">
          <div>
            <p className="eyebrow">确定性队列</p>
            <h2>真实本地建议</h2>
          </div>
          <span className="policy-chip">{summary.policyVersion}</span>
        </div>
        {summary.actions.length === 0 ? (
          <p className="muted">没有需要立即处理的学习动作。</p>
        ) : (
          <ol>
            {summary.actions.map((action) => (
              <li key={action.id}>
                <span className="priority">{action.priority}</span>
                <div><strong>{action.title}</strong><p>{action.description}</p></div>
                <span className="action-type">{action.type}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

function Metric({ label, value, accent = false }: { label: string; value: string | number; accent?: boolean }) {
  return <article className={accent ? "metric accent" : "metric"}><span>{label}</span><strong>{value}</strong></article>;
}

function ErrorNotice({ error, onRetry }: { error: LocalAppError; onRetry: () => void }) {
  return (
    <section className="error-notice" role="alert">
      <div><strong>{error.code}</strong><p>{error.message}</p></div>
      {error.retryable && <button onClick={onRetry} type="button">重试</button>}
    </section>
  );
}

function PageSkeleton({ label }: { label: string }) {
  return <section className="page-skeleton" aria-live="polite"><span className="spinner" />{label}</section>;
}

export default App;
