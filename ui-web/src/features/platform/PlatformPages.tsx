import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../shared/ipc";
import type { CloudWorkspace, MigrationStatus, SettingsWorkspace, TeachingWorkspace } from "../../shared/types";
import { Button, DataTable, Feedback, FormField } from "../../shared/ui";

const teachingKey = ["teaching", "workspace"] as const;
const cloudKey = ["cloud", "workspace"] as const;
const settingsKey = ["settings", "workspace"] as const;

export function TeachingPage() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: teachingKey, queryFn: () => localAppRequest<TeachingWorkspace>("teaching.workspace"), staleTime: 15_000 });
  const toggle = useMutation({ mutationFn: (item: { id: string; enabled: boolean; version: number }) => localAppRequest("teaching.exercise.toggle", { exerciseId: item.id, enabled: !item.enabled, expectedVersion: item.version }), onSuccess: () => void client.invalidateQueries({ queryKey: teachingKey }) });
  if (query.isPending) return <Loading label="正在读取本地题库与学情" />;
  if (query.isError) return <Feedback tone="error" title="教学工作台不可用"><p>{query.error.message}</p></Feedback>;
  const data = query.data;
  return <div className="platform-workspace page-grid">
    <section className="hero-card"><div><p className="eyebrow">Java 权限边界</p><h2>教学工作台</h2><p>题库、学习进度与发布权限均来自 Java 服务；服务器仍会再次验证云端写操作。</p></div><span className="policy-chip">{data.role}</span></section>
    <section className="metric-row"><Metric label="题目" value={data.exercises.length} /><Metric label="练习会话" value={data.progressOverview.sessions} /><Metric label="提交" value={data.progressOverview.submissions} /><Metric label="已通过" value={data.progressOverview.passedSubmissions} /></section>
    <section className="content-card"><div className="section-heading"><div><p className="eyebrow">Question bank</p><h2>本地题库</h2></div><span className="policy-chip">{data.canPublish ? "可发布" : "只读"}</span></div>
      <DataTable caption="教师题库" rows={data.exercises} columns={[
        { key: "title", title: "题目", render: row => row.title },
        { key: "knowledge", title: "知识点", render: row => row.knowledgePoint },
        { key: "difficulty", title: "难度", render: row => row.difficulty },
        { key: "state", title: "状态", render: row => <Button variant="secondary" busy={toggle.isPending} onClick={() => toggle.mutate(row)}>{row.enabled ? "停用" : "启用"}</Button> },
      ]} />
    </section>
    <section className="content-card"><div className="section-heading"><div><p className="eyebrow">Learning progress</p><h2>学习进度</h2></div></div>
      {data.progressItems.length === 0 ? <p className="muted">尚无练习记录。</p> : <DataTable caption="练习学情" rows={data.progressItems} columns={[
        { key: "title", title: "题目", render: row => row.title },
        { key: "attempts", title: "尝试", render: row => row.attempts },
        { key: "failed", title: "失败提交", render: row => row.failedSubmissions },
        { key: "passed", title: "结果", render: row => row.passed ? "通过" : "练习中" },
      ]} />}
    </section>
  </div>;
}

export function CloudPage() {
  const client = useQueryClient();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [className, setClassName] = useState("");
  const query = useQuery({ queryKey: cloudKey, queryFn: () => localAppRequest<CloudWorkspace>("cloud.workspace"), staleTime: 15_000 });
  const refresh = useMutation({ mutationFn: () => localAppRequest<CloudWorkspace>("cloud.workspace", { refreshRemote: true }), onSuccess: data => client.setQueryData(cloudKey, data) });
  const sync = useMutation({ mutationFn: () => localAppRequest<{ uploaded: number; downloaded: number }>("cloud.sync"), onSuccess: () => void client.invalidateQueries({ queryKey: cloudKey }) });
  const login = useMutation({ mutationFn: () => localAppRequest("account.login", { email, password }), onSuccess: async () => { await client.invalidateQueries({ queryKey: ["session", "current"] }); await client.invalidateQueries({ queryKey: cloudKey }); }, onSettled: () => setPassword("") });
  const logout = useMutation({ mutationFn: () => localAppRequest("account.logout"), onSuccess: async () => { await client.invalidateQueries({ queryKey: ["session", "current"] }); await client.invalidateQueries({ queryKey: cloudKey }); } });
  const createClass = useMutation({ mutationFn: () => localAppRequest("cloud.class.create", { name: className }), onSuccess: async () => { setClassName(""); const refreshed = await localAppRequest<CloudWorkspace>("cloud.workspace", { refreshRemote: true }); client.setQueryData(cloudKey, refreshed); } });
  if (query.isPending) return <Loading label="正在读取账号与同步队列" />;
  if (query.isError) return <Feedback tone="error" title="云端状态不可用"><p>{query.error.message}</p></Feedback>;
  const data = query.data;
  if (!data.signedIn) return <section className="content-card account-login"><p className="eyebrow">Account</p><h2>登录 SQLTeacher Cloud</h2><p className="muted">密码只传给 Java 登录服务，不进入 Web Storage、日志或持久化前端状态。离线学习无需登录。</p><div className="settings-grid"><FormField label="邮箱">{ids => <input {...ids} type="email" autoComplete="username" value={email} onChange={event => setEmail(event.target.value)} />}</FormField><FormField label="密码">{ids => <input {...ids} type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} />}</FormField></div>{login.isError && <Feedback tone="error" title="登录失败"><p>{login.error.message}</p></Feedback>}<Button busy={login.isPending} disabled={!email || !password} onClick={() => login.mutate()}>登录</Button></section>;
  return <div className="platform-workspace page-grid">
    <section className="hero-card"><div><p className="eyebrow">Account & sync</p><h2>{data.displayName ?? "云端账号"}</h2><p>{data.message}</p></div><div className="button-row"><Button variant="secondary" busy={refresh.isPending} onClick={() => refresh.mutate()}>刷新班级</Button><Button busy={sync.isPending} onClick={() => sync.mutate()}>立即同步</Button><Button variant="secondary" busy={logout.isPending} onClick={() => logout.mutate()}>退出登录</Button></div></section>
    {data.state === "DEGRADED" && <Feedback tone="warning" title="云端连接降级"><p>本地学习不受影响，可稍后手动重试。</p></Feedback>}
    <section className="metric-row"><Metric label="班级" value={data.classes.length} /><Metric label="同步状态" value={data.sync.state} /><Metric label="待同步" value={data.sync.pending} /><Metric label="重试次数" value={data.sync.attempt} /></section>
    <section className="content-card"><div className="section-heading"><div><h2>可见班级</h2></div>{(data.role === "TEACHER" || data.role === "ADMINISTRATOR") && <div className="button-row"><input aria-label="新班级名称" value={className} onChange={event => setClassName(event.target.value)} placeholder="新班级名称" /><Button busy={createClass.isPending} disabled={!className.trim()} onClick={() => createClass.mutate()}>创建班级</Button></div>}</div>{data.classes.length === 0 ? <p className="muted">尚未从云端刷新班级。</p> : <ul className="plain-list">{data.classes.map(item => <li key={item.id}><strong>{item.name}</strong><span>{item.members.length} 名成员</span></li>)}</ul>}</section>
  </div>;
}

type SettingsDraft = Pick<SettingsWorkspace["general"], "automaticUpdateChecks" | "reducedMotion" | "highContrast" | "language" | "nativeNotificationsEnabled" | "meteredNetwork"> & { developerMode: boolean };

export function SettingsPage() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: settingsKey, queryFn: () => localAppRequest<SettingsWorkspace>("settings.workspace"), staleTime: 15_000 });
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  useEffect(() => { if (query.data) setDraft({ ...query.data.general, developerMode: query.data.developerMode }); }, [query.data]);
  const save = useMutation({ mutationFn: (value: SettingsDraft) => localAppRequest("settings.update", value), onSuccess: () => void client.invalidateQueries({ queryKey: settingsKey }) });
  if (query.isPending || !draft) return <Loading label="正在探测本机环境" />;
  if (query.isError) return <Feedback tone="error" title="设置不可用"><p>{query.error.message}</p></Feedback>;
  const data = query.data;
  const toggle = (key: keyof SettingsDraft) => setDraft(value => value ? { ...value, [key]: !value[key] } : value);
  return <div className="platform-workspace page-grid">
    <section className="hero-card"><div><p className="eyebrow">Guided settings</p><h2>常用设置</h2><p>保存到现有 Java 配置服务，不使用 Web Storage，也不向 WebView 暴露密码、令牌或连接字符串。</p></div><Button busy={save.isPending} onClick={() => save.mutate(draft)}>保存设置</Button></section>
    {save.isSuccess && <Feedback tone="success" title="设置已保存"><p>Java 核心已应用新的本机设置。</p></Feedback>}
    <section className="content-card settings-grid">
      <FormField label="界面语言">{ids => <select {...ids} value={draft.language} onChange={event => setDraft({ ...draft, language: event.target.value })}><option value="zh">简体中文</option><option value="en">English</option></select>}</FormField>
      <Toggle label="自动检查更新" checked={draft.automaticUpdateChecks} onChange={() => toggle("automaticUpdateChecks")} />
      <Toggle label="减少动态效果" checked={draft.reducedMotion} onChange={() => toggle("reducedMotion")} />
      <Toggle label="高对比度" checked={draft.highContrast} onChange={() => toggle("highContrast")} />
      <Toggle label="原生通知" checked={draft.nativeNotificationsEnabled} onChange={() => toggle("nativeNotificationsEnabled")} />
      <Toggle label="按流量计费网络" checked={draft.meteredNetwork} onChange={() => toggle("meteredNetwork")} />
      <Toggle label="SQL 开发者模式" checked={draft.developerMode} onChange={() => toggle("developerMode")} hint="只减少常规确认；禁用语句、只读边界、高风险确认和审计仍由 Java 强制。" />
    </section>
    <details className="content-card"><summary><strong>高级环境与存储</strong></summary><p className="muted">探测顺序：{data.manualPathPolicy}</p><p>连接状态：{data.connectivity}</p><p>可用空间：{formatBytes(data.storage.usableBytes)}</p><div className="component-grid">{data.components.map(item => <article key={item.id} className="subtle-card"><strong>{item.displayName}</strong><span>{item.state}</span><small>{item.detail || item.source}</small></article>)}</div></details>
  </div>;
}

export function MigrationPage() {
  const query = useQuery({ queryKey: ["migration", "status"], queryFn: () => localAppRequest<MigrationStatus>("migration.status"), staleTime: Infinity });
  if (query.isPending) return <Loading label="正在核对功能对齐状态" />;
  if (query.isError) return <Feedback tone="error" title="无法读取迁移状态"><p>{query.error.message}</p></Feedback>;
  const data = query.data;
  return <div className="platform-workspace page-grid"><section className="hero-card"><div><p className="eyebrow">Alpha.7 parity</p><h2>功能对齐完成</h2><p>新工作区已覆盖 Alpha 计划；生产默认入口未切换，JavaFX 回退仍保留。</p></div><span className="alpha-badge">{data.stage}</span></section><section className="content-card"><ul className="parity-list">{data.features.map(item => <li key={item.id}><span className="status-dot ready" /><div><strong>{item.title}</strong><small>{item.id}</small></div><span className="policy-chip">{item.status}</span></li>)}</ul></section><Feedback tone="info" title="迁移边界"><p>离线核心：{data.offlineCore ? "可用" : "不可用"}；JavaFX 回退：{data.javaFxFallback ? "保留" : "关闭"}；现有 schema 语义：{data.schemaSemanticsChanged ? "已变化" : "未变化"}。</p></Feedback></div>;
}

function Toggle({ label, checked, onChange, hint }: { label: string; checked: boolean; onChange: () => void; hint?: string }) { return <label className="setting-toggle"><input type="checkbox" checked={checked} onChange={onChange} /><span><strong>{label}</strong>{hint && <small>{hint}</small>}</span></label>; }
function Metric({ label, value }: { label: string; value: string | number }) { return <article className="metric"><span>{label}</span><strong>{value}</strong></article>; }
function Loading({ label }: { label: string }) { return <section className="page-skeleton" aria-live="polite"><span className="spinner" />{label}</section>; }
function formatBytes(value: number) { return `${(value / 1024 / 1024 / 1024).toFixed(1)} GiB`; }
