import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { localAppRequest } from "../../shared/ipc";
import { connectionsQuery } from "../../app/queries";
import {
  ConnectionEditor,
  dialectLabel,
  emptyConnection,
  useConnectionDialects,
  valueToDraft,
} from "./ConnectionManager";
import { subscribeConnectionPanel } from "./connectionPanel";
import type { ConnectionDraft } from "./ConnectionManager";
import type { ConnectionSummary, ConnectionTestResult } from "../../shared/types";

// v3.4.4 CTB-1：数据库连接提升为全局顶栏入口，紧邻通知按钮。chip 显示当前连接，
// popover 负责清单与快速切换（点行即设为当前），新建/编辑在 Dialog 中承载表单。
// v3.10.0 HAJ-5：另加"测试当前连接"一键校验与服务器型连接的"重新输入密码"快捷恢复。
// v3.10.0 HAJ-1 修订：AI 引擎分区撤出本弹层，移至独立顶栏按钮 TopbarAiEngine。
type EditingTarget = { key: number; isNew: boolean; builtIn: boolean; draft: ConnectionDraft };
type ReauthTarget = { id: string; displayName: string };

// v3.4.4：清单行显示连接目标（文件名 / 主机:端口/库），让内置库与用户自建库一眼可辨。
function connectionTargetDetail(item: ConnectionSummary): string {
  if (item.databasePath) {
    return item.databasePath.split(/[\\/]/).pop() ?? "";
  }
  if (item.host) {
    return `${item.host}${item.port ? `:${item.port}` : ""}${
      item.databaseName ? `/${item.databaseName}` : ""
    }`;
  }
  return "";
}

export default function TopbarConnection() {
  const client = useQueryClient();
  const toast = useToast();
  const connections = useQuery(connectionsQuery);
  const dialects = useConnectionDialects();
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<EditingTarget>();
  const [deleteTarget, setDeleteTarget] = useState<ConnectionSummary>();
  const [reauth, setReauth] = useState<ReauthTarget>();
  const [reauthPassword, setReauthPassword] = useState("");
  const [reauthError, setReauthError] = useState("");
  const [quickTest, setQuickTest] = useState<ConnectionTestResult | null>(null);
  const items = connections.data?.items ?? [];
  const current = items.find((item) => item.selected);
  // 服务器型连接（非文件型）才有"重新输入密码"的恢复语义；文件型无凭据。
  const isFileBased = (dialect: string) =>
    dialects.find((option) => option.name === dialect)?.fileBased ?? false;

  // 数据页侧栏「管理」按钮与空态「连接数据库」按钮都走这个通道唤起面板。
  useEffect(() => subscribeConnectionPanel(() => setOpen(true)), []);
  // v3.5.0 反馈：面板点击外部即关闭。连接表单与重新输入密码的 Dialog 渲染在弹层
  // DOM 之外的固定背板上，背板内的按下不算"点击外部"（AI 弹层同规则，见 TopbarAiEngine）。
  const anchorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement;
      if (anchorRef.current?.contains(target)) return;
      if (target.closest(".ui-dialog-backdrop")) return;
      setOpen(false);
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open]);

  const refresh = () => client.invalidateQueries({ queryKey: connectionsQuery.queryKey });
  const select = useMutation({
    mutationFn: (connectionId: string) =>
      localAppRequest<ConnectionSummary>("data.connection.select", { connectionId }),
    onSuccess: () => refresh(),
    onError: (error: Error) => toast("error", `切换连接失败：${error.message}`),
  });
  const remove = useMutation({
    mutationFn: (connectionId: string) =>
      localAppRequest("data.connection.delete", { connectionId }),
    onSuccess: async () => {
      setDeleteTarget(undefined);
      await refresh();
      toast("success", "连接已删除");
    },
    onError: (error: Error) => toast("error", `删除连接失败：${error.message}`),
  });
  // v3.10.0 HAJ-5：一键校验当前连接。密码为空时 Java 侧回退本进程会话凭据，语义不变。
  const testCurrent = useMutation({
    mutationFn: (connectionId: string) =>
      localAppRequest<ConnectionTestResult>("data.connection.test", { connectionId }),
    onSuccess: (result) => setQuickTest(result),
    onError: (error: Error) => toast("error", `测试失败：${error.message}`),
  });
  // v3.10.0 HAJ-5：认证失败快捷恢复——只输密码重验，成功即回写本进程会话凭据。
  const reauthTest = useMutation({
    mutationFn: (input: { connectionId: string; password: string }) =>
      localAppRequest<ConnectionTestResult>("data.connection.test", {
        connectionId: input.connectionId,
        password: input.password,
      }),
    onSuccess: (result) => {
      if (result.successful) {
        setReauth(undefined);
        setReauthPassword("");
        setReauthError("");
        void refresh();
        toast("success", "密码已验证，本次运行内有效");
      } else {
        setReauthError(result.message);
      }
    },
    onError: (error: Error) => setReauthError(error.message),
  });
  const startReauth = (item: ConnectionSummary) => {
    setReauth({ id: item.id, displayName: item.displayName });
    setReauthPassword("");
    setReauthError("");
  };
  const openEditor = (draft: ConnectionDraft, isNew: boolean, builtIn = false) => {
    setEditing({ key: Date.now(), isNew, builtIn, draft });
  };
  const copyConnection = (item: ConnectionSummary) => {
    const copied = valueToDraft(item);
    openEditor({ ...copied, id: "", displayName: `${copied.displayName} 副本` }, true);
    toast("success", "已复制配置，确认后点“测试并保存”");
  };
  const chipLabel = current
    ? current.displayName
    : connections.isPending
      ? "数据库连接…"
      : "连接数据库";
  return (
    <div className="connection-anchor" ref={anchorRef}>
      <button
        type="button"
        className={`connection-trigger${current ? "" : " missing"}`}
        aria-label={`数据库连接${current ? `：${current.displayName}` : "，尚未选择连接"}`}
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
      >
        <span aria-hidden="true">▦</span>
        <span className="connection-chip-label">{chipLabel}</span>
        {current && <small>{dialectLabel(dialects, current.dialect)}</small>}
        {current?.readOnly && <small>只读</small>}
      </button>
      {open && (
        <section className="connection-popover" role="dialog" aria-label="数据库连接">
          <header>
            <div>
              <strong>数据库连接</strong>
              <small>
                {current
                  ? `当前：${current.displayName}`
                  : "尚未选择连接，点列表中的连接即可切换。"}
              </small>
            </div>
          </header>
          {connections.isError && (
            <p className="muted">连接列表读取失败：{connections.error?.message}</p>
          )}
          <ul>
            {items.map((item) => (
              <li key={item.id} className={item.selected ? "current" : ""}>
                <button
                  type="button"
                  className="connection-row"
                  disabled={!item.enabled || select.isPending}
                  title={item.enabled ? "点击设为当前连接" : "连接已停用"}
                  onClick={() => select.mutate(item.id)}
                >
                  <strong>
                    {item.displayName}
                    {item.selected && <span className="connection-current-chip">当前</span>}
                  </strong>
                  <small>
                    {dialectLabel(dialects, item.dialect)}
                    {item.readOnly ? " · 只读" : ""}
                    {!item.enabled ? " · 已停用" : ""}
                  </small>
                  {connectionTargetDetail(item) && (
                    <small className="connection-row-target">
                      {connectionTargetDetail(item)}
                    </small>
                  )}
                </button>
                <div className="connection-row-actions">
                  <Button
                    variant="secondary"
                    onClick={() => openEditor(valueToDraft(item), false, item.builtIn)}
                  >
                    编辑
                  </Button>
                  {!isFileBased(item.dialect) && (
                    <Button variant="secondary" onClick={() => startReauth(item)}>
                      重新输入密码
                    </Button>
                  )}
                  {!item.builtIn && (
                    <Button variant="secondary" onClick={() => copyConnection(item)}>
                      复制
                    </Button>
                  )}
                  {!item.builtIn && (
                    <Button variant="danger" onClick={() => setDeleteTarget(item)}>
                      删除
                    </Button>
                  )}
                </div>
              </li>
            ))}
            {items.length === 0 && !connections.isError && (
              <li className="connection-empty">
                <p className="muted">还没有数据库连接。创建一个即可在这里快速切换。</p>
              </li>
            )}
          </ul>
          <div className="connection-popover-footer">
            <Button
              variant="secondary"
              disabled={!current}
              busy={testCurrent.isPending}
              onClick={() => current && testCurrent.mutate(current.id)}
            >
              测试当前连接
            </Button>
            <Button onClick={() => openEditor(emptyConnection(), true)}>新建连接</Button>
          </div>
          {quickTest && (
            <p
              className={`connection-test-result ${quickTest.successful ? "ok" : "bad"}`}
              role="status"
            >
              {quickTest.successful ? "✓ " : "✕ "}
              {quickTest.message}
            </p>
          )}
        </section>
      )}
      <Dialog
        open={Boolean(editing)}
        title={editing?.isNew ? "新建数据库连接" : "编辑数据库连接"}
        onClose={() => setEditing(undefined)}
      >
        {editing && (
          <ConnectionEditor
            key={editing.key}
            initial={editing.draft}
            builtIn={editing.builtIn}
            onSaved={(summary) => {
              setEditing(undefined);
              // 与旧行为一致：保存后的连接直接成为当前连接。
              select.mutate(summary.id);
            }}
          />
        )}
      </Dialog>
      <Dialog
        open={Boolean(deleteTarget)}
        title="删除数据库连接"
        onClose={() => setDeleteTarget(undefined)}
      >
        <p>确认删除“{deleteTarget?.displayName}”？密码缓存会同时清除。</p>
        <div className="button-row">
          <Button variant="secondary" onClick={() => setDeleteTarget(undefined)}>
            取消
          </Button>
          <Button
            variant="danger"
            busy={remove.isPending}
            onClick={() => deleteTarget && remove.mutate(deleteTarget.id)}
          >
            确认删除
          </Button>
        </div>
      </Dialog>
      <Dialog
        open={Boolean(reauth)}
        title="重新输入数据库密码"
        onClose={() => setReauth(undefined)}
      >
        <p className="muted">
          “{reauth?.displayName}”的密码只暂存在本进程内存中，应用重启后需重新验证；验证成功后本次运行内可直接使用。
        </p>
        <FormField label="数据库密码">
          {(ids) => (
            <input
              {...ids}
              type="password"
              autoComplete="current-password"
              value={reauthPassword}
              onChange={(event) => setReauthPassword(event.target.value)}
            />
          )}
        </FormField>
        {reauthError && (
          <Feedback tone="error" title="验证失败">
            {reauthError}
          </Feedback>
        )}
        <div className="button-row">
          <Button variant="secondary" onClick={() => setReauth(undefined)}>
            取消
          </Button>
          <Button
            busy={reauthTest.isPending}
            disabled={!reauthPassword}
            onClick={() => reauth && reauthTest.mutate({ connectionId: reauth.id, password: reauthPassword })}
          >
            验证密码
          </Button>
        </div>
      </Dialog>
    </div>
  );
}
