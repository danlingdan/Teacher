import { invoke } from "@tauri-apps/api/core";

const CONTRACT_VERSION = "3.0-alpha.1";

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

export async function localAppRequest<T>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  if (!("__TAURI_INTERNALS__" in window)) {
    throw new LocalAppError("DESKTOP_HOST_REQUIRED", "浏览器预览不提供模拟数据，请从 Tauri 桌面壳启动。", false);
  }
  const started = performance.now();
  try {
    return await invoke<T>("local_app_request", {
      request: {
        requestId: crypto.randomUUID(),
        method,
        params,
        contractVersion: CONTRACT_VERSION,
      },
    });
  } finally {
    performance.measure(`ipc:${method}`, { start: started, end: performance.now() });
  }
}
