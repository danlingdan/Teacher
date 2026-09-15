import { queryOptions } from "@tanstack/react-query";
import { localAppRequest } from "../shared/ipc";
import type {
  ConnectionDialectOption,
  ConnectionSummary,
  CourseWorkspace,
  HealthResult,
  HomeSummary,
  SessionResult,
  SettingsPreferences,
} from "../shared/types";

export const healthQuery = queryOptions({
  queryKey: ["local-app", "health"],
  queryFn: () => localAppRequest<HealthResult>("system.health"),
  staleTime: 30_000,
});
export const sessionQuery = queryOptions({
  queryKey: ["session", "current"],
  queryFn: () => localAppRequest<SessionResult>("session.current"),
  staleTime: 5 * 60_000,
});
export const homeQuery = queryOptions({
  queryKey: ["learning", "home"],
  queryFn: () => localAppRequest<HomeSummary>("home.summary"),
  staleTime: 15_000,
});
export const courseWorkspaceQuery = queryOptions({
  queryKey: ["course", "workspace"],
  queryFn: () => localAppRequest<CourseWorkspace>("course.workspace"),
  staleTime: 30_000,
});
export const settingsPreferencesQuery = queryOptions({
  queryKey: ["settings", "preferences"],
  queryFn: () => localAppRequest<SettingsPreferences>("settings.preferences"),
  staleTime: 30_000,
});
// v3.4.4 CTB-3：连接查询提升为共享 query，顶栏 TopbarConnection 与数据页同源消费，
// 「当前连接」只认后端 selected 标记，消灭页面本地与全局选择的双轨。
export const connectionsQuery = queryOptions({
  queryKey: ["data", "connections"],
  queryFn: () => localAppRequest<{ items: ConnectionSummary[] }>("data.connections"),
});
export const connectionDialectsQuery = queryOptions({
  queryKey: ["data", "connection-dialects"],
  queryFn: () => localAppRequest<{ items: ConnectionDialectOption[] }>("data.connection.dialects"),
  staleTime: Infinity,
});
