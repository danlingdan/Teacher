import { useCallback, useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "./ipc";
import { settingsPreferencesQuery } from "../app/queries";
import { Button, Dialog } from "./ui";
import { formatManifestVersion, useUpdateInstaller } from "./useUpdateInstaller";

type UpdateManifestView = {
  version: { major: number; minor: number; patch: number } | string;
  releaseNotesUrl?: string;
};

type UpdateCheckView = {
  status: string;
  message: string;
  available?: UpdateManifestView;
};

// 每个会话只自动检查一次（模块级标记在 StrictMode 重挂载下也生效）。
let startupCheckStarted = false;

/**
 * 启动时自动检查应用更新（默认启用）并在发现新版本时弹窗提示。
 * 弹窗提供应用内下载 + 启动安装程序的完整闭环；"跳过此版本"写入
 * 后端 skippedVersion，之后的自动检查不再提示同一版本。
 * 下载/安装逻辑在 useUpdateInstaller，与设置页「检查更新」共用。
 */
export function UpdateDialog() {
  const queryClient = useQueryClient();
  // 走共享 react-query 缓存：偏好更新（如"跳过此版本"）后弹窗状态同步刷新。
  const preferencesQuery = useQuery(settingsPreferencesQuery);
  const preferences = preferencesQuery.data;
  const [open, setOpen] = useState(false);
  const [available, setAvailable] = useState<UpdateManifestView>();
  const installer = useUpdateInstaller();

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
          ? formatManifestVersion(result.available.version)
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

  const skip = useCallback(() => {
    if (!available) return;
    void localAppRequest("settings.update.skip", {
      version: formatManifestVersion(available.version),
    })
      .then(() =>
        queryClient.invalidateQueries({
          queryKey: settingsPreferencesQuery.queryKey,
        }),
      )
      .finally(() => setOpen(false));
  }, [available, queryClient]);

  const newVersion = available ? formatManifestVersion(available.version) : "";
  // 在 JSX 外先取布尔值，避免 TS 对 phase 字面量收窄后误判比较无意义。
  const downloading = installer.phase === "downloading";
  const launching = installer.phase === "launching";
  const ready = installer.phase === "ready";
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
      {downloading && <p>正在下载更新… {Math.round(installer.fraction * 100)}%</p>}
      {installer.error && <p className="muted">{installer.error}</p>}
      <div className="button-row">
        {!ready ? (
          <Button busy={downloading} disabled={busy} onClick={installer.download}>
            {downloading
              ? `下载中 ${Math.round(installer.fraction * 100)}%`
              : "立即下载并安装"}
          </Button>
        ) : (
          <Button busy={launching} disabled={launching} onClick={installer.launch}>
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
