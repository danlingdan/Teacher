import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { log, measure } from "./telemetry";

export const CONTRACT_VERSION = "3.0-v1";

export type LocalAppMethod =
  | "account.login"
  | "account.logout"
  | "ai.knowledge.ask"
  | "teaching.workspace"
  | "teaching.exercise.toggle"
  | "cloud.workspace"
  | "cloud.sync"
  | "cloud.class.create"
  | "settings.workspace"
  | "settings.update"
  | "migration.status"
  | "system.health"
  | "session.current"
  | "home.summary"
  | "knowledge.sample"
  | "course.workspace"
  | "knowledge.article"
  | "knowledge.search"
  | "knowledge.import.preview"
  | "knowledge.import.execute"
  | "practice.catalog"
  | "practice.preview"
  | "practice.start"
  | "practice.run"
  | "practice.submit"
  | "runner.capabilities"
  | "runner.run"
  | "data.connections"
  | "data.schema"
  | "sql.analyze"
  | "sql.execute"
  | "sql.result.page"
  | "editor.languages"
  | "benchmark.echo"
  | "task.demo"
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
