// v3.10.0 HAJ-8：网络 AI 供应商预设（傻瓜化配置的前端 UX 清单）。
// 预设只是填表助手：选择后自动填入端点与默认模型，端点与模型名始终可自由编辑；
// 「发现模型」/「测试连接」会列出端点真实可用的模型供模型名称框下拉选择。厂商调整端点/模型名时
// 请同步更新本表（数值于 2026-10 对照各厂商开放文档核对；端点需满足 Java 侧 AiProviderProfile
// 校验：必须 https，http 仅限本机回环，且不含 query/userinfo/fragment）。
export type AiProviderPresetGroup = "cn" | "global" | "local";

export type AiProviderPreset = {
  id: string;
  displayName: string; // auto-fills 显示名称
  endpoint: string;
  defaultModel: string; // auto-fills 模型名称；测试连接后会列出真实可用模型可点选
  group: AiProviderPresetGroup;
  keyUrl: string; // 申请 API Key 的入口（文本提示展示）
  note?: string; // 特殊说明（显示在模型名称字段 hint）
};

export const AI_PROVIDER_PRESETS: ReadonlyArray<AiProviderPreset> = Object.freeze([
  // 国内厂商
  {
    id: "deepseek",
    displayName: "DeepSeek",
    endpoint: "https://api.deepseek.com/v1",
    defaultModel: "deepseek-chat",
    group: "cn",
    keyUrl: "https://platform.deepseek.com/api_keys",
  },
  {
    id: "zhipu",
    displayName: "智谱 GLM",
    endpoint: "https://open.bigmodel.cn/api/paas/v4",
    defaultModel: "glm-5.3",
    group: "cn",
    keyUrl: "https://bigmodel.cn/usercenter/proj-mgmt/apikeys",
  },
  {
    id: "moonshot",
    displayName: "Kimi（月之暗面）",
    endpoint: "https://api.moonshot.cn/v1",
    defaultModel: "kimi-latest",
    group: "cn",
    keyUrl: "https://platform.kimi.com",
  },
  {
    id: "qwen",
    displayName: "通义千问（阿里云百炼）",
    endpoint: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    defaultModel: "qwen-plus",
    group: "cn",
    keyUrl: "https://bailian.console.aliyun.com/?apiKey=1",
    note: "已开通业务空间专属域名的用户可把端点换成 {WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
  },
  {
    id: "doubao",
    displayName: "豆包（火山方舟）",
    endpoint: "https://ark.cn-beijing.volces.com/api/v3",
    defaultModel: "doubao-pro-32k",
    group: "cn",
    keyUrl: "https://console.volcengine.com/ark",
    note: "也可填入方舟推理接入点 ep-xxxx 作为模型名；可用模型以方舟控制台为准",
  },
  {
    id: "hunyuan",
    displayName: "腾讯混元",
    endpoint: "https://api.hunyuan.cloud.tencent.com/v1",
    defaultModel: "hunyuan-turbos-latest",
    group: "cn",
    keyUrl: "https://console.cloud.tencent.com/hunyuan/api-key",
  },
  {
    id: "qianfan",
    displayName: "百度千帆",
    endpoint: "https://qianfan.baidubce.com/v2",
    defaultModel: "ernie-4.5-turbo-128k",
    group: "cn",
    keyUrl: "https://console.bce.baidu.com/iam/#/iam/apikey/list",
  },
  {
    id: "spark",
    displayName: "讯飞星火",
    endpoint: "https://spark-api-open.xf-yun.com/v1",
    defaultModel: "4.0Ultra",
    group: "cn",
    keyUrl: "https://console.xfyun.cn",
    note: "星火 X1/X2 等新模型与鉴权格式见讯飞控制台说明",
  },
  {
    id: "minimax",
    displayName: "MiniMax",
    endpoint: "https://api.minimaxi.com/v1",
    defaultModel: "MiniMax-M3",
    group: "cn",
    keyUrl: "https://platform.minimaxi.com",
  },
  {
    id: "stepfun",
    displayName: "阶跃星辰",
    endpoint: "https://api.stepfun.com/v1",
    defaultModel: "step-3.7-flash",
    group: "cn",
    keyUrl: "https://platform.stepfun.com",
  },
  // 国际厂商
  {
    id: "openai",
    displayName: "OpenAI",
    endpoint: "https://api.openai.com/v1",
    defaultModel: "gpt-5",
    group: "global",
    keyUrl: "https://platform.openai.com/api-keys",
  },
  {
    id: "anthropic",
    displayName: "Anthropic Claude",
    endpoint: "https://api.anthropic.com/v1",
    defaultModel: "claude-sonnet-4-5",
    group: "global",
    keyUrl: "https://console.anthropic.com",
    note: "走 Anthropic 官方 OpenAI SDK 兼容层",
  },
  {
    id: "gemini",
    displayName: "Google Gemini",
    endpoint: "https://generativelanguage.googleapis.com/v1beta/openai/",
    defaultModel: "gemini-2.5-flash",
    group: "global",
    keyUrl: "https://aistudio.google.com/apikey",
  },
  {
    id: "xai",
    displayName: "xAI Grok",
    endpoint: "https://api.x.ai/v1",
    defaultModel: "grok-4",
    group: "global",
    keyUrl: "https://console.x.ai",
  },
  {
    id: "mistral",
    displayName: "Mistral",
    endpoint: "https://api.mistral.ai/v1",
    defaultModel: "mistral-large-latest",
    group: "global",
    keyUrl: "https://console.mistral.ai",
  },
  // 聚合与本地
  {
    id: "openrouter",
    displayName: "OpenRouter（聚合）",
    endpoint: "https://openrouter.ai/api/v1",
    defaultModel: "openrouter/auto",
    group: "local",
    keyUrl: "https://openrouter.ai/settings/keys",
  },
  {
    id: "groq",
    displayName: "Groq（快速推理）",
    endpoint: "https://api.groq.com/openai/v1",
    defaultModel: "llama-3.3-70b-versatile",
    group: "local",
    keyUrl: "https://console.groq.com/keys",
  },
  {
    id: "siliconflow",
    displayName: "硅基流动",
    endpoint: "https://api.siliconflow.cn/v1",
    defaultModel: "deepseek-ai/DeepSeek-V3",
    group: "local",
    keyUrl: "https://cloud.siliconflow.cn/account/ak",
  },
  {
    id: "lmstudio",
    displayName: "LM Studio（本机）",
    endpoint: "http://localhost:1234/v1",
    defaultModel: "local-model",
    group: "local",
    keyUrl: "",
    note: "本机服务无需密钥；模型名填 LM Studio 已加载的模型",
  },
]);

export const AI_PROVIDER_PRESET_GROUPS: ReadonlyArray<{ id: AiProviderPresetGroup; label: string }> = Object.freeze([
  { id: "cn", label: "国内厂商" },
  { id: "global", label: "国际厂商" },
  { id: "local", label: "聚合与本地" },
]);

// 「自定义（OpenAI 兼容）」不是清单成员，只作为下拉里的独立选项。
export const CUSTOM_PRESET_ID = "custom";

/** 编辑既有供应商时按端点回选预设；匹配不到（含自定义端点）返回 undefined。 */
export function matchPresetByEndpoint(endpoint: string): AiProviderPreset | undefined {
  const normalized = endpoint.trim();
  return AI_PROVIDER_PRESETS.find((preset) => preset.endpoint === normalized);
}
