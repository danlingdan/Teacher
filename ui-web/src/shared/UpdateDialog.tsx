import { useCallback, useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  localAppRequest,
  localAppRequestWithId,
  subscribeLocalAppEvents,
} from "./ipc";
import type { SettingsPreferences } from "./types";
import { Button, Dialog } from "./ui";

type UpdateManifestView = {
  version: { major: number; minor: number; patch: number } | string;
  releaseNotesUrl?: string;
};

type UpdateCheckView = {
  status: string;
  message: string;
  available?: UpdateManifestView;
};

type UpdatePhase = "idle" | "downloading" | "ready" | "launching";

function formatVersion(version: UpdateManifestView["version"]): string {
  if (typeof version === "string") return version;
  return `${version.major}.${version.minor}.${version.patch}`;
}

// 每个会话只自动检查一次（模块级标记在 StrictMode 重挂载下也生效）。
let startupCheckStarted = false;

/**
 * 启动时自动检查应用更新（默认启用）并在发现新版本时弹窗提示。
 * 弹窗提供应用内下载 + 启动安装程序的完整闭环；"跳过此版本"写入
 * 后端 skippedVersion，之后的自动检查不再提示同一版本。
 */
export function UpdateDialog() {
  const queryClient = useQueryClient();
  const [preferences, setPreferences] = useState<SettingsPreferences>();
  const [open, setOpen] = useState(false);
  const [available, setAvailable] = useState<UpdateManifestView>();
  const [phase, setPhase] = useState<UpdatePhase>("idle");
  const [fraction, setFraction] = useState(0);
  const [error, setError] = useState("");

  useEffect(() => {
    void localAppRequest<SettingsPreferences>("settings.preferences").then(
      setPreferences,
    );
  }, []);

  useEffect(() => {
    const general = preferences?.general;
    if (!general) return;
    if (startupCheckStarted) return;
    if (!general.automaticUpdateChecks) return;
    startupCheckStarted = true;
    void (async () => {
      try {
        const result = await localAppRequest<UpdateCheckView>(
          "settings.update.check",
        );
        const version = result.available
          ? formatVersion(result.available.version)
          : "";
        if (result.status === "AVAILABLE" && version !== general.skippedVersion) {
          setAvailable(result.available);
          setOpen(true);
        }
      } catch {
        // 自动检查失败静默降级，绝不影响本地使用；设置页仍可手动检查。
      }
    })();
  }, [preferences]);

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

  const skip = useCallback(() => {
    if (!available) return;
    void localAppRequest("settings.update.skip", {
      version: formatVersion(available.version),
    })
      .then(() =>
        queryClient.invalidateQueries({ queryKey: ["settings", "preferences"] }),
      )
      .finally(() => setOpen(false));
  }, [available, queryClient]);

  const newVersion = available ? formatVersion(available.version) : "";
  // 在 JSX 外先取布尔值，避免 TS 对 phase 字面量收窄后误判比较无意义。
  const downloading = phase === "downloading";
  const launching = phase === "launching";
  const ready = phase === "ready";
  const busy = downloading || launching;
  return (
    <Dialog
      open={open}
      title={`发现新版本 SQLTeacher ${newVersion}`}
      onClose={() => {
        if (!busy) setOpen(false);
      }}
    >
      <p>可在应用内直接下载官方安装包并启动升级，无需手动访问网站。</p>
      {downloading && <p>正在下载更新… {Math.round(fraction * 100)}%</p>}
      {error && <p className="muted">{error}</p>}
      <div className="button-row">
        {!ready ? (
          <Button busy={downloading} disabled={busy} onClick={download}>
            {downloading
              ? `下载中 ${Math.round(fraction * 100)}%`
              : "立即下载并安装"}
          </Button>
        ) : (
          <Button busy={launching} disabled={launching} onClick={launch}>
            启动安装程序
          </Button>
        )}
        <Button variant="secondary" disabled={busy} onClick={skip}>
          跳过此版本
        </Button>
        <Button variant="secondary" disabled={busy} onClick={() => setOpen(false)}>
          稍后提醒
        </Button>
      </div>
    </Dialog>
  );
}
