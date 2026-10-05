import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Button, ConfirmDialog, Dialog, Feedback, FormField, useToast } from "../../shared/ui";
import { localAppRequest } from "../../shared/ipc";
import { aiEngineStatusQuery } from "../../app/queries";
import type {
  AiModelSelectionState,
  AiProviderModelDiscovery,
  AiProviderView,
} from "../../shared/types";
import { AI_PROVIDER_PRESETS, AI_PROVIDER_PRESET_GROUPS, CUSTOM_PRESET_ID, matchPresetByEndpoint } from "./aiProviderPresets";

// v3.10.0 HAJ-1/2（修订）：AI 引擎面板自设置页抽出，常驻顶栏独立的「AI 引擎」弹层
// （TopbarAiEngine，全页面可达）。
// 能力与 v3.4.4 AIS-2 一致：供应商增删改、激活/停用、连通性测试；凭据只写不回显（DPAPI 在 Java 侧）。
// HAJ-3/4 新增：引擎状态行（ai.engine.status）与本地已装模型选择（ai.model.list/select，
// 包装既有 OllamaModelSelectionService，切换时旧模型卸载语义由 Java 侧保持）。
// HAJ-8 新增：网络 AI 供应商表单内置厂商预设（aiProviderPresets.ts），选择即填端点与默认模型。
// HAJ-9 新增：「发现模型」（ai.provider.models）——拉取供应商真实模型列表，模型名称改为
// 输入 + datalist 下拉选择；测试连接与发现模型的结果共用同一候选列表，不静默覆盖已填模型名。
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

export default function AiEnginePanel() {
  const client = useQueryClient();
  const toast = useToast();
  const engine = useQuery(aiEngineStatusQuery);
  const models = useQuery({
    queryKey: ["ai", "models"],
    queryFn: () => localAppRequest<AiModelSelectionState>("ai.model.list"),
    staleTime: 30_000,
  });
  const providers = useQuery({
    queryKey: ["ai", "providers"],
    queryFn: () =>
      localAppRequest<{ items: AiProviderView[]; activeProfileId: string }>("ai.provider.list"),
  });
  const refreshAll = () => {
    void client.invalidateQueries({ queryKey: ["ai", "providers"] });
    void client.invalidateQueries({ queryKey: ["ai", "engine-status"] });
    void client.invalidateQueries({ queryKey: ["ai", "models"] });
  };
  const [editing, setEditing] = useState<AiProviderDraft>();
  // v3.10.0 HAJ-8：服务商预设选择。新建默认“自定义（OpenAI 兼容）”，编辑既有供应商时按端点回选。
  const [presetId, setPresetId] = useState<string>(CUSTOM_PRESET_ID);
  // 选预设即填显示名称/端点/默认模型（不动已输入的密钥）；选“自定义”保留当前值，仅清除预设派生的提示。
  const selectPreset = (nextPresetId: string) => {
    setPresetId(nextPresetId);
    const preset = AI_PROVIDER_PRESETS.find((candidate) => candidate.id === nextPresetId);
    if (!preset) return;
    setEditing((value) =>
      value
        ? { ...value, displayName: preset.displayName, endpoint: preset.endpoint, model: preset.defaultModel }
        : value,
    );
  };
  const openCreate = () => {
    setPresetId(CUSTOM_PRESET_ID);
    setDiscoveredModels([]);
    setEditing(emptyAiProviderDraft());
  };
  const openEdit = (item: AiProviderView) => {
    setPresetId(matchPresetByEndpoint(item.endpoint)?.id ?? CUSTOM_PRESET_ID);
    setDiscoveredModels([]);
    setEditing({
      id: item.id,
      displayName: item.displayName,
      endpoint: item.endpoint,
      model: item.model,
      credential: "",
    });
  };
  // 当前生效的预设（非“自定义”时才有）：派生申请密钥提示与模型名称备注。
  const activePreset =
    editing && presetId !== CUSTOM_PRESET_ID
      ? AI_PROVIDER_PRESETS.find((preset) => preset.id === presetId)
      : undefined;
  // v3.8.0 UIX-2：删除 AI 供应商的应用内确认状态。
  const [pendingRemove, setPendingRemove] = useState<{ id: string; displayName: string } | null>(null);
  // v3.10.0 HAJ-9：测试连接与「发现模型」共用的模型候选列表（去重合并），喂给 datalist 下拉。
  const [discoveredModels, setDiscoveredModels] = useState<string[]>([]);
  const test = useMutation({
    mutationFn: (draft: AiProviderDraft) =>
      localAppRequest<AiProviderModelDiscovery>("ai.provider.test", {
        displayName: draft.displayName,
        kind: "OPENAI_COMPATIBLE",
        endpoint: draft.endpoint,
        model: draft.model,
        credential: draft.credential,
      }),
    onSuccess: (data) =>
      setDiscoveredModels((current) => Array.from(new Set([...current, ...data.models]))),
  });
  // v3.10.0 HAJ-9：发现模型——只依赖端点与密钥；编辑已存供应商且密钥留空时，
  // Java 侧借用其已保存的 DPAPI 密钥，无需重新粘贴。
  const discover = useMutation({
    mutationFn: (draft: AiProviderDraft) =>
      localAppRequest<AiProviderModelDiscovery>("ai.provider.models", {
        id: draft.id ?? "",
        endpoint: draft.endpoint,
        credential: draft.credential,
      }),
    onSuccess: (data) =>
      setDiscoveredModels((current) => Array.from(new Set([...current, ...data.models]))),
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
      refreshAll();
      toast("success", "AI 供应商已保存");
    },
    onError: (error: Error) => toast("error", `保存失败：${error.message}`),
  });
  const activate = useMutation({
    mutationFn: (id: string) => localAppRequest("ai.provider.activate", { id }),
    onSuccess: () => {
      refreshAll();
      // v3.10.0 HAJ-6：原文案宣称"网络不可用时会自动回落本地"，与实际切换语义不符。
      toast("success", "已切换到网络 AI；停用后回到本地 Ollama");
    },
  });
  const deactivate = useMutation({
    mutationFn: () => localAppRequest("ai.provider.deactivate", {}),
    onSuccess: () => {
      refreshAll();
      toast("success", "已停用网络 AI，使用本地 Ollama");
    },
  });
  const remove = useMutation({
    mutationFn: (id: string) => localAppRequest("ai.provider.remove", { id }),
    onSuccess: () => {
      refreshAll();
      toast("success", "AI 供应商已删除");
    },
  });
  const selectModel = useMutation({
    mutationFn: (model: string) => localAppRequest<AiModelSelectionState>("ai.model.select", { model }),
    onSuccess: () => {
      refreshAll();
      toast("success", "已切换本地模型");
    },
    onError: (error: Error) => toast("error", `切换本地模型失败：${error.message}`),
  });
  const editField = (field: keyof AiProviderDraft) => (event: React.ChangeEvent<HTMLInputElement>) =>
    setEditing((value) => (value ? { ...value, [field]: event.target.value } : value));

  // 当前引擎描述：网络供应商生效时以其为准；否则看本地 Ollama 可达性与选定模型。
  const engineLabel = engine.data
    ? engine.data.networkActive
      ? `网络 AI · ${engine.data.displayName}`
      : engine.data.ollamaAvailable
        ? `本地 Ollama${engine.data.selectedModel ? ` · ${engine.data.selectedModel}` : ""}`
        : "本地 Ollama（不可达）"
    : "";
  const engineOk = Boolean(engine.data && (engine.data.networkActive || engine.data.ollamaAvailable));

  return (
    <div className="ai-engine-section">
      <div className="ai-engine-heading">
        <strong>AI 引擎</strong>
        {engine.data && (
          <small className={engineOk ? "engine-state-ok" : "engine-state-bad"}>
            当前引擎：{engineLabel}
          </small>
        )}
        {engine.isError && <small className="engine-state-bad">引擎状态读取失败</small>}
      </div>
      <div className="ai-engine-models">
        {models.data && models.data.installedModels.length > 0 ? (
          <>
            <label className="muted" htmlFor="ai-local-model">本地模型</label>
            <select
              id="ai-local-model"
              value={models.data.selectedModel || models.data.installedModels[0]}
              disabled={selectModel.isPending}
              onChange={(event) => selectModel.mutate(event.target.value)}
            >
              {models.data.installedModels.map((model) => (
                <option key={model} value={model}>{model}</option>
              ))}
            </select>
            <Button variant="secondary" busy={models.isFetching} onClick={() => void models.refetch()}>
              刷新
            </Button>
          </>
        ) : (
          <small className="muted">
            {models.isError
              ? "本地模型列表读取失败，可稍后重试或改用网络 AI。"
              : "未检测到本地模型：可安装 Ollama 模型后刷新，或添加网络 AI 供应商。"}
          </small>
        )}
      </div>
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
                  onClick={() => openEdit(item)}
                >
                  编辑
                </Button>
                <Button variant="danger" onClick={() => setPendingRemove({ id: item.id, displayName: item.displayName })}>
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
        <Button onClick={openCreate}>新建网络 AI 供应商</Button>
      </div>
      <Dialog
        open={Boolean(editing)}
        title={editing?.id ? "编辑网络 AI 供应商" : "新建网络 AI 供应商"}
        onClose={() => setEditing(undefined)}
      >
        {editing && (
          <>
            {/* v3.10.0 HAJ-8：服务商预设置顶——选择即填端点与默认模型，只需粘贴 API Key。 */}
            <FormField label="服务商预设">
              {(ids) => (
                <select {...ids} value={presetId} onChange={(event) => selectPreset(event.target.value)}>
                  {AI_PROVIDER_PRESET_GROUPS.map((group) => (
                    <optgroup key={group.id} label={group.label}>
                      {AI_PROVIDER_PRESETS
                        .filter((preset) => preset.group === group.id)
                        .map((preset) => (
                          <option key={preset.id} value={preset.id}>{preset.displayName}</option>
                        ))}
                    </optgroup>
                  ))}
                  <option value={CUSTOM_PRESET_ID}>自定义（OpenAI 兼容）</option>
                </select>
              )}
            </FormField>
            <FormField label="显示名称">
              {(ids) => <input {...ids} value={editing.displayName} onChange={editField("displayName")} />}
            </FormField>
            <FormField label="API 端点" hint="仅支持 https；本机服务可用 http://localhost">
              {(ids) => <input {...ids} value={editing.endpoint} onChange={editField("endpoint")} />}
            </FormField>
            <FormField label="模型名称" hint={activePreset?.note}>
              {(ids) => (
                <div className="ai-engine-model-row">
                  <input
                    {...ids}
                    list="ai-provider-model-options"
                    value={editing.model}
                    onChange={editField("model")}
                  />
                  <Button
                    variant="secondary"
                    busy={discover.isPending}
                    disabled={!editing.endpoint}
                    onClick={() => discover.mutate(editing)}
                  >
                    发现模型
                  </Button>
                </div>
              )}
            </FormField>
            {/* v3.10.0 HAJ-9：测试/发现到的真实模型进入同一 datalist，输入框可直接下拉选择或手填。 */}
            <datalist id="ai-provider-model-options">
              {discoveredModels.map((model) => (
                <option key={model} value={model} />
              ))}
            </datalist>
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
            {activePreset?.keyUrl && (
              <small className="muted">申请 API Key：{activePreset.keyUrl}</small>
            )}
            {test.data && (
              <Feedback tone={test.data.success ? "success" : "warning"} title={test.data.success ? "连接成功" : "连接失败"}>
                <p>{test.data.message}</p>
              </Feedback>
            )}
            {/* v3.10.0 HAJ-9：发现模型结果。success:false 时 Java 侧已给出分类文案（认证失败/限流/超时等）。 */}
            {discover.data && discover.data.success && discover.data.models.length > 0 && (
              <Feedback tone="info" title="发现模型">
                <p>{`发现 ${discover.data.models.length} 个模型，可在模型名称框下拉选择`}</p>
              </Feedback>
            )}
            {discover.data && !(discover.data.success && discover.data.models.length > 0) && (
              <Feedback tone="warning" title="发现模型失败">
                <p>{discover.data.message}</p>
              </Feedback>
            )}
            {discover.isError && (
              <Feedback tone="error" title="发现模型失败">
                {discover.error.message}
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
      <ConfirmDialog
        open={pendingRemove !== null}
        title="删除 AI 供应商"
        danger
        message={`删除“${pendingRemove?.displayName ?? ""}”？其加密密钥会一并清除。`}
        confirmLabel="删除"
        onConfirm={() => {
          if (pendingRemove) remove.mutate(pendingRemove.id);
          setPendingRemove(null);
        }}
        onClose={() => setPendingRemove(null)}
      />
    </div>
  );
}
