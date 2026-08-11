import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { log, measure } from "./telemetry";

export const CONTRACT_VERSION = "3.0-v1";

export type LocalAppMethod =
  | "account.login"
  | "account.register"
  | "account.logout"
  | "account.password.change"
  | "account.password.reset.request"
  | "account.sessions"
  | "account.session.revoke"
  | "account.export.request"
  | "account.export.get"
  | "account.deletion.request"
  | "account.deletion.cancel"
  | "account.deletion.status"
  | "ai.knowledge.ask"
  | "ai.sql.preview"
  | "ai.sql.generate"
  | "teaching.workspace"
  | "teaching.exercise.toggle"
  | "teaching.exercise.detail"
  | "teaching.exercise.save"
  | "teaching.exercise.copy"
  | "teaching.exercise.import"
  | "teaching.exercise.export"
  | "teaching.analytics"
  | "teaching.interventions"
  | "teaching.intervention.update"
  | "cloud.workspace"
  | "cloud.sync"
  | "cloud.class.create"
  | "cloud.class.member.add"
  | "cloud.assignments"
  | "cloud.assignment.create"
  | "cloud.assignment.update"
  | "cloud.assignment.copy"
  | "cloud.assignment.status"
  | "cloud.class.analytics"
  | "cloud.class.analytics.export"
  | "cloud.assignment.analytics"
  | "cloud.assignment.analytics.export"
  | "settings.workspace"
  | "settings.update"
  | "settings.component.install"
  | "settings.component.cancel"
  | "settings.backups"
  | "settings.backup.create"
  | "settings.backup.restore"
  | "settings.demo.restore"
  | "settings.learning.reset"
  | "settings.cache.clear"
  | "settings.update.check"
  | "settings.notifications.read"
  | "settings.help"
  | "system.health"
  | "session.current"
  | "home.summary"
  | "home.action.dismiss"
  | "course.workspace"
  | "activity.definition"
  | "activity.submit"
  | "knowledge.article"
  | "knowledge.search"
  | "knowledge.read.mark"
  | "knowledge.index.status"
  | "knowledge.index.rebuild"
  | "knowledge.article.import"
  | "knowledge.article.revise"
  | "knowledge.article.visibility"
  | "knowledge.article.delete"
  | "knowledge.import.preview"
  | "knowledge.import.execute"
  | "practice.catalog"
  | "practice.preview"
  | "practice.start"
  | "practice.run"
  | "practice.submit"
  | "practice.hint"
  | "practice.reset"
  | "practice.close"
  | "runner.capabilities"
  | "runner.run"
  | "data.connections"
  | "data.connection.save"
  | "data.connection.test"
  | "data.connection.select"
  | "data.connection.delete"
  | "data.schema"
  | "sql.analyze"
  | "sql.execute"
  | "sql.result.page"
  | "editor.languages"
  | "system.cancel"
  | "system.shutdown";

export type LocalAppEvent = {
  type: "event";
  requestId: string;
  contractVersion: typeof CONTRACT_VERSION;
  event: "progress" | "import.progress" | "runner.progress" | "ai.delta";
  payload: Record<string, unknown>;
};

interface BridgeErrorShape {
  code?: string;
  message?: string;
  retryable?: boolean;
}

export class LocalAppError extends Error {
  readonly code: string;
  readonly retryable: boolean;

  constructor(code: string, message: string, retryable = false) {
    super(message);
    this.name = "LocalAppError";
    this.code = code;
    this.retryable = retryable;
  }

  static from(cause: unknown): LocalAppError {
    if (cause instanceof LocalAppError) return cause;
    if (typeof cause === "object" && cause !== null) {
      const value = cause as BridgeErrorShape;
      return new LocalAppError(
        value.code ?? "LOCAL_BRIDGE_FAILED",
        value.message ?? "无法连接本地 Java 核心。",
        value.retryable ?? true,
      );
    }
    return new LocalAppError("LOCAL_BRIDGE_FAILED", "无法连接本地 Java 核心。", true);
  }
}

export async function localAppRequest<T>(
  method: LocalAppMethod,
  params: Record<string, unknown> = {},
): Promise<T> {
  return localAppRequestWithId<T>(method, params, crypto.randomUUID());
}

export async function localAppRequestWithId<T>(
  method: LocalAppMethod,
  params: Record<string, unknown>,
  requestId: string,
): Promise<T> {
  if (!("__TAURI_INTERNALS__" in window)) {
    throw new LocalAppError("DESKTOP_HOST_REQUIRED", "浏览器预览不提供模拟数据，请从 Tauri 桌面壳启动。", false);
  }
  const started = performance.now();
  try {
    return await invoke<T>("local_app_request", {
      request: {
        requestId,
        method,
        params,
        contractVersion: CONTRACT_VERSION,
      },
    });
  } catch (cause) {
    const error = LocalAppError.from(cause);
    log("error", "ipc.request.failed", { method, code: error.code, retryable: error.retryable });
    throw error;
  } finally {
    measure(`ipc:${method}`, started, { method });
  }
}

export async function cancelLocalAppRequest(targetRequestId: string): Promise<boolean> {
  const result = await localAppRequest<{ cancelled: boolean }>("system.cancel", { targetRequestId });
  return result.cancelled;
}

export async function subscribeLocalAppEvents(
  handler: (event: LocalAppEvent) => void,
): Promise<UnlistenFn> {
  return listen<LocalAppEvent>("local-app-event", ({ payload }) => {
    if (payload.contractVersion === CONTRACT_VERSION && payload.type === "event") handler(payload);
  });
}
