import { useCallback, useEffect, useRef, useState } from "react";
import {
  cancelLocalAppRequest,
  localAppRequest,
  localAppRequestWithId,
  subscribeLocalAppEvents,
} from "./ipc";

export type UpdateInstallPhase = "idle" | "downloading" | "ready" | "launching";

export function formatManifestVersion(
  version: string | { major: number; minor: number; patch: number },
): string {
  if (typeof version === "string") return version;
  return `${version.major}.${version.minor}.${version.patch}`;
}

/**
 * 应用内「下载官方安装包 → 启动安装程序」的共享状态机。
 * UpdateDialog（启动自动检查弹窗）与设置页「检查更新」/「重装安装包」共用，
 * 保证任何入口发现新版本后都有可用的下载闭环。
 * download 走常规 check→download 闭环；forceDownload 跳过版本门控直接取最新
 * 签名清单下载（同版本重装/修复，也便于对已发布清单做端到端验证）。
 */
export function useUpdateInstaller() {
  const [phase, setPhase] = useState<UpdateInstallPhase>("idle");
  const [fraction, setFraction] = useState(0);
  const [error, setError] = useState("");
  const activeRequestRef = useRef("");
  const stopProgressRef = useRef<(() => void) | undefined>(undefined);

  // 组件卸载（如用户中途关闭弹窗/页面）时退订仍在进行的下载进度监听，避免泄漏。
  useEffect(() => () => stopProgressRef.current?.(), []);

  const beginDownload = useCallback((method: "settings.update.download" | "settings.update.forceDownload") => {
    const requestId = crypto.randomUUID();
    activeRequestRef.current = requestId;
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
      stopProgressRef.current = stop;
    });
    void localAppRequestWithId(method, {}, requestId)
      .then(() => setPhase("ready"))
      .catch((cause: unknown) => {
        setPhase("idle");
        setError(cause instanceof Error ? cause.message : String(cause));
      })
      .finally(() => {
        activeRequestRef.current = "";
        // 事件监听在流结束后保留一拍再退订，避免吞掉最后的进度帧。
        window.setTimeout(() => {
          unlisten?.();
          if (stopProgressRef.current === unlisten) stopProgressRef.current = undefined;
        }, 500);
      });
  }, []);

  const download = useCallback(() => beginDownload("settings.update.download"), [beginDownload]);
  const forceDownload = useCallback(() => beginDownload("settings.update.forceDownload"), [beginDownload]);

  /** 取消进行中的下载：Java 侧在下一个进度帧中止，已下载的部分文件保留供续传。 */
  const cancel = useCallback(() => {
    if (!activeRequestRef.current) return;
    void cancelLocalAppRequest(activeRequestRef.current).catch(() => {
      // 取消通道本身失败不改变状态机：请求仍会在完成/失败时自然落回 idle/ready。
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

  return { phase, fraction, error, download, forceDownload, cancel, launch };
}
