import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { Button, Dialog, useToast } from "../../shared/ui";
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
import type { ConnectionSummary } from "../../shared/types";

// v3.4.4 CTB-1：数据库连接提升为全局顶栏入口，紧邻通知按钮。chip 显示当前连接，
// popover 负责清单与快速切换（点行即设为当前），新建/编辑在 Dialog 中承载表单。
type EditingTarget = { key: number; isNew: boolean; builtIn: boolean; draft: ConnectionDraft };

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
  const items = connections.data?.items ?? [];
  const current = items.find((item) => item.selected);

  // 数据页侧栏「管理」按钮与空态「连接数据库」按钮都走这个通道唤起面板。
  useEffect(() => subscribeConnectionPanel(() => setOpen(true)), []);
  // v3.5.0 反馈：面板点击外部即关闭；表单/删除确认对话框打开时不抢它们的交互。
  const anchorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open || editing || deleteTarget) return;
    const onPointerDown = (event: MouseEvent) => {
      if (!anchorRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [open, editing, deleteTarget]);

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
            <Button onClick={() => openEditor(emptyConnection(), true)}>新建连接</Button>
          </div>
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
    </div>
  );
}
