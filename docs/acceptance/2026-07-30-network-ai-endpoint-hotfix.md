# 网络 AI 根地址兼容与失败提示修复记录

日期：2026-07-30

## 问题

- OpenAI 兼容 Provider 可以使用根地址完成模型发现，但生成请求直接发送到根地址。
- DeepSeek Profile 保存为 `https://api.deepseek.com` 时，生成请求返回 HTTP 404。
- AI 请求失败后，助手页面统一显示“服务离线”并填入模拟 SQL，掩盖了真实失败类型。

## 修复

- 网络 AI 调用自动将 Provider 根地址或 `/v1` 地址规范化到 `/chat/completions`。
- 已填写完整 `/chat/completions` 的地址保持不变，`/models` 地址会转换为对应生成地址。
- 失败时保留服务层返回的脱敏错误说明，不再生成或展示模拟 SQL。
- 提示标题调整为“AI 请求未完成”，避免把认证、限流、路径或格式错误误报为离线。
- 网络 Provider 停用状态可跨重启保持，不会自动覆盖用户选择的本地 Ollama。
- HTTP 4xx 不再重试；Provider URL 禁止嵌入凭据、查询参数和片段。
- 云端 API 异常只记录操作名与异常类型，不再直接打印潜在敏感堆栈。

## 验证

- `mvn -q "-Dtest=OpenAiCompatibleModelProviderTest,AiAssistantControllerTest" test`：通过。
- 使用本机 `.secrets` 凭据和 DeepSeek 根地址执行真实网络 Provider 测试：通过。
- `mvn -q dependency:analyze`：通过。
- `mvn test`：300 项，失败 0，错误 0，跳过 2 项按默认配置禁用的实时测试。
- 本地 Ollama `qwen3.5:0.8b` 与 DeepSeek 根地址实时调用：通过。
- `packaging/package-stage1.ps1`：通过；最终 EXE、Windows x64 ZIP、app-image 和双条目校验清单位于 `target/installer`。
- ZIP 共 323 个条目，未发现 `.secrets`、`app-data`、`target`、数据库或日志文件；候选源码中的已知真实 API Key 精确命中数为 0。
- 本地 SHA-256：ZIP `e4f97303dcdf12a02dc50daef6327c030b6192eb848a9eb60bc0124f921089bd`；EXE `b034cb622c668ff78762387e3e5b9e206776b6ee43171cf27a0c21c82899d60a`。
- 未执行 JavaFX UI 自动化；界面显示由用户在安装包中手工确认。
