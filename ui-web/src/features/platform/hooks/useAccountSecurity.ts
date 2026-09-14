// 账号安全与数据治理域 hook（v3.4.0 REF-13）：从 CloudPage.tsx 原样搬移，
// 覆盖登录会话、修改密码、数据导出与账号删除；不改任何用户可见行为。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../../shared/ipc";
import { downloadJson } from "../../../shared/download";
import type { ActiveSession } from "../../../shared/types";
import { useToast } from "../../../shared/ui";
import { sessionsKey } from "./cloudShared";

export function useAccountSecurity() {
  const client = useQueryClient();
  const toast = useToast();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [accountMessage, setAccountMessage] = useState("");
  const [exportTaskId, setExportTaskId] = useState("");
  const [accountOpen, setAccountOpen] = useState(false);
  const sessions = useQuery({
    queryKey: sessionsKey,
    queryFn: () => localAppRequest<{ items: ActiveSession[] }>("account.sessions"),
    enabled: accountOpen,
    retry: false,
  });
  useEffect(() => {
    if (sessions.isError) toast("error", `加载会话失败：${sessions.error?.message ?? ""}`);
  }, [sessions.isError, sessions.error, toast]);
  const revokeSession = useMutation({
    mutationFn: (sessionId: string) => localAppRequest("account.session.revoke", { sessionId }),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: sessionsKey });
    },
    onError: (error: Error) => toast("error", `撤销会话失败：${error.message}`),
  });
  const changePassword = useMutation({
    mutationFn: () =>
      localAppRequest("account.password.change", {
        currentPassword,
        newPassword,
      }),
    onSuccess: () => {
      setCurrentPassword("");
      setNewPassword("");
      setAccountMessage("密码已修改，其他会话将按服务器策略处理。");
      toast("success", "密码已修改");
    },
    onError: (error: Error) => {
      // 失败时保留输入，用户可直接修改后重试。
      setAccountMessage(`密码修改失败：${error.message}`);
      toast("error", `密码修改失败：${error.message}`);
    },
  });
  const requestExport = useMutation({
    mutationFn: () => localAppRequest<Record<string, unknown>>("account.export.request"),
    onSuccess: (value) => {
      const id = String(value.id ?? value.taskId ?? "");
      setExportTaskId(id);
      setAccountMessage(`数据导出任务已创建：${id || "请稍后刷新"}`);
    },
    onError: (error: Error) => toast("error", `数据导出申请失败：${error.message}`),
  });
  const getExport = useMutation({
    mutationFn: () => localAppRequest<unknown>("account.export.get", { taskId: exportTaskId }),
    onSuccess: (value) => downloadJson(`sqlteacher-account-export-${exportTaskId}.json`, value),
    onError: (error: Error) => toast("error", `获取导出结果失败：${error.message}`),
  });
  const requestDeletion = useMutation({
    mutationFn: () => localAppRequest<Record<string, unknown>>("account.deletion.request"),
    onSuccess: (value) =>
      setAccountMessage(`账号删除已进入撤销期：${String(value.status ?? "PENDING")}`),
    onError: (error: Error) => toast("error", `账号删除申请失败：${error.message}`),
  });
  const cancelDeletion = useMutation({
    mutationFn: () => localAppRequest("account.deletion.cancel"),
    onSuccess: () => setAccountMessage("账号删除已取消。"),
    onError: (error: Error) => toast("error", `取消账号删除失败：${error.message}`),
  });
  const deletionStatus = useMutation({
    mutationFn: () => localAppRequest<Record<string, unknown>>("account.deletion.status"),
    onSuccess: (value) => setAccountMessage(`账号删除状态：${String(value.status ?? "NONE")}`),
    onError: (error: Error) => toast("error", `查询删除状态失败：${error.message}`),
  });
  /** 展开「账号安全与数据治理」时打开会话查询并强制刷新会话列表。 */
  const openAccount = () => {
    setAccountOpen(true);
    void client.invalidateQueries({ queryKey: sessionsKey });
  };
  return {
    currentPassword,
    setCurrentPassword,
    newPassword,
    setNewPassword,
    accountMessage,
    exportTaskId,
    sessions,
    openAccount,
    revokeSession,
    changePassword,
    requestExport,
    getExport,
    requestDeletion,
    cancelDeletion,
    deletionStatus,
  };
}
