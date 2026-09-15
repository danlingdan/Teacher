// Settings page (v3.4.0 REF-13): extracted from PlatformPages.tsx.
// Preferences come from the shared settingsPreferencesQuery options
// (src/app/queries.ts); read-only maintenance loads use useQuery.
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { localAppRequest } from "../../shared/ipc";
import { formatInstant } from "../../shared/instant";
import { useAppVersion } from "../../shared/appVersion";
import { settingsPreferencesQuery } from "../../app/queries";
import type {
  AppRole,
  BackupSnapshot,
  SettingsEnvironment,
  SettingsPreferences,
  SettingsStorage,
  UpdateCheck,
} from "../../shared/types";
import { Button, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { Loading, Toggle } from "./shared";
import { ProblemReportDialog, ReportStatusDialog } from "./ProblemReportDialogs";
import { formatManifestVersion, useUpdateInstaller } from "../../shared/useUpdateInstaller";

type SettingsDraft = SettingsPreferences["general"] & {
  developerMode: boolean;
};

type BankPreferencesView = {
  autoCheckEnabled: boolean;
  subscribedChannels: string[];
};

type BankChannelInfo = {
  channel: string;
  bankVersion: number;
  updatedAt: string;
};

/** 题库更新设置（W4.2/W4.3）：订阅频道 + 定时检查 opt-in（默认关闭）；v3.4.1 起为可折叠面板，默认收起。 */
function BankUpdateSettings({
  preferences,
  role,
}: {
  preferences?: BankPreferencesView;
  role: AppRole;
}) {
  const client = useQueryClient();
  const toast = useToast();
  const channels = useQuery({
    queryKey: ["practice", "bank", "channels"],
    queryFn: () => localAppRequest<{ items: BankChannelInfo[] }>("practice.bank.channels"),
    staleTime: 60_000,
    retry: false,
  });
  const known = channels.data?.items.map((item) => item.channel) ?? [];
  const subscribed = preferences?.subscribedChannels ?? ["network"];
  const options = Array.from(new Set([...known, ...subscribed, "network"]));
  const save = useMutation({
    mutationFn: (next: { autoCheckEnabled: boolean; channels: string[] }) =>
      localAppRequest("settings.bank.update", {
        autoCheckEnabled: next.autoCheckEnabled,
        subscribedChannels: next.channels,
      }),
    onSuccess: () => {
      void client.invalidateQueries({
        queryKey: settingsPreferencesQuery.queryKey,
      });
      toast("success", "题库更新设置已保存");
    },
    onError: (error: Error) => toast("error", `保存失败：${error.message}`),
  });
  const [autoCheck, setAutoCheck] = useState<boolean | undefined>();
  const [selected, setSelected] = useState<string[] | undefined>();
  const autoChecked = autoCheck ?? preferences?.autoCheckEnabled ?? false;
  const toggleChannel = (channel: string) => {
    const current = selected ?? subscribed;
    setSelected(
      current.includes(channel)
        ? current.filter((item) => item !== channel)
        : [...current, channel],
    );
  };
  const dirty =
    (autoCheck !== undefined && autoCheck !== (preferences?.autoCheckEnabled ?? false)) ||
    (selected !== undefined &&
      JSON.stringify([...(selected ?? [])].sort()) !== JSON.stringify([...subscribed].sort()));
  return (
    <details className="content-card settings-panel">
      <summary>
        <span className="settings-symbol">⇩</span>
        <span>
          <strong>题库更新</strong>
          <small>订阅频道与定时检查</small>
        </span>
        <span className="settings-chevron">›</span>
      </summary>
      <div className="settings-panel-body">
        <label className="setting-toggle">
          <input
            type="checkbox"
            checked={autoChecked}
            onChange={(event) => setAutoCheck(event.target.checked)}
          />
          <span>
            <strong>定时检查题库更新</strong>
            <small>
              开启后每 6 小时在后台检查一次；发现更新仅提示，不会自动应用，也绝不打断练习。
            </small>
          </span>
        </label>
        <p className="muted">订阅的题库频道（未订阅的频道不会拉取）：</p>
        {options.map((channel) => (
          <label className="setting-toggle" key={channel}>
            <input
              type="checkbox"
              checked={(selected ?? subscribed).includes(channel)}
              onChange={() => toggleChannel(channel)}
            />
            <span>
              <strong>{channel}</strong>
              {(() => {
                const info = channels.data?.items.find((item) => item.channel === channel);
                return info ? <small>服务器版本 {info.bankVersion}</small> : null;
              })()}
            </span>
          </label>
        ))}
        {channels.isError && <p className="muted">无法获取服务器频道列表，仅显示已订阅频道。</p>}
        {role === "ADMINISTRATOR" && (
          <BankRollbackControl channels={channels.data?.items ?? []} />
        )}
        <div className="button-row">
          <Button
            disabled={!dirty || save.isPending}
            busy={save.isPending}
            onClick={() =>
              save.mutate({
                autoCheckEnabled: autoChecked,
                channels: selected ?? subscribed,
              })
            }
          >
            保存题库设置
          </Button>
          {selected && (
            <Button
              variant="secondary"
              onClick={() => {
                setSelected(undefined);
                setAutoCheck(undefined);
              }}
            >
              放弃修改
            </Button>
          )}
        </div>
      </div>
    </details>
  );
}

/** v3.4.2 LEG-14：题库回滚（服务端仅管理员可执行）——破坏性操作，确认后回滚到上一版本。 */
function BankRollbackControl({ channels }: { channels: BankChannelInfo[] }) {
  const client = useQueryClient();
  const toast = useToast();
  const [channel, setChannel] = useState("");
  const [rollbackTarget, setRollbackTarget] = useState<{ channel: string; from: number }>();
  const activeChannel = channel || channels[0]?.channel || "";
  const currentVersion = channels.find((item) => item.channel === activeChannel)?.bankVersion ?? 0;
  const hasPrevious = currentVersion > 1;
  const rollback = useMutation({
    mutationFn: (target: { channel: string; to: number }) =>
      localAppRequest<{ bankVersion: number }>("teaching.bank.rollback", {
        channel: target.channel,
        bankVersion: target.to,
      }),
    onSuccess: (value, target) => {
      setRollbackTarget(undefined);
      void client.invalidateQueries({ queryKey: ["practice", "bank", "channels"] });
      void client.invalidateQueries({ queryKey: ["practice"] });
      toast("success", `题库「${target.channel}」已回滚到版本 ${value.bankVersion}`);
    },
    onError: (error: Error) => toast("error", `题库回滚失败：${error.message}`),
  });
  if (channels.length === 0) return null;
  return (
    <>
      <div className="button-row">
        <select
          aria-label="回滚频道"
          value={activeChannel}
          onChange={(event) => setChannel(event.target.value)}
        >
          {channels.map((item) => (
            <option key={item.channel} value={item.channel}>
              {item.channel}
            </option>
          ))}
        </select>
        <Button
          variant="danger"
          disabled={!hasPrevious}
          onClick={() => setRollbackTarget({ channel: activeChannel, from: currentVersion })}
        >
          回滚上一版本
        </Button>
        {!hasPrevious && <span className="muted">当前频道没有可回滚的历史版本。</span>}
      </div>
      <Dialog
        open={Boolean(rollbackTarget)}
        title="确认回滚题库"
        onClose={() => setRollbackTarget(undefined)}
      >
        <p>
          将把题库频道「{rollbackTarget?.channel}」从版本 {rollbackTarget?.from} 回滚到版本{" "}
          {(rollbackTarget?.from ?? 1) - 1}。回滚立即生效：学生端之后拉取到的都是旧版本题目，
          正在进行的练习不受影响，但后续提交会按旧版本评判。需要已登录的云端管理员账号。
        </p>
        <div className="button-row">
          <Button variant="secondary" onClick={() => setRollbackTarget(undefined)}>
            取消
          </Button>
          <Button
            variant="danger"
            busy={rollback.isPending}
            onClick={() =>
              rollbackTarget &&
              rollback.mutate({ channel: rollbackTarget.channel, to: rollbackTarget.from - 1 })
            }
          >
            确认回滚
          </Button>
        </div>
      </Dialog>
    </>
  );
}

/** 关于（v3.4.2 LEG-1）：版本、制作团队与法律信息。名单与权属为用户确认的静态事实；隐私正文保持 Java 单一事实源，经 settings.help 拉取后在本面板内渲染。 */
// v3.4.4 AIS-2：本地/网络 AI 模型面板——恢复 v1.x 的供应商配置能力（Tauri 版首次提供）。
// 凭据只写不读：保存/测试时随请求提交，DPAPI 加密存储在 Java 侧；列表与测试响应不回传密钥。
type AiProviderView = {
  id: string;
  displayName: string;
  kind: string;
  endpoint: string;
  model: string;
  enabled: boolean;
  active: boolean;
};

type AiProviderDraft = {
  id?: string;
  displayName: string;
  endpoint: string;
  model: string;
  credential: string;
};

const emptyAiProviderDraft = (): AiProviderDraft => ({
  displayName: "",
  endpoint: "https://",
  model: "",
  credential: "",
});

function AiModelSettings() {
  const client = useQueryClient();
  const toast = useToast();
  const providers = useQuery({
    queryKey: ["ai", "providers"],
    queryFn: () =>
      localAppRequest<{ items: AiProviderView[]; activeProfileId: string }>("ai.provider.list"),
  });
  const refresh = () => void client.invalidateQueries({ queryKey: ["ai", "providers"] });
  const [editing, setEditing] = useState<AiProviderDraft>();
  const test = useMutation({
    mutationFn: (draft: AiProviderDraft) =>
      localAppRequest<{ success: boolean; message: string; models: string[] }>(
        "ai.provider.test",
        {
          displayName: draft.displayName,
          kind: "OPENAI_COMPATIBLE",
          endpoint: draft.endpoint,
          model: draft.model,
          credential: draft.credential,
        },
      ),
  });
  const save = useMutation({
    mutationFn: (draft: AiProviderDraft) =>
      localAppRequest("ai.provider.save", {
        id: draft.id,
        displayName: draft.displayName,
        kind: "OPENAI_COMPATIBLE",
        endpoint: draft.endpoint,
        model: draft.model,
        enabled: true,
        credential: draft.credential,
      }),
    onSuccess: () => {
      setEditing(undefined);
      refresh();
      toast("success", "AI 供应商已保存");
    },
    onError: (error: Error) => toast("error", `保存失败：${error.message}`),
  });
  const activate = useMutation({
    mutationFn: (id: string) => localAppRequest("ai.provider.activate", { id }),
    onSuccess: () => {
      refresh();
      toast("success", "已切换到网络 AI；网络不可用时会自动回落本地 Ollama");
    },
  });
  const deactivate = useMutation({
    mutationFn: () => localAppRequest("ai.provider.deactivate", {}),
    onSuccess: () => {
      refresh();
      toast("success", "已停用网络 AI，使用本地 Ollama");
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => localAppRequest("ai.provider.remove", { id }),
    onSuccess: () => {
      refresh();
      toast("success", "AI 供应商已删除");
    },
  });
  const active = providers.data?.items.find((item) => item.active);
  const editField = (field: keyof AiProviderDraft) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setEditing((value) => (value ? { ...value, [field]: event.target.value } : value));

  return (
    <details className="content-card settings-panel">
      <summary>
        <span className="settings-symbol">✦</span>
        <span>
          <strong>AI 模型</strong>
          <small>{active ? `网络 AI · ${active.displayName}` : "本地 Ollama"}</small>
        </span>
        <span className="settings-chevron">›</span>
      </summary>
      <div className="settings-panel-body">
        <p className="muted">
          未配置网络供应商时使用本地 Ollama（http://localhost:11434），AI 功能失败不会影响本地学习主流程；
          启用网络供应商后 AI 功能优先使用它。API Key 经 Windows DPAPI 加密存储，只写不回显。
        </p>
        {providers.data && providers.data.items.length > 0 && (
          <div className="ai-provider-list">
            {providers.data.items.map((item) => (
              <article key={item.id} className="subtle-card ai-provider-row">
                <strong>
                  {item.displayName}
                  {item.active && <span className="policy-chip"> 使用中</span>}
                </strong>
                <small>
                  {item.endpoint} · {item.model}
                </small>
                <div className="button-row">
                  {item.active ? (
                    <Button variant="secondary" busy={deactivate.isPending} onClick={() => deactivate.mutate()}>
                      停用（回本地）
                    </Button>
                  ) : (
                    <Button variant="secondary" onClick={() => activate.mutate(item.id)}>
                      启用
                    </Button>
                  )}
                  <Button
                    variant="secondary"
                    onClick={() =>
                      setEditing({
                        id: item.id,
                        displayName: item.displayName,
                        endpoint: item.endpoint,
                        model: item.model,
                        credential: "",
                      })
                    }
                  >
                    编辑
                  </Button>
                  <Button
                    variant="danger"
                    onClick={() => {
                      if (window.confirm(`删除“${item.displayName}”？其加密密钥会一并清除。`)) {
                        remove.mutate(item.id);
                      }
                    }}
                  >
                    删除
                  </Button>
                </div>
              </article>
            ))}
          </div>
        )}
        {providers.data && providers.data.items.length === 0 && (
          <p className="muted">还没有网络 AI 供应商。添加后即可在本地 Ollama 与网络 AI 之间切换。</p>
        )}
        {providers.isError && (
          <Feedback tone="error" title="读取 AI 供应商失败">
            {providers.error.message}
          </Feedback>
        )}
        <div className="button-row">
          <Button onClick={() => setEditing(emptyAiProviderDraft())}>新建网络 AI 供应商</Button>
        </div>
      </div>
      <Dialog
        open={Boolean(editing)}
        title={editing?.id ? "编辑网络 AI 供应商" : "新建网络 AI 供应商"}
        onClose={() => setEditing(undefined)}
      >
        {editing && (
          <>
            <FormField label="显示名称">
              {(ids) => <input {...ids} value={editing.displayName} onChange={editField("displayName")} />}
            </FormField>
            <FormField label="API 端点" hint="仅支持 https；本机服务可用 http://localhost">
              {(ids) => <input {...ids} value={editing.endpoint} onChange={editField("endpoint")} />}
            </FormField>
            <FormField label="模型名称">
              {(ids) => <input {...ids} value={editing.model} onChange={editField("model")} />}
            </FormField>
            <FormField
              label="API Key"
              hint={editing.id ? "已保存过密钥，留空表示沿用原值" : "只保存在本机（DPAPI 加密），不会回显"}
            >
              {(ids) => (
                <input
                  {...ids}
                  type="password"
                  autoComplete="new-password"
                  value={editing.credential}
                  onChange={editField("credential")}
                />
              )}
            </FormField>
            {test.data && (
              <Feedback tone={test.data.success ? "success" : "warning"} title={test.data.success ? "连接成功" : "连接失败"}>
                <p>{test.data.message}</p>
                {test.data.models.length > 0 && <p>可用模型：{test.data.models.join("、")}</p>}
              </Feedback>
            )}
            {test.isError && (
              <Feedback tone="error" title="测试失败">
                {test.error.message}
              </Feedback>
            )}
            <div className="button-row">
              <Button
                variant="secondary"
                busy={test.isPending}
                disabled={!editing.endpoint || !editing.model}
                onClick={() => test.mutate(editing)}
              >
                测试连接
              </Button>
              <Button
                busy={save.isPending}
                disabled={!editing.displayName || !editing.endpoint || !editing.model}
                onClick={() => save.mutate(editing)}
              >
                保存
              </Button>
            </div>
          </>
        )}
      </Dialog>
    </details>
  );
}

function AboutPanel({ onCheckUpdate, checkingUpdate, onOpenReport }: { onCheckUpdate: () => void; checkingUpdate: boolean; onOpenReport: () => void }) {
  const version = useAppVersion();
  const [privacy, setPrivacy] = useState("");
  const loadPrivacy = () => {
    localAppRequest<{ content: string }>("settings.help", { topicId: "privacy" })
      .then((value) => setPrivacy(value.content))
      .catch(() => setPrivacy("无法加载隐私说明，请稍后重试。"));
  };
  return (
    <details className="content-card settings-panel">
      <summary>
        <span className="settings-symbol">©</span>
        <span>
          <strong>关于</strong>
          <small>版本、制作团队与法律信息</small>
        </span>
        <span className="settings-chevron">›</span>
      </summary>
      <div className="settings-panel-body">
        <p>
          <strong>SQLTeacher</strong> <span className="policy-chip">{version}</span>
        </p>
        <p>制作团队：</p>
        <ul className="about-credits">
          <li>杨春蕾老师</li>
          <li>王红艺老师</li>
          <li>华佳浩（学生、主要负责人）</li>
          <li>董晓佟（学生）</li>
          <li>刘浩武（学生）</li>
          <li>刘馨潞（学生）</li>
          <li>邵思瀚（老学长）</li>
        </ul>
        <p>软件著作权归河南科技大学所有（软著申请中，登记号：待登记）。</p>
        <p className="muted">
          本项目以 Apache License 2.0 发布；第三方组件许可见安装目录 legal 文件夹中的 THIRD-PARTY-LICENSES 文件。
        </p>
        <div className="button-row">
          <Button variant="secondary" onClick={loadPrivacy}>
            隐私与数据
          </Button>
          <Button variant="secondary" busy={checkingUpdate} onClick={onCheckUpdate}>
            检查更新
          </Button>
          <Button variant="secondary" onClick={onOpenReport}>
            问题反馈
          </Button>
        </div>
        {privacy && <pre className="help-content">{privacy}</pre>}
      </div>
    </details>
  );
}

export function SettingsPage() {
  const client = useQueryClient();
  const toast = useToast();
  const query = useQuery(settingsPreferencesQuery);
  const environment = useQuery({
    queryKey: ["settings", "environment"],
    queryFn: () => localAppRequest<SettingsEnvironment>("settings.environment"),
    enabled: false,
    retry: false,
  });
  const storage = useQuery({
    queryKey: ["settings", "storage"],
    queryFn: () => localAppRequest<SettingsStorage>("settings.storage"),
    enabled: false,
    retry: false,
  });
  const [draft, setDraft] = useState<SettingsDraft | null>(null);
  const [backupsOpen, setBackupsOpen] = useState(false);
  const [resetPhrase, setResetPhrase] = useState("");
  const [restoreTarget, setRestoreTarget] = useState<BackupSnapshot>();
  const [helpContent, setHelpContent] = useState("");
  const [updateResult, setUpdateResult] = useState<UpdateCheck>();
  const [reportOpen, setReportOpen] = useState(false);
  const [reportStatusOpen, setReportStatusOpen] = useState(false);
  useEffect(() => {
    if (!query.data) return;
    // 上次离开时有未保存的更改会暂存在 sessionStorage，优先恢复，避免静默丢失。
    let stored: SettingsDraft | null = null;
    try {
      stored = JSON.parse(sessionStorage.getItem("sqlteacher.settings.draft") ?? "null");
    } catch {
      stored = null;
    }
    setDraft(
      stored ?? {
        ...query.data.general,
        developerMode: query.data.developerMode,
      },
    );
  }, [query.data]);
  const savedPreferencesEarly: SettingsDraft | undefined = query.data
    ? { ...query.data.general, developerMode: query.data.developerMode }
    : undefined;
  const dirtyEarly =
    Boolean(savedPreferencesEarly) &&
    JSON.stringify(savedPreferencesEarly) !== JSON.stringify(draft);
  // dirty 时暂存草稿；保存或放弃后清除。必须位于任何条件返回之前（Hooks 规则）。
  useEffect(() => {
    try {
      if (dirtyEarly && draft)
        sessionStorage.setItem("sqlteacher.settings.draft", JSON.stringify(draft));
      else sessionStorage.removeItem("sqlteacher.settings.draft");
    } catch {
      // ignore
    }
  }, [dirtyEarly, draft]);
  const save = useMutation({
    mutationFn: (value: SettingsDraft) => localAppRequest("settings.update", value),
    onSuccess: (_result, value) => {
      void client.invalidateQueries({
        queryKey: settingsPreferencesQuery.queryKey,
      });
      if (query.data?.general.language !== value.language) window.location.reload();
    },
    onError: (error: Error) => toast("error", `设置保存失败：${error.message}`),
  });
  const install = useMutation({
    mutationFn: (componentId: string) =>
      localAppRequest("settings.component.install", { componentId }),
    onSuccess: () => void environment.refetch(),
  });
  const cancelInstall = useMutation({
    mutationFn: (componentId: string) =>
      localAppRequest("settings.component.cancel", { componentId }),
  });
  const backups = useQuery({
    queryKey: ["settings", "backups"],
    queryFn: () => localAppRequest<{ items: BackupSnapshot[] }>("settings.backups"),
    enabled: backupsOpen,
    retry: false,
  });
  useEffect(() => {
    if (backups.isError) toast("error", `加载备份列表失败：${backups.error?.message ?? ""}`);
  }, [backups.isError, backups.error, toast]);
  const createBackup = useMutation({
    mutationFn: () => localAppRequest<BackupSnapshot>("settings.backup.create"),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ["settings", "backups"] });
    },
    onError: (error: Error) => toast("error", `创建备份失败：${error.message}`),
  });
  const restoreBackup = useMutation({
    mutationFn: () =>
      localAppRequest("settings.backup.restore", {
        backupId: restoreTarget?.id,
      }),
    onSuccess: () => setRestoreTarget(undefined),
    onError: (error: Error) => toast("error", `恢复备份失败，当前数据未被替换：${error.message}`),
  });
  const restoreDemo = useMutation({
    mutationFn: () => localAppRequest("settings.demo.restore"),
    onError: (error: Error) => toast("error", `恢复演示数据库失败：${error.message}`),
  });
  const resetLearning = useMutation({
    mutationFn: () => localAppRequest("settings.learning.reset", { confirmation: resetPhrase }),
    onSuccess: () => setResetPhrase(""),
    onError: (error: Error) => toast("error", `清空学习数据失败：${error.message}`),
  });
  const clearCache = useMutation({
    mutationFn: () => localAppRequest<{ clearedBytes: number }>("settings.cache.clear"),
    onError: (error: Error) => toast("error", `清理缓存失败：${error.message}`),
  });
  const checkUpdate = useMutation({
    mutationFn: () => localAppRequest<UpdateCheck>("settings.update.check"),
    onSuccess: setUpdateResult,
    onError: (error: Error) => toast("error", `检查更新失败：${error.message}`),
  });
  const loadHelp = useMutation({
    mutationFn: (topicId: string) =>
      localAppRequest<{ content: string }>("settings.help", { topicId }),
    onSuccess: (value) => setHelpContent(value.content),
    onError: (error: Error) => toast("error", `加载帮助失败：${error.message}`),
  });
  if (query.isPending || !draft) return <Loading label="正在读取设置" />;
  if (query.isError)
    return (
      <Feedback tone="error" title="设置不可用">
        <p>{query.error.message}</p>
      </Feedback>
    );
  const data = query.data;
  const backupItems = backups.data?.items ?? [];
  const toggle = (key: keyof SettingsDraft) =>
    setDraft((value) => (value ? { ...value, [key]: !value[key] } : value));
  const savedPreferences: SettingsDraft | undefined = query.data
    ? { ...query.data.general, developerMode: query.data.developerMode }
    : undefined;
  const dirty =
    Boolean(savedPreferences) && JSON.stringify(savedPreferences) !== JSON.stringify(draft);
  const discardChanges = () => {
    if (savedPreferences) setDraft(savedPreferences);
    try {
      sessionStorage.removeItem("sqlteacher.settings.draft");
    } catch {
      // ignore
    }
  };
  return (
    <div className="platform-workspace settings-workspace">
      <section className="settings-intro">
        <div>
          <p className="eyebrow">个性化设置</p>
          <h2>按你的方式使用 SQLTeacher</h2>
        </div>
        <div className="button-row">
          {dirty && (
            <>
              <span className="policy-chip">有未保存的更改</span>
              <Button variant="secondary" onClick={discardChanges}>
                放弃更改
              </Button>
            </>
          )}
          <Button busy={save.isPending} onClick={() => save.mutate(draft)}>
            保存偏好设置
          </Button>
        </div>
      </section>
      {save.isSuccess && <Feedback tone="success" title="设置已保存" />}
      <details className="content-card settings-panel" open>
        <summary>
          <span className="settings-symbol">Aa</span>
          <span>
            <strong>外观与使用体验</strong>
            <small>语言、主题和辅助功能</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          <div className="settings-grid">
            <FormField label="界面语言">
              {(ids) => (
                <select
                  {...ids}
                  value={draft.language}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      language: event.target.value as SettingsDraft["language"],
                    })
                  }
                >
                  <option value="zh">简体中文</option>
                  <option value="en">English</option>
                </select>
              )}
            </FormField>
            <FormField label="主题">
              {(ids) => (
                <select
                  {...ids}
                  value={draft.theme}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      theme: event.target.value as SettingsDraft["theme"],
                    })
                  }
                >
                  <option value="system">跟随系统</option>
                  <option value="light">浅色</option>
                  <option value="dark">深色</option>
                </select>
              )}
            </FormField>
            <FormField label="界面字体">
              {(ids) => (
                <select
                  {...ids}
                  value={draft.font}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      font: event.target.value as SettingsDraft["font"],
                    })
                  }
                >
                  <option value="modern">现代中文</option>
                  <option value="system">系统默认</option>
                  <option value="classic">经典清晰</option>
                </select>
              )}
            </FormField>
            <FormField label="界面密度">
              {(ids) => (
                <select
                  {...ids}
                  value={draft.density}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      density: event.target.value as SettingsDraft["density"],
                    })
                  }
                >
                  <option value="comfortable">舒适</option>
                  <option value="compact">紧凑</option>
                </select>
              )}
            </FormField>
            <FormField label="代理模式">
              {(ids) => (
                <select
                  {...ids}
                  value={draft.proxyMode}
                  onChange={(event) =>
                    setDraft({
                      ...draft,
                      proxyMode: event.target.value as SettingsDraft["proxyMode"],
                    })
                  }
                >
                  <option value="SYSTEM">跟随系统</option>
                  <option value="DIRECT">直接连接</option>
                  <option value="MANUAL">手动代理</option>
                </select>
              )}
            </FormField>
            {draft.proxyMode === "MANUAL" && (
              <>
                <FormField label="代理主机">
                  {(ids) => (
                    <input
                      {...ids}
                      value={draft.proxyHost}
                      onChange={(event) => setDraft({ ...draft, proxyHost: event.target.value })}
                    />
                  )}
                </FormField>
                <FormField label="代理端口">
                  {(ids) => (
                    <input
                      {...ids}
                      type="number"
                      min={1}
                      max={65535}
                      value={draft.proxyPort || ""}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          proxyPort: Number(event.target.value),
                        })
                      }
                    />
                  )}
                </FormField>
              </>
            )}
            <Toggle
              label="自动检查更新"
              checked={draft.automaticUpdateChecks}
              onChange={() => toggle("automaticUpdateChecks")}
            />
            <Toggle
              label="减少动态效果"
              checked={draft.reducedMotion}
              onChange={() => toggle("reducedMotion")}
            />
            <Toggle
              label="高对比度"
              checked={draft.highContrast}
              onChange={() => toggle("highContrast")}
            />
            <Toggle
              label="原生通知"
              checked={draft.nativeNotificationsEnabled}
              onChange={() => toggle("nativeNotificationsEnabled")}
            />
            <Toggle
              label="按流量计费网络"
              checked={draft.meteredNetwork}
              onChange={() => toggle("meteredNetwork")}
            />
            <Toggle
              label="临时支持日志"
              checked={draft.supportLogging}
              onChange={() => toggle("supportLogging")}
              hint="开启后 24 小时自动关闭"
            />
            <Toggle
              label="允许可信更新镜像"
              checked={draft.updateMirrorsEnabled}
              onChange={() => toggle("updateMirrorsEnabled")}
            />
            <Toggle
              label="SQL 开发者模式"
              checked={draft.developerMode}
              onChange={() => toggle("developerMode")}
              hint="减少常规确认，安全边界不变"
            />
          </div>
        </div>
      </details>
      <BankUpdateSettings preferences={query.data?.bank} role={query.data?.role} />
      <details className="content-card settings-panel">
        <summary>
          <span className="settings-symbol">⌘</span>
          <span>
            <strong>本机环境与组件</strong>
            <small>默认不检测，需要时手动运行</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          <div className="on-demand-callout">
            <div>
              <strong>手动检测</strong>
              <p>检测 Cloud、Runner、JDK、Python、Ollama、MSVC 与 WSL。</p>
            </div>
            <Button
              variant="secondary"
              busy={environment.isFetching}
              onClick={() => void environment.refetch()}
            >
              {environment.data ? "重新检测" : "开始检测"}
            </Button>
          </div>
          {environment.isError && (
            <Feedback tone="warning" title="环境检测未完成">
              {environment.error.message}
            </Feedback>
          )}
          {environment.data && (
            <>
              <p>连接状态：{environment.data.connectivity}</p>
              <div className="component-grid">
                {environment.data.components.map((item) => (
                  <article key={item.id} className="subtle-card">
                    <strong>{item.displayName}</strong>
                    <span className={`component-state state-${item.state.toLowerCase()}`}>
                      {item.state}
                    </span>
                    <small>{item.detail || item.source}</small>
                    <small>
                      {item.license}
                      {item.requiresAdministrator ? " · 需要管理员确认" : ""}
                    </small>
                    {install.isPending && install.variables === item.id ? (
                      <Button
                        variant="danger"
                        busy={cancelInstall.isPending}
                        onClick={() => cancelInstall.mutate(item.id)}
                      >
                        取消安装
                      </Button>
                    ) : (
                      item.state !== "READY" && (
                        <Button variant="secondary" onClick={() => install.mutate(item.id)}>
                          安装或修复
                        </Button>
                      )
                    )}
                  </article>
                ))}
              </div>
            </>
          )}
          {install.isError && (
            <Feedback tone="error" title="组件安装失败">
              {install.error.message}
            </Feedback>
          )}
        </div>
      </details>
      <AiModelSettings />
      <details
        className="content-card settings-panel"
        onToggle={(event) => {
          if (event.currentTarget.open && data.canMaintainLocalData) {
            void storage.refetch();
            setBackupsOpen(true);
            void client.invalidateQueries({ queryKey: ["settings", "backups"] });
          }
        }}
      >
        <summary>
          <span className="settings-symbol">↺</span>
          <span>
            <strong>备份与本地数据</strong>
            <small>空间、恢复和数据维护</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          {!data.canMaintainLocalData ? (
            <Feedback tone="warning" title="当前身份无维护权限" />
          ) : (
            <>
              {storage.isFetching && !storage.data ? (
                <Loading label="正在统计本地空间" />
              ) : (
                <p>
                  可用空间：
                  {formatBytes(storage.data?.storage.usableBytes ?? -1)}
                </p>
              )}
              <div className="button-row">
                <Button busy={createBackup.isPending} onClick={() => createBackup.mutate()}>
                  创建完整备份
                </Button>
                <Button
                  variant="secondary"
                  busy={restoreDemo.isPending}
                  onClick={() => restoreDemo.mutate()}
                >
                  恢复演示数据库
                </Button>
                <Button
                  variant="secondary"
                  busy={clearCache.isPending}
                  onClick={() => clearCache.mutate()}
                >
                  清理可重建缓存
                </Button>
              </div>
              {clearCache.data && (
                <Feedback tone="success" title="缓存已清理">
                  释放 {formatBytes(clearCache.data.clearedBytes)}。
                </Feedback>
              )}
              <ul className="plain-list">
                {backupItems.map((item) => (
                  <li key={item.id}>
                    <strong>{formatInstant(item.createdAt)}</strong>
                    <span>
                      {formatBytes(item.sizeBytes)}
                      {item.automatic ? " · 自动" : ""}
                    </span>
                    <Button variant="secondary" onClick={() => setRestoreTarget(item)}>
                      恢复
                    </Button>
                  </li>
                ))}
              </ul>
              <FormField label="清空学习数据确认词" hint="输入 RESET LEARNING DATA 解锁按钮">
                {(ids) => (
                  <input
                    {...ids}
                    value={resetPhrase}
                    onChange={(event) => setResetPhrase(event.target.value)}
                  />
                )}
              </FormField>
              <Button
                variant="danger"
                disabled={resetPhrase !== "RESET LEARNING DATA" || resetLearning.isPending}
                onClick={() => resetLearning.mutate()}
              >
                清空学习数据
              </Button>
              {resetLearning.isSuccess && (
                <Feedback tone="success" title="学习数据已重置">
                  已清除全部学习记录。
                </Feedback>
              )}
            </>
          )}
        </div>
      </details>
      <details className="content-card settings-panel">
        <summary>
          <span className="settings-symbol">?</span>
          <span>
            <strong>更新、通知与帮助</strong>
            <small>版本检查和使用支持</small>
          </span>
          <span className="settings-chevron">›</span>
        </summary>
        <div className="settings-panel-body">
          <div className="button-row">
            <Button busy={checkUpdate.isPending} onClick={() => checkUpdate.mutate()}>
              检查更新
            </Button>
            <Button variant="secondary" onClick={() => setReportOpen(true)}>
              问题反馈
            </Button>
            <Button variant="secondary" onClick={() => setReportStatusOpen(true)}>
              查询反馈进度
            </Button>
            {data.notifications.some((item) => !item.read) && (
              <Button
                variant="secondary"
                onClick={() =>
                  localAppRequest("settings.notifications.read")
                    .then(() =>
                      client.invalidateQueries({
                        queryKey: settingsPreferencesQuery.queryKey,
                      }),
                    )
                    .catch((error: Error) => toast("error", `标记已读失败：${error.message}`))
                }
              >
                全部标为已读
              </Button>
            )}
          </div>
          {updateResult && (
            <Feedback
              tone={updateResult.status === "FAILED" ? "warning" : "info"}
              title={`更新状态：${updateResult.status}`}
            >
              {updateResult.message}
            </Feedback>
          )}
          {/* v3.5.0 反馈：手动检查发现新版本时，直接在这里提供下载/安装闭环，
              不再只有一行状态文本（此前用户"没下载的地方"）。 */}
          {updateResult?.status === "AVAILABLE" && updateResult.available && (
            <UpdateInstallPanel
              version={updateResult.available.version}
              releaseNotesUrl={updateResult.available.releaseNotesUrl}
            />
          )}
          <ul className="plain-list">
            {data.notifications.map((item) => (
              <li key={item.id}>
                <strong>{item.title}</strong>
                <span>{item.message}</span>
              </li>
            ))}
          </ul>
          <div className="button-row">
            {data.helpTopics.map((topic) => (
              <Button
                key={topic}
                variant="secondary"
                busy={loadHelp.isPending && loadHelp.variables === topic}
                onClick={() => loadHelp.mutate(topic)}
              >
                {helpTopicLabel(topic)}
              </Button>
            ))}
          </div>
          {helpContent && <pre className="help-content">{helpContent}</pre>}
        </div>
      </details>
      <AboutPanel
        onCheckUpdate={() => checkUpdate.mutate()}
        checkingUpdate={checkUpdate.isPending}
        onOpenReport={() => setReportOpen(true)}
      />
      <ProblemReportDialog open={reportOpen} onClose={() => setReportOpen(false)} />
      <ReportStatusDialog open={reportStatusOpen} onClose={() => setReportStatusOpen(false)} />
      <Dialog
        open={Boolean(restoreTarget)}
        title="恢复应用备份"
        onClose={() => setRestoreTarget(undefined)}
      >
        <p>
          恢复会覆盖当前应用数据库。确认恢复{" "}
          {restoreTarget ? formatInstant(restoreTarget.createdAt) : ""} 的备份？
        </p>
        <div className="button-row">
          <Button variant="secondary" onClick={() => setRestoreTarget(undefined)}>
            取消
          </Button>
          <Button
            variant="danger"
            busy={restoreBackup.isPending}
            onClick={() => restoreBackup.mutate()}
          >
            确认恢复
          </Button>
        </div>
      </Dialog>
    </div>
  );
}

function formatBytes(value: number) {
  if (!Number.isFinite(value) || value < 0) return "未知";
  if (value < 1024) return `${value} B`;
  const units = ["KiB", "MiB", "GiB", "TiB"];
  let size = value / 1024;
  let unit = 0;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(size >= 10 ? 1 : 2)} ${units[unit]}`;
}
function helpTopicLabel(value: string) {
  return (
    (
      {
        "getting-started": "快速入门",
        updates: "更新说明",
        feedback: "反馈与建议",
        privacy: "隐私与数据",
        shortcuts: "快捷键",
        troubleshooting: "故障排查",
      } as Record<string, string>
    )[value] ?? value
  );
}

/** v3.5.0 反馈：设置页内的更新下载/安装闭环（与启动弹窗共用 useUpdateInstaller）。 */
function UpdateInstallPanel({
  version,
  releaseNotesUrl,
}: {
  version?: string | { major: number; minor: number; patch: number };
  releaseNotesUrl?: string;
}) {
  const installer = useUpdateInstaller();
  const downloading = installer.phase === "downloading";
  const launching = installer.phase === "launching";
  const ready = installer.phase === "ready";
  const busy = downloading || launching;
  const versionLabel = version ? formatManifestVersion(version) : "";
  return (
    <section className="update-install-panel">
      <Feedback tone="info" title={`新版本 SQLTeacher ${versionLabel} 可安装`}>
        <p>下载官方安装包后即可启动升级；{releaseNotesUrl ? "发布说明见 Release 页面。" : "安装过程中应用保持运行。"}</p>
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
        </div>
      </Feedback>
    </section>
  );
}
