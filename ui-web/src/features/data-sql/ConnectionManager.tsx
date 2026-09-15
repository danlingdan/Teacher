import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Button, Feedback, FormField, useToast } from "../../shared/ui";
import { localAppRequest } from "../../shared/ipc";
import { connectionDialectsQuery } from "../../app/queries";
import type {
  ConnectionDialectOption,
  ConnectionSummary,
  ConnectionTestResult,
} from "../../shared/types";

export type ConnectionDraft = {
  id: string;
  displayName: string;
  dialect: string;
  databasePath: string;
  host: string;
  port: string;
  databaseName: string;
  username: string;
  password: string;
  jdbcUrl: string;
  driverClass: string;
  driverJar: string;
  readOnly: boolean;
  enabled: boolean;
};
export const emptyConnection = (): ConnectionDraft => ({
  id: "",
  displayName: "",
  dialect: "SQLITE",
  databasePath: "",
  host: "localhost",
  port: "",
  databaseName: "",
  username: "",
  password: "",
  jdbcUrl: "",
  driverClass: "",
  driverJar: "",
  readOnly: false,
  enabled: true,
});
// 方言元数据由 Java 侧 data.connection.dialects 提供；请求失败时退化为裸枚举名，表单仍可用。
const FALLBACK_DIALECTS: ConnectionDialectOption[] = [
  { name: "SQLITE", displayName: "SQLite", defaultPort: 0, fileBased: true, generic: false },
  { name: "DUCKDB", displayName: "DuckDB", defaultPort: 0, fileBased: true, generic: false },
  { name: "H2", displayName: "H2", defaultPort: 0, fileBased: true, generic: false },
  { name: "MYSQL", displayName: "MySQL", defaultPort: 3306, fileBased: false, generic: false },
  { name: "MARIADB", displayName: "MariaDB", defaultPort: 3306, fileBased: false, generic: false },
  {
    name: "POSTGRESQL",
    displayName: "PostgreSQL",
    defaultPort: 5432,
    fileBased: false,
    generic: false,
  },
  {
    name: "SQL_SERVER",
    displayName: "SQL Server",
    defaultPort: 1433,
    fileBased: false,
    generic: false,
  },
  { name: "ORACLE", displayName: "Oracle", defaultPort: 1521, fileBased: false, generic: false },
  { name: "DB2", displayName: "Db2", defaultPort: 50000, fileBased: false, generic: false },
  { name: "DAMENG", displayName: "达梦 DM8", defaultPort: 5236, fileBased: false, generic: false },
  { name: "TIDB", displayName: "TiDB", defaultPort: 4000, fileBased: false, generic: false },
  {
    name: "OCEANBASE",
    displayName: "OceanBase",
    defaultPort: 2881,
    fileBased: false,
    generic: false,
  },
  { name: "GAUSSDB", displayName: "GaussDB", defaultPort: 5432, fileBased: false, generic: false },
  { name: "GENERIC", displayName: "通用 JDBC", defaultPort: 0, fileBased: false, generic: true },
];
export const dialectLabel = (options: ConnectionDialectOption[], name: string) =>
  options.find((option) => option.name === name)?.displayName ?? name;

export function useConnectionDialects(): ConnectionDialectOption[] {
  const query = useQuery(connectionDialectsQuery);
  return query.data?.items.length ? query.data.items : FALLBACK_DIALECTS;
}

// 连接 ID 由显示名生成小写别名加随机后缀，匹配 Java 侧 [a-z0-9][a-z0-9._-]{0,63}。
function connectionIdFrom(displayName: string): string {
  const slug = displayName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48);
  const token = Math.random().toString(36).slice(2, 6);
  return `${slug || "connection"}-${token}`;
}

function defaultDisplayName(draft: ConnectionDraft, dialect?: ConnectionDialectOption): string {
  const label = dialect?.displayName ?? draft.dialect;
  const detail = dialect?.fileBased
    ? draft.databasePath.split(/[\\/]/).pop()
    : draft.databaseName.trim() || draft.host.trim();
  return detail ? `${label} ${detail}` : `${label} 连接`;
}

function missingConnectionFields(
  draft: ConnectionDraft,
  dialect?: ConnectionDialectOption,
): string {
  if (dialect?.fileBased) return draft.databasePath.trim() ? "" : "数据库文件路径";
  if (dialect?.generic)
    return [draft.jdbcUrl, draft.driverClass, draft.driverJar].every((value) => value.trim())
      ? ""
      : "JDBC URL、驱动类与驱动 JAR";
  return [draft.host, draft.databaseName, draft.username].every((value) => value.trim())
    ? ""
    : "主机、数据库与用户名";
}

export function valueToDraft(item: ConnectionSummary): ConnectionDraft {
  return {
    ...emptyConnection(),
    ...item,
    port: item.port ? String(item.port) : "",
    databasePath: item.databasePath ?? "",
    host: item.host ?? "localhost",
    databaseName: item.databaseName ?? "",
    username: item.username ?? "",
    jdbcUrl: item.jdbcUrl ?? "",
    driverClass: item.driverClass ?? "",
    driverJar: item.driverJar ?? "",
    password: "",
  };
}

// v3.4.3 CXN-1：常用类型一键直达，其余 11 种方言与通用 JDBC 收进「更多类型」。
const QUICK_DIALECTS = ["MYSQL", "SQLITE", "POSTGRESQL"];

// v3.4.4 CTB-1：连接管理上移到顶栏后，这里只保留表单本身（新建/编辑在 Dialog 中打开）。
// 连接清单、设为当前、复制、删除归 TopbarConnection 的 popover 管。父组件通过 key 重新
// 挂载来切换编辑目标（initial 只在挂载时生效）。
export function ConnectionEditor({
  initial,
  builtIn = false,
  onSaved,
}: {
  initial: ConnectionDraft;
  /** builtIn 连接（随包演示库）不允许改连接 ID。 */
  builtIn?: boolean;
  onSaved: (summary: ConnectionSummary) => void;
}) {
  const toast = useToast();
  const [draft, setDraft] = useState<ConnectionDraft>(initial);
  const [result, setResult] = useState<ConnectionTestResult>();
  // v3.4.4：「列出数据库」——服务器连接可直接读取库列表供选择，也保留手输。
  const [databaseOptions, setDatabaseOptions] = useState<string[]>([]);
  const [catalogHint, setCatalogHint] = useState("");
  const dialectOptions = useConnectionDialects();
  const dialect = dialectOptions.find((option) => option.name === draft.dialect);
  const fileBased = dialect?.fileBased ?? false;
  const generic = dialect?.generic ?? false;
  // 一个连接都没有时自动展开，避免新用户找不到入口。
  // 连接 ID 与显示名称留空时自动生成并回写表单：同一次提交里测试与保存必须用同一个 ID，
  // 否则测试成功暂存的凭据会挂在另一个 ID 下。
  const submitPayload = () => {
    const displayName = draft.displayName.trim() || defaultDisplayName(draft, dialect);
    const id = draft.id.trim() || connectionIdFrom(displayName);
    if (id !== draft.id || displayName !== draft.displayName) {
      setDraft((value) => ({ ...value, id, displayName }));
    }
    return { ...draft, id, displayName, port: Number(draft.port || 0) };
  };
  const save = useMutation({
    mutationFn: async () => {
      const payload = submitPayload();
      // 保存前强制真实连接测试：失败的连接不会被保存。
      const tested = await localAppRequest<ConnectionTestResult>("data.connection.test", {
        ...payload,
        password: draft.password,
      });
      if (!tested.successful) {
        setResult(tested);
        throw new Error(tested.message);
      }
      setResult(tested);
      return localAppRequest<ConnectionSummary>("data.connection.save", payload);
    },
    onSuccess: (value) => {
      setDraft(valueToDraft(value));
      onSaved(value);
      toast("success", "连接已保存");
    },
    onError: (error: Error) => toast("error", `连接保存失败：${error.message}`),
    onSettled: () => setDraft((value) => ({ ...value, password: "" })),
  });
  // 测试成功后清空表单密码是刻意的：Java 侧 DatabaseCredentialSession 已记住本次凭据，
  // 保存时用空密码即可；避免密码长期留在前端表单状态里。
  const test = useMutation({
    mutationFn: () =>
      localAppRequest<ConnectionTestResult>("data.connection.test", {
        ...submitPayload(),
        password: draft.password,
      }),
    onSuccess: setResult,
    onSettled: () => setDraft((value) => ({ ...value, password: "" })),
    onError: (error: Error) => toast("error", `连接测试失败：${error.message}`),
  });
  const listDatabases = useMutation({
    mutationFn: () =>
      localAppRequest<{ items: string[]; message: string }>("data.connection.databases", {
        ...submitPayload(),
        password: draft.password,
      }),
    onSuccess: (value) => {
      setDatabaseOptions(value.items);
      setCatalogHint(value.message);
    },
    onError: (error: Error) => toast("error", `读取数据库列表失败：${error.message}`),
    onSettled: () => setDraft((value) => ({ ...value, password: "" })),
  });
  const browseDatabaseFile = async () => {
    try {
      const { open } = await import("@tauri-apps/plugin-dialog");
      const selection = await open({
        multiple: false,
        directory: false,
        title: "选择数据库文件",
        filters: [
          { name: "数据库文件", extensions: ["db", "sqlite", "sqlite3", "duckdb", "mv.db"] },
          { name: "所有文件", extensions: ["*"] },
        ],
      });
      if (typeof selection === "string" && selection.trim()) {
        setDraft((value) => ({ ...value, databasePath: selection }));
        setResult(undefined);
      }
    } catch {
      toast("error", "无法打开文件选择器，请直接输入文件路径");
    }
  };
  // v3.4.3 CXN-1：类型选择傻瓜化——常用类型一键选中，其余收进「更多类型」；
  // 选中后表单只保留该类型必要字段，端口自动填默认值。
  const selectDialect = (option: ConnectionDialectOption) => {
    setDraft((value) => ({
      ...value,
      dialect: option.name,
      port: option.defaultPort > 0 ? String(option.defaultPort) : "",
    }));
    setDatabaseOptions([]);
    setCatalogHint("");
    setResult(undefined);
  };
  const quickDialects = dialectOptions.filter((option) => QUICK_DIALECTS.includes(option.name));
  const moreDialects = dialectOptions.filter((option) => !QUICK_DIALECTS.includes(option.name));
  const createEmptyDatabaseFile = async () => {
    try {
      const { save: saveDialog } = await import("@tauri-apps/plugin-dialog");
      const extension =
        draft.dialect === "H2" ? "mv.db" : draft.dialect === "DUCKDB" ? "duckdb" : "db";
      const selection = await saveDialog({
        title: "新建数据库文件",
        defaultPath: `database.${extension}`,
        filters: [{ name: "数据库文件", extensions: [extension] }],
      });
      if (typeof selection === "string" && selection.trim()) {
        setDraft((value) => ({ ...value, databasePath: selection }));
        setResult(undefined);
        toast("success", "已选择路径，测试并保存后会创建新的空数据库");
      }
    } catch {
      toast("error", "无法打开保存对话框，请直接输入文件路径");
    }
  };
  const missing = missingConnectionFields(draft, dialect);
  const busy = save.isPending || test.isPending;
  return (
    <div className="connection-editor">
      <div className="settings-grid">
        <FormField label="数据库类型">
          {() => (
            <div className="dialect-picker">
              <div className="dialect-quick-row" role="group" aria-label="常用数据库类型">
                {quickDialects.map((option) => (
                  <button
                    key={option.name}
                    type="button"
                    className={`dialect-chip${draft.dialect === option.name ? " active" : ""}`}
                    aria-pressed={draft.dialect === option.name}
                    onClick={() => selectDialect(option)}
                  >
                    {option.displayName}
                  </button>
                ))}
              </div>
              <select
                aria-label="更多数据库类型"
                value={QUICK_DIALECTS.includes(draft.dialect) ? "" : draft.dialect}
                onChange={(event) => {
                  const next = dialectOptions.find((option) => option.name === event.target.value);
                  if (next) selectDialect(next);
                }}
              >
                <option value="" disabled>
                  更多类型…
                </option>
                {moreDialects.map((option) => (
                  <option key={option.name} value={option.name}>
                    {option.displayName}
                  </option>
                ))}
              </select>
            </div>
          )}
        </FormField>
        <FormField label="显示名称" hint="留空会自动生成">
          {(ids) => (
            <input
              {...ids}
              value={draft.displayName}
              onChange={(event) => setDraft({ ...draft, displayName: event.target.value })}
              placeholder="留空自动生成"
            />
          )}
        </FormField>
        {fileBased ? (
          <FormField
            label="数据库文件"
            hint={draft.dialect === "SQLITE" ? "文件不存在时会创建新的空数据库" : undefined}
          >
            {(ids) => (
              <div className="button-row">
                <input
                  {...ids}
                  value={draft.databasePath}
                  onChange={(event) => setDraft({ ...draft, databasePath: event.target.value })}
                  placeholder="选择或输入文件路径"
                />
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void browseDatabaseFile()}
                >
                  浏览…
                </Button>
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void createEmptyDatabaseFile()}
                >
                  新建空库…
                </Button>
              </div>
            )}
          </FormField>
        ) : generic ? (
          <>
            <FormField label="JDBC URL">
              {(ids) => (
                <input
                  {...ids}
                  value={draft.jdbcUrl}
                  onChange={(event) => setDraft({ ...draft, jdbcUrl: event.target.value })}
                />
              )}
            </FormField>
            <FormField label="驱动类">
              {(ids) => (
                <input
                  {...ids}
                  value={draft.driverClass}
                  onChange={(event) => setDraft({ ...draft, driverClass: event.target.value })}
                />
              )}
            </FormField>
            <FormField label="驱动 JAR">
              {(ids) => (
                <input
                  {...ids}
                  value={draft.driverJar}
                  onChange={(event) => setDraft({ ...draft, driverJar: event.target.value })}
                />
              )}
            </FormField>
          </>
        ) : (
          <>
            <FormField label="主机">
              {(ids) => (
                <input
                  {...ids}
                  value={draft.host}
                  onChange={(event) => setDraft({ ...draft, host: event.target.value })}
                />
              )}
            </FormField>
            <FormField label="端口" hint="通常保持默认即可">
              {(ids) => (
                <input
                  {...ids}
                  type="number"
                  value={draft.port}
                  onChange={(event) => setDraft({ ...draft, port: event.target.value })}
                  placeholder={
                    dialect && dialect.defaultPort > 0 ? `默认 ${dialect.defaultPort}` : "默认"
                  }
                />
              )}
            </FormField>
            <FormField
              label="数据库"
              hint={catalogHint || "可手动输入，或点「列出数据库」从服务器读取"}
            >
              {(ids) => (
                <div className="database-picker">
                  <div className="button-row">
                    <input
                      {...ids}
                      value={draft.databaseName}
                      onChange={(event) => setDraft({ ...draft, databaseName: event.target.value })}
                      placeholder="手动输入，或点右侧列出"
                    />
                    <Button
                      variant="secondary"
                      disabled={
                        busy || listDatabases.isPending || (!fileBased && !generic && !draft.username.trim())
                      }
                      busy={listDatabases.isPending}
                      onClick={() => listDatabases.mutate()}
                    >
                      列出数据库
                    </Button>
                  </div>
                  {/* v3.4.4：原生 datalist 按输入值前缀过滤，输入不匹配时点箭头弹不出任何项——
                      改为显式下拉，选中即回填输入框。 */}
                  {databaseOptions.length > 0 && (
                    <select
                      aria-label="从服务器数据库列表选择"
                      value=""
                      onChange={(event) => {
                        if (event.target.value) setDraft({ ...draft, databaseName: event.target.value });
                      }}
                    >
                      <option value="">
                        从服务器列表选择（共 {databaseOptions.length} 个）…
                      </option>
                      {databaseOptions.map((name) => (
                        <option key={name} value={name}>
                          {name}
                        </option>
                      ))}
                    </select>
                  )}
                </div>
              )}
            </FormField>
          </>
        )}
        {!fileBased && (
          <>
            <FormField label="用户名">
              {(ids) => (
                <input
                  {...ids}
                  autoComplete="username"
                  value={draft.username}
                  onChange={(event) => setDraft({ ...draft, username: event.target.value })}
                />
              )}
            </FormField>
            <FormField
              label="本次测试密码"
              hint="只保存在当前 Java 进程内存中；编辑时留空会复用已验证的密码"
            >
              {(ids) => (
                <input
                  {...ids}
                  type="password"
                  autoComplete="new-password"
                  value={draft.password}
                  onChange={(event) => setDraft({ ...draft, password: event.target.value })}
                />
              )}
            </FormField>
          </>
        )}
      </div>
      <details className="connection-advanced">
        <summary>高级选项</summary>
        <label className="setting-toggle">
          <input
            type="checkbox"
            checked={draft.readOnly}
            onChange={(event) => setDraft({ ...draft, readOnly: event.target.checked })}
          />
          <span>
            <strong>只读连接</strong>
          </span>
        </label>
        <label className="setting-toggle">
          <input
            type="checkbox"
            checked={draft.enabled}
            onChange={(event) => setDraft({ ...draft, enabled: event.target.checked })}
          />
          <span>
            <strong>启用连接</strong>
          </span>
        </label>
        <FormField label="连接 ID" hint="留空自动生成；仅小写字母、数字、点、横线或下划线">
          {(ids) => (
            <input
              {...ids}
              disabled={builtIn}
              value={draft.id}
              onChange={(event) => setDraft({ ...draft, id: event.target.value })}
              placeholder="留空自动生成"
            />
          )}
        </FormField>
      </details>
      {missing && <p className="field-gap-note">还需填写：{missing}</p>}
      <div className="button-row">
        <Button
          variant="secondary"
          busy={test.isPending}
          disabled={busy || Boolean(missing)}
          onClick={() => test.mutate()}
        >
          仅测试
        </Button>
        <Button
          busy={save.isPending}
          disabled={busy || Boolean(missing)}
          onClick={() => save.mutate()}
        >
          测试并保存
        </Button>
      </div>
      {result && (
        <Feedback
          tone={result.successful ? "success" : "warning"}
          title={result.successful ? "连接成功" : "连接失败"}
        >
          {result.message}
          {result.databaseProduct ? ` · ${result.databaseProduct} ${result.databaseVersion}` : ""}
          {result.successful && !fileBased ? " 密码已暂存，可直接保存。" : ""}
        </Feedback>
      )}
      {(save.isError || test.isError) && (
        <Feedback tone="error" title="连接操作失败">
          {(save.error ?? test.error)?.message}
        </Feedback>
      )}
    </div>
  );
}
