// 账号安全与数据治理域 hook（v3.4.0 REF-13）：从 CloudPage.tsx 原样搬移，
// 覆盖登录会话、修改密码、数据导出与账号删除；不改任何用户可见行为。
// v3.8.0 ACC-D2/D3：新增显示名修改与邮箱绑定/验证（找回密码的前置）。
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../../shared/ipc";
import { downloadJson } from "../../../shared/download";
import { sessionQuery } from "../../../app/queries";
import type { ActiveSession, SessionResult } from "../../../shared/types";
import { useToast } from "../../../shared/ui";
import { cloudKey, sessionsKey } from "./cloudShared";

export function useAccountSecurity() {
  const client = useQueryClient();
  const toast = useToast();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [accountMessage, setAccountMessage] = useState("");
  const [exportTaskId, setExportTaskId] = useState("");
  const [accountOpen, setAccountOpen] = useState(false);
  // v3.8.0 ACC-D2/D3：显示名修改与邮箱绑定/验证的本地输入状态。
  const [profileName, setProfileName] = useState("");
  const [bindEmail, setBindEmail] = useState("");
  const [emailCode, setEmailCode] = useState("");
  const [emailCodeSent, setEmailCodeSent] = useState(false);
  // v3.8.0 ACC-S4/D3：一次性教师升级码兑换（决策点 1 方案 B）。
  const [roleCode, setRoleCode] = useState("");
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
  // v3.8.0 ACC-D3：显示名保存成功后，桥会重签会话并返回最新身份，本地缓存直接替换。
  const updateProfile = useMutation({
    mutationFn: () =>
      localAppRequest<SessionResult>("account.profile.update", { displayName: profileName.trim() }),
    onSuccess: (value) => {
      setProfileName("");
      setAccountMessage("显示名称已更新。");
      toast("success", "显示名称已更新");
      client.setQueryData(sessionQuery.queryKey, value);
      void client.invalidateQueries({ queryKey: cloudKey });
    },
    onError: (error: Error) => setAccountMessage(`显示名称修改失败：${error.message}`),
  });
  // v3.8.0 ACC-D2：向新邮箱发送 6 位验证码（绑定或换绑），再输码完成验证。
  const sendEmailCode = useMutation({
    mutationFn: () => localAppRequest("account.email.bind", { email: bindEmail.trim() }),
    onSuccess: () => {
      setEmailCodeSent(true);
      setAccountMessage(`验证码已发送至 ${bindEmail.trim()}，30 分钟内有效。`);
      toast("success", "验证码已发送");
    },
    onError: (error: Error) => toast("error", `发送验证码失败：${error.message}`),
  });
  const verifyEmail = useMutation({
    mutationFn: () => localAppRequest<SessionResult>("account.email.verify", { code: emailCode.trim() }),
    onSuccess: () => {
      setEmailCode("");
      setBindEmail("");
      setEmailCodeSent(false);
      setAccountMessage("邮箱已验证。之后可通过该邮箱自助找回密码。");
      toast("success", "邮箱已验证");
      void client.invalidateQueries({ queryKey: sessionQuery.queryKey });
      void client.invalidateQueries({ queryKey: cloudKey });
    },
    onError: (error: Error) => toast("error", `邮箱验证失败：${error.message}`),
  });
  // 兑换成功后桥会重签会话，返回值已带 TEACHER 角色，本地缓存直接替换即可即时生效。
  const redeemRole = useMutation({
    mutationFn: () => localAppRequest<SessionResult>("account.role.redeem", { code: roleCode.trim().toUpperCase() }),
    onSuccess: (value) => {
      setRoleCode("");
      setAccountMessage("兑换成功，教师功能已解锁。");
      toast("success", "教师身份已激活");
      client.setQueryData(sessionQuery.queryKey, value);
      void client.invalidateQueries({ queryKey: cloudKey });
    },
    onError: (error: Error) => toast("error", `兑换失败：${error.message}`),
  });
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
    profileName,
    setProfileName,
    updateProfile,
    bindEmail,
    setBindEmail,
    emailCode,
    setEmailCode,
    emailCodeSent,
    setEmailCodeSent,
    sendEmailCode,
    verifyEmail,
    roleCode,
    setRoleCode,
    redeemRole,
  };
}
