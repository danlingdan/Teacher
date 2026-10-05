import { queryOptions } from "@tanstack/react-query";
import { localAppRequest } from "../shared/ipc";
import type {
  AiEngineStatus,
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
// v3.10.0 HAJ-3：AI 引擎状态（当前生效通道/选定模型/Ollama 可达性），顶栏弹层与失败引导消费。
export const aiEngineStatusQuery = queryOptions({
  queryKey: ["ai", "engine-status"],
  queryFn: () => localAppRequest<AiEngineStatus>("ai.engine.status"),
  staleTime: 10_000,
});
