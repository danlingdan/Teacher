import { useCallback, useState } from "react";
import { localAppRequest, localAppRequestWithId, subscribeLocalAppEvents } from "./ipc";

export type UpdateInstallPhase = "idle" | "downloading" | "ready" | "launching";

export function formatManifestVersion(
  version: string | { major: number; minor: number; patch: number },
): string {
  if (typeof version === "string") return version;
  return `${version.major}.${version.minor}.${version.patch}`;
}

/**
 * 应用内「下载官方安装包 → 启动安装程序」的共享状态机。
 * UpdateDialog（启动自动检查弹窗）与设置页「检查更新」（手动）共用，
 * 保证任何入口发现新版本后都有可用的下载闭环。
 */
export function useUpdateInstaller() {
  const [phase, setPhase] = useState<UpdateInstallPhase>("idle");
  const [fraction, setFraction] = useState(0);
  const [error, setError] = useState("");

  const download = useCallback(() => {
    const requestId = crypto.randomUUID();
    setPhase("downloading");
    setFraction(0);
    setError("");
    let unlisten: (() => void) | undefined;
    void subscribeLocalAppEvents((event) => {
      if (event.requestId !== requestId || event.event !== "progress") return;
      const payload = event.payload as { phase?: string; fraction?: number };
      if (payload.phase === "update.download") {
        setFraction(payload.fraction ?? 0);
      }
    }).then((stop) => {
      unlisten = stop;
    });
    void localAppRequestWithId("settings.update.download", {}, requestId)
      .then(() => setPhase("ready"))
      .catch((cause: unknown) => {
        setPhase("idle");
        setError(cause instanceof Error ? cause.message : String(cause));
      })
      .finally(() => {
        // 事件监听在流结束后保留一拍再退订，避免吞掉最后的进度帧。
        window.setTimeout(() => unlisten?.(), 500);
      });
  }, []);

  const launch = useCallback(() => {
    setPhase("launching");
    setError("");
    void localAppRequest("settings.update.install", {})
      .then(() => {
        // 安装程序已启动；应用保持运行，按安装器指引完成升级。
      })
      .catch((cause: unknown) => {
        setPhase("ready");
        setError(cause instanceof Error ? cause.message : String(cause));
      });
  }, []);

  return { phase, fraction, error, download, launch };
}
