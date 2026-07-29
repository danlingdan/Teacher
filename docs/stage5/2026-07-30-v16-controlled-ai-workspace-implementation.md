# v1.6 可控 AI 教学工作区实施记录

> 日期：2026-07-30
> 基线：v1.5.6
> 当前状态：功能代码与自动化验证已完成，等待负责人进行人工 UI 验收；尚未打包或发布

## 已实现功能

- Provider Profile：支持多个 OpenAI-compatible Profile 的新增、编辑、选择、停用（切回本地）和删除；非敏感配置写入 `app-data/ai-providers.json`，每个 API Key 使用 Windows CurrentUser DPAPI 独立加密。
- 能力探测：Ollama 与 OpenAI-compatible 共用稳定探测契约；限制超时、重定向、模型数量和响应大小，并映射认证、限流、超时、无模型等脱敏错误。
- 统一任务编排：NL2SQL、SQL 错误解释、教师反馈草稿统一经过 `AiTaskService`；集中控制超时、一次有界重试、输入/输出上限、JSON 基础结构校验、取消、错误映射和元数据审计。
- 上下文策略：按任务使用类别白名单，隐藏邮箱和常见凭据，限制单项及总字符数；网络 NL2SQL 在发送前显示类别、来源、规模和删减记录并要求确认。
- SQL 草稿修订：允许基于上一版草稿提出单轮修订；修订请求使用独立 `revision-v1` Prompt，返回完整结构化草稿，并再次执行 Java SQL 风险审查。无限模式不参与 AI 审查。
- Prompt 与反馈模板：NL2SQL v4、SQL 错误解释 v1、SQL 修订 v1、反馈草稿 v1 均作为资源维护；教师反馈支持简洁、引导式、分步解释三种表达风格，确定性结论不可被 AI 改写。
- 用量与历史：设备默认限制 24,000 输入字符、8,000 输出字符、45 秒超时和每日 100 次；历史默认只保存任务类型、模型、结果、耗时和 Prompt 版本，只有用户主动收藏才保存限长草稿正文。
- UI 收敛：AI 助手集中承载来源切换、Profile、连接测试、模型发现、隐私预览、取消、修订、收藏和状态提示；网络密钥文案更新为 DPAPI 加密保存。

## 安全边界

- AI Provider 不获得 JDBC `Connection`，AI 输出不自动执行。
- 所有 AI SQL 草稿均进入同一 Java 风险分析路径；多语句、禁用语句和高风险规则不会被无限模式放宽。
- 网络错误、日志和历史不保存 API Key、完整 Prompt 或默认完整响应。
- HTTP 仅允许回环地址；公开 Provider 必须使用 HTTPS；探测不跟随重定向。
- 教师反馈仍需人工确认后发布，AI 不得改变确定性通过/未通过结论。

## 自动化验证结果

- 聚焦回归通过：Provider/Profile/DPAPI、上下文策略、统一任务、Ollama 模型选择、NL2SQL 和 Spring 接线。
- 真实 Ollama 冒烟通过：发现并选择 `qwen3.5:0.8b`，结构化任务成功；运行时偏好已写入忽略的 `app-data/selected-ai-model.txt`。
- 真实 DeepSeek 冒烟通过：`/models` 发现当前模型，DPAPI 临时 Profile、`SwitchableAiModelProvider` 和统一 `AiTaskService` 实际调用成功。真实测试默认禁用，只有显式设置 `sqlteacher.live.ai=true` 时运行。
- 模型切换释放内存通过：切换前向旧模型发送 Ollama `keep_alive: 0` 卸载请求；单元测试验证请求内容，真实测试后 `/api/ps` 只显示 0.8B 模型。
- 完整 `mvn test`：295 项，0 失败，0 错误，2 跳过。两项跳过是默认禁用的真实 Provider 冒烟，已在独立显式运行中全部通过。
- 密钥检查：本地 `.secrets/deepseek.env` 被 `.gitignore` 命中；Git 跟踪文件中精确密钥命中数为 0。

## 剩余发布前工作

1. JavaFX/FXML 资源自动测试已通过；具体视觉与交互人工验收由发布负责人执行。
2. 人工验收通过后确认版本号与发布范围，再打包输出到 `target/installer`。

本记录不宣称人工 UI 验收或安装包验证已完成。
