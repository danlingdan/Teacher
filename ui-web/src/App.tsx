import { lazy, Suspense, useEffect, useMemo, useState } from "react";
import {
  NavLink,
  Navigate,
  Outlet,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import "./App.css";
import { healthQuery, homeQuery, sessionQuery } from "./app/queries";
import { ErrorBoundary } from "./app/ErrorBoundary";
import { RoleGuard } from "./app/RoleGuard";
import { Button, EmptyState, Feedback } from "./shared/ui";
import { measure } from "./shared/telemetry";
import { localAppRequest } from "./shared/ipc";
import type {
  CloudNotification,
  LearningActionSummary,
  SettingsPreferences,
} from "./shared/types";
import { deliverNativeNotifications } from "./shared/nativeNotifications";
import { installEnglishUi } from "./shared/uiI18n";

const KnowledgePage = lazy(() => import("./features/knowledge/KnowledgePage"));
const EditorPage = lazy(() => import("./features/editor/EditorPage"));
const DataSqlPage = lazy(() => import("./features/data-sql/DataSqlPage"));
const TeachingPage = lazy(() =>
  import("./features/platform/PlatformPages").then((module) => ({
    default: module.TeachingPage,
  })),
);
const CloudPage = lazy(() =>
  import("./features/platform/PlatformPages").then((module) => ({
    default: module.CloudPage,
  })),
);
const SettingsPage = lazy(() =>
  import("./features/platform/PlatformPages").then((module) => ({
    default: module.SettingsPage,
  })),
);
const AuthPage = lazy(() => import("./features/auth/AuthPage"));

type NavigationItem = {
  to: string;
  label: string;
  detail: string;
  icon: string;
  roles?: string[];
};
const navigation: NavigationItem[] = [
  { to: "/today", label: "今天", detail: "学习概览", icon: "⌂" },
  { to: "/knowledge", label: "课程与知识", detail: "阅读与检索", icon: "◇" },
  { to: "/practice", label: "练习与实验", detail: "编码与活动", icon: "⌘" },
  { to: "/data", label: "数据与 SQL", detail: "连接与查询", icon: "▦" },
  {
    to: "/teaching",
    label: "教学空间",
    detail: "题库与学情",
    icon: "◎",
    roles: ["TEACHER", "ADMINISTRATOR"],
  },
  { to: "/cloud", label: "班级与云端", detail: "账号与同步", icon: "☁" },
  { to: "/settings", label: "设置", detail: "偏好与维护", icon: "⚙" },
];

export default function App() {
  return (
    <>
      <AppEffects />
      <Routes>
        <Route
          path="login"
          element={
            <Suspense fallback={<PageSkeleton label="正在加载账号页面" />}>
              <AuthPage />
            </Suspense>
          }
        />
        <Route element={<Shell />}>
          <Route index element={<Navigate to="/today" replace />} />
          <Route path="today" element={<ErrorBoundary><TodayPage /></ErrorBoundary>} />
          <Route path="knowledge" element={<ErrorBoundary><KnowledgePage /></ErrorBoundary>} />
          <Route path="practice" element={<ErrorBoundary><EditorPage /></ErrorBoundary>} />
          <Route path="data" element={<ErrorBoundary><DataSqlPage /></ErrorBoundary>} />
          <Route
            path="teaching"
            element={
              <RoleGuard allow={["TEACHER", "ADMINISTRATOR"]}>
                <ErrorBoundary>
                  <TeachingPage />
                </ErrorBoundary>
              </RoleGuard>
            }
          />
          <Route path="cloud" element={<ErrorBoundary><CloudPage /></ErrorBoundary>} />
          <Route path="settings" element={<ErrorBoundary><SettingsPage /></ErrorBoundary>} />
          <Route path="*" element={<Navigate to="/today" replace />} />
        </Route>
      </Routes>
    </>
  );
}

function AppEffects() {
  const appearance = useQuery({
    queryKey: ["settings", "preferences"],
    queryFn: () => localAppRequest<SettingsPreferences>("settings.preferences"),
    staleTime: 30_000,
  });
  useEffect(() => {
    const general = appearance.data?.general;
    if (!general) return;
    const media = window.matchMedia("(prefers-color-scheme: dark)");
    const apply = () => {
      const dark =
        general.theme === "dark" ||
        (general.theme === "system" && media.matches);
      document.documentElement.classList.toggle("theme-dark", dark);
      document.documentElement.classList.toggle(
        "high-contrast",
        general.highContrast,
      );
      document.documentElement.classList.toggle(
        "reduced-motion",
        general.reducedMotion,
      );
      document.documentElement.classList.toggle(
        "density-compact",
        general.density === "compact",
      );
      document.documentElement.classList.remove(
        "font-modern",
        "font-system",
        "font-classic",
      );
      document.documentElement.classList.add(`font-${general.font}`);
      document.documentElement.lang =
        general.language === "en" ? "en" : "zh-CN";
      document.documentElement.style.colorScheme = dark ? "dark" : "light";
    };
    apply();
    media.addEventListener("change", apply);
    return () => media.removeEventListener("change", apply);
  }, [appearance.data]);
  useEffect(
    () =>
      appearance.data?.general.language === "en"
        ? installEnglishUi()
        : undefined,
    [appearance.data?.general.language],
  );
  useEffect(() => {
    const data = appearance.data;
    if (!data?.general.nativeNotificationsEnabled) return;
    void deliverNativeNotifications(data.notifications).then((delivered) => {
      if (delivered > 0) void localAppRequest("settings.notifications.read");
    });
  }, [appearance.data]);
  return null;
}

function Shell() {
  const health = useQuery(healthQuery);
  const session = useQuery(sessionQuery);
  const location = useLocation();
  const navigate = useNavigate();
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [paletteQuery, setPaletteQuery] = useState("");
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const preferences = useQuery({
    queryKey: ["settings", "preferences"],
    queryFn: () => localAppRequest<SettingsPreferences>("settings.preferences"),
    staleTime: 30_000,
  });
  const cloudNotifications = useQuery({
    queryKey: ["cloud", "notifications"],
    queryFn: () =>
      localAppRequest<{
        items: CloudNotification[];
        unread: number;
        cached: boolean;
      }>("cloud.notifications"),
    enabled: Boolean(session.data?.authenticated),
    staleTime: 30_000,
  });
  const queryClient = useQueryClient();
  const localNotifications = preferences.data?.notifications ?? [];
  const notifications = [
    ...(cloudNotifications.data?.items ?? []).map((item) => ({
      id: `cloud:${item.id}`,
      cloudId: item.id,
      title: item.title,
      message: item.message,
      createdAt: item.createdAt,
      read: Boolean(item.readAt),
      target: "/cloud",
    })),
    ...localNotifications.map((item) => ({ ...item, cloudId: "" })),
  ].sort((left, right) => right.createdAt.localeCompare(left.createdAt));
  const unreadCount = notifications.filter((item) => !item.read).length;
  const refreshCloudNotifications = useMutation({
    mutationFn: () =>
      localAppRequest<{
        items: CloudNotification[];
        unread: number;
        cached: boolean;
      }>("cloud.notifications", { refreshRemote: true }),
    onSuccess: (data) =>
      queryClient.setQueryData(["cloud", "notifications"], data),
  });
  const markNotificationsRead = useMutation({
    mutationFn: async () => {
      await localAppRequest("settings.notifications.read");
      await Promise.all(
        notifications
          .filter((item) => !item.read && item.cloudId)
          .map((item) =>
            localAppRequest("cloud.notification.read", {
              notificationId: item.cloudId,
            }),
          ),
      );
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: ["settings", "preferences"],
      });
      await queryClient.invalidateQueries({
        queryKey: ["cloud", "notifications"],
      });
    },
  });
  const visibleNavigation = useMemo(
    () =>
      navigation.filter(
        (item) =>
          !item.roles ||
          (session.data && item.roles.includes(session.data.role)),
      ),
    [session.data],
  );
  useEffect(() => {
    const started = performance.now();
    requestAnimationFrame(() =>
      measure("route.render", started, { route: location.pathname }),
    );
  }, [location.pathname]);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setPaletteOpen((value) => !value);
      }
      if ((event.ctrlKey || event.metaKey) && event.key === ",") {
        event.preventDefault();
        navigate("/settings");
      }
      if ((event.ctrlKey || event.metaKey) && /^[1-7]$/.test(event.key)) {
        const item = visibleNavigation[Number(event.key) - 1];
        if (item) {
          event.preventDefault();
          navigate(item.to);
        }
      }
      if (event.key === "Escape") setPaletteOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navigate, visibleNavigation]);
  const active = navigation.find((item) =>
    location.pathname.startsWith(item.to),
  );
  return (
    <main className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">S</span>
          <div>
            <strong>SQLTeacher</strong>
            <small>Learning Studio</small>
          </div>
        </div>
        <p className="nav-caption">工作区</p>
        <nav aria-label="工作区">
          {visibleNavigation.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                isActive ? "nav-item active" : "nav-item"
              }
            >
              <span className="nav-icon" aria-hidden="true">
                {item.icon}
              </span>
              <span className="nav-copy">
                <strong>{item.label}</strong>
                <small>{item.detail}</small>
              </span>
            </NavLink>
          ))}
        </nav>
        <NavLink
          className="sidebar-account"
          to={
            session.data?.authenticated
              ? "/cloud"
              : `/login?returnTo=${encodeURIComponent(location.pathname)}`
          }
        >
          <span className="account-avatar">
            {session.data?.authenticated
              ? session.data.displayName.slice(0, 1).toUpperCase()
              : "↗"}
          </span>
          <span>
            <strong>
              {session.data?.authenticated
                ? session.data.displayName
                : "登录或创建账号"}
            </strong>
            <small>
              {session.data?.authenticated
                ? (session.data.roleLabel ?? session.data.role)
                : "同步学习进度"}
            </small>
          </span>
        </NavLink>
        <div className="sidebar-status">
          <span className={health.data ? "status-dot ready" : "status-dot"} />
          <div>
            <strong>
              {health.data ? "本地核心已就绪" : "正在连接本地核心"}
            </strong>
            <small>离线可用</small>
          </div>
        </div>
      </aside>
      <section className="workspace">
        <header className="topbar">
          <div>
            <p className="eyebrow">{active?.detail ?? "本地学习平台"}</p>
            <h1>{active?.label ?? "SQLTeacher"}</h1>
          </div>
          <div className="topbar-actions">
            <div className="notification-anchor">
              <button
                type="button"
                className="notification-trigger"
                aria-label={`通知${unreadCount ? `，${unreadCount} 条未读` : ""}`}
                aria-expanded={notificationsOpen}
                onClick={() => {
                  const next = !notificationsOpen;
                  setNotificationsOpen(next);
                  if (next && session.data?.authenticated)
                    refreshCloudNotifications.mutate();
                }}
              >
                <span aria-hidden="true">●</span>
                <span>通知</span>
                {unreadCount > 0 && (
                  <b>{unreadCount > 99 ? "99+" : unreadCount}</b>
                )}
              </button>
              {notificationsOpen && (
                <section
                  className="notification-popover"
                  role="dialog"
                  aria-label="通知中心"
                >
                  <header>
                    <div>
                      <strong>通知中心</strong>
                      <small>
                        {refreshCloudNotifications.isPending
                          ? "正在同步"
                          : unreadCount
                            ? `${unreadCount} 条未读`
                            : "全部已读"}
                      </small>
                    </div>
                    {unreadCount > 0 && (
                      <Button
                        variant="secondary"
                        busy={markNotificationsRead.isPending}
                        onClick={() => markNotificationsRead.mutate()}
                      >
                        全部已读
                      </Button>
                    )}
                  </header>
                  {notifications.length === 0 ? (
                    <p className="muted">
                      暂无通知
                    </p>
                  ) : (
                    <ul>
                      {notifications.slice(0, 8).map((item) => (
                        <li className={item.read ? "" : "unread"} key={item.id}>
                          <strong>{item.title}</strong>
                          <p>{item.message}</p>
                          <small>{formatUiDate(item.createdAt)}</small>
                          {item.target && (
                            <Button
                              variant="secondary"
                              onClick={() => {
                                navigate(item.target);
                                setNotificationsOpen(false);
                              }}
                            >
                              查看
                            </Button>
                          )}
                        </li>
                      ))}
                    </ul>
                  )}
                  <Button
                    variant="secondary"
                    onClick={() => {
                      navigate("/settings");
                      setNotificationsOpen(false);
                    }}
                  >
                    通知设置
                  </Button>
                </section>
              )}
            </div>
            <button
              type="button"
              className="palette-trigger"
              onClick={() => setPaletteOpen(true)}
            >
              <span>搜索与跳转</span>
              <kbd>Ctrl K</kbd>
            </button>
            <div className="stage-badge">3.0</div>
          </div>
        </header>
        <div className="workspace-content">
          <Suspense fallback={<PageSkeleton label="正在按需加载页面" />}>
            <Outlet />
          </Suspense>
        </div>
      </section>
      <CommandPalette
        items={visibleNavigation}
        open={paletteOpen}
        query={paletteQuery}
        onQuery={setPaletteQuery}
        onClose={() => setPaletteOpen(false)}
        onNavigate={(path) => {
          navigate(path);
          setPaletteOpen(false);
          setPaletteQuery("");
        }}
      />
    </main>
  );
}

function CommandPalette({
  items,
  open,
  query,
  onQuery,
  onClose,
  onNavigate,
}: {
  items: NavigationItem[];
  open: boolean;
  query: string;
  onQuery: (value: string) => void;
  onClose: () => void;
  onNavigate: (path: string) => void;
}) {
  const matches = useMemo(
    () =>
      items.filter((item) =>
        `${item.label} ${item.detail} ${item.to}`
          .toLowerCase()
          .includes(query.trim().toLowerCase()),
      ),
    [items, query],
  );
  if (!open) return null;
  return (
    <div
      className="palette-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section
        className="command-palette"
        role="dialog"
        aria-modal="true"
        aria-label="快速导航"
      >
        <input
          autoFocus
          aria-label="搜索页面"
          placeholder="输入页面名称或功能"
          value={query}
          onChange={(event) => onQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && matches[0]) onNavigate(matches[0].to);
          }}
        />
        <div>
          {matches.map((item, index) => (
            <button
              type="button"
              key={item.to}
              onClick={() => onNavigate(item.to)}
            >
              <span>{item.label}</span>
              <small>{item.detail}</small>
              <kbd>Ctrl {index + 1}</kbd>
            </button>
          ))}
        </div>
        {matches.length === 0 && <p className="muted">没有匹配页面</p>}
      </section>
    </div>
  );
}

function TodayPage() {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const summary = useQuery(homeQuery);
  const dismiss = useMutation({
    mutationFn: (actionId: string) =>
      localAppRequest("home.action.dismiss", { actionId }),
    onSuccess: () =>
      void queryClient.invalidateQueries({ queryKey: homeQuery.queryKey }),
  });
  function continueAction(action: LearningActionSummary) {
    if (action.type === "REVIEW_KNOWLEDGE" || action.knowledgePoint)
      navigate(`/knowledge?query=${encodeURIComponent(action.knowledgePoint)}`);
    else if (action.type === "RETRY_ACTIVITY")
      navigate(`/practice?activity=${encodeURIComponent(action.exerciseId)}`);
    else if (action.exerciseId)
      navigate(`/practice?exercise=${encodeURIComponent(action.exerciseId)}`);
    else if (
      action.type === "COMPLETE_ASSIGNMENT" ||
      action.type === "REVIEW_FEEDBACK"
    )
      navigate("/cloud");
    else navigate("/practice");
  }
  if (summary.isPending) return <PageSkeleton label="正在读取本地学习摘要" />;
  if (summary.isError)
    return (
      <Feedback tone="error" title="无法读取学习摘要">
        <p>{summary.error.message}</p>
        <Button variant="secondary" onClick={() => void summary.refetch()}>
          重试
        </Button>
      </Feedback>
    );
  if (!summary.data)
    return (
      <EmptyState title="尚未连接">
        请从 SQLTeacher 桌面应用打开。
      </EmptyState>
    );
  const data = summary.data;
  return (
    <div className="page-grid">
      <section className="hero-card">
        <div>
          <p className="eyebrow">下一步学习</p>
          <h2>{data.actions[0]?.title ?? "当前没有待办动作"}</h2>
          {data.actions[0]?.description && (
            <p>{data.actions[0].description}</p>
          )}
        </div>
        <div className="button-row">
          {data.actions[0] && (
            <Button onClick={() => continueAction(data.actions[0])}>
              继续学习
            </Button>
          )}
          <Button
            variant="secondary"
            onClick={() =>
              void queryClient.invalidateQueries({
                queryKey: homeQuery.queryKey,
              })
            }
          >
            刷新诊断
          </Button>
        </div>
      </section>
      <section className="metric-row" aria-label="学习摘要">
        <Metric label="知识点" value={data.knowledgePointCount} />
        <Metric label="需要练习" value={data.needsPracticeCount} accent />
        <Metric label="待办动作" value={data.actions.length} />
        <Metric label="诊断耗时" value={`${data.calculationMillis} ms`} />
      </section>
      <section className="content-card action-list">
        <div className="section-heading">
          <div>
            <p className="eyebrow">学习队列</p>
            <h2>学习建议</h2>
          </div>
          <span className="policy-chip" title={data.policyVersion}>
            确定性策略
          </span>
        </div>
        {data.actions.length === 0 ? (
          <p className="muted">暂无待办动作</p>
        ) : (
          <ol>
            {data.actions.map((action) => (
              <li key={action.id}>
                <span className="priority">{action.priority}</span>
                <div>
                  <strong>{action.title}</strong>
                  <p>{action.description}</p>
                  <div className="button-row">
                    <Button
                      variant="secondary"
                      onClick={() => continueAction(action)}
                    >
                      继续
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={dismiss.isPending}
                      onClick={() => dismiss.mutate(action.id)}
                    >
                      暂不处理
                    </Button>
                  </div>
                </div>
                <span className="action-type">{action.type}</span>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

function Metric({
  label,
  value,
  accent = false,
}: {
  label: string;
  value: string | number;
  accent?: boolean;
}) {
  return (
    <article className={accent ? "metric accent" : "metric"}>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}
function formatUiDate(value: string) {
  const timestamp = Date.parse(value);
  return !Number.isFinite(timestamp) || timestamp < Date.UTC(2000, 0, 1)
    ? "时间未知"
    : new Date(timestamp).toLocaleString("zh-CN", { hour12: false });
}
function PageSkeleton({ label }: { label: string }) {
  return (
    <section className="page-skeleton" aria-live="polite">
      <span className="spinner" />
      {label}
    </section>
  );
}
