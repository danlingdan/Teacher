# LocalApp IPC v1 合同

> 适用代码版本：`3.0.0-alpha.2` 起；Alpha.3—7 在 v1 内仅做加法扩展

`3.0-v1` 是 Tauri/Rust 与 Java 本地核心之间第一版冻结合同。权威机器文件位于
[`contracts/ipc/v1/`](../../contracts/ipc/v1/)；Java、Rust 和 TypeScript 都有同步测试，不能只修改单侧常量。

## 信封与兼容策略

- 请求固定包含 `requestId`、`method`、对象类型 `params` 与 `contractVersion`；未知请求字段被拒绝。
- 响应只允许 `result` 或结构化 `error` 二选一；前端必须忽略 v1 内新增的响应字段。
- v1 内只允许增加可选响应字段、方法和事件；删除、改名、改变字段含义或收紧既有输入，需要新的合同版本。
- 单条请求上限 1 MiB，并发上限 8，默认超时 30 秒。Rust 与 Java 均执行白名单和边界检查。

## 错误与事件

错误统一包含稳定 `code`、面向用户的 `message` 与 `retryable`。调用方根据 `code` 决策，不解析文案。
当前事件信封开放 `progress`、`import.progress`、`runner.progress` 和 `ai.delta`；未知事件由前端忽略。取消使用 `system.cancel`，目标请求通过
`targetRequestId` 指定。

## 方法：v3.10.0 加法扩展

v3.10.0 沿用“v1 内仅做加法扩展”策略新增以下方法（供顶栏「AI 引擎」弹层消费），四端同步点
（`contracts/ipc/v1/manifest.json`、Java `LocalAppContract`、Rust 白名单、TypeScript 方法联合类型）
均已收录：

- `ai.engine.status` — 无参数；返回只读的当前 AI 引擎状态快照：
  `{networkActive, activeKind, displayName, selectedModel, ollamaAvailable, ollamaModelCount, message}`，
  其中 `activeKind` 为 `OLLAMA` 或 `OPENAI_COMPATIBLE`。响应不含端点 URL 与任何凭据材料。
- `ai.model.list` — 无参数；触发一次本地 Ollama 模型刷新探测，返回
  `{installedModels, selectedModel, message}`。
- `ai.model.select` — 参数 `{model}`（1–120 字符，必须是已安装的本地模型）；返回结构与
  `ai.model.list` 相同。切换会卸载上一个选中的模型（既有 `OllamaModelSelectionService` 语义，
  v3.10.0 起首次暴露给 UI）。
- `ai.provider.models` — 参数 `{endpoint (1–512), id? (已存供应商 id), credential?}`；行为与
  `ai.provider.test` 同源：以端点+密钥（`id` 给出且 `credential` 为空时借用该已存供应商的 DPAPI
  解密密钥，借用副本按单次使用契约在调用后清零）探测 `{endpoint}/models`，返回
  `{success, message, models[], errorCode?}`。它是既有有界模型发现以独立动作形式暴露给
  新建/编辑供应商表单的「发现模型」按钮，响应不含任何密钥材料。

同一批次内 `data.connection.test` 新增可选参数 `connectionId`：提供时按 id 加载已存连接配置进行测试
（未知 id 报 “Database connection was not found”），无需重建整份表单字段；省略时行为不变（表单字段 +
可选密码，密码为空时回退到本进程会话凭据）。

## 变更门禁

修改合同必须同时更新 JSON Schema/manifest、Java `LocalAppContract`、Rust 白名单、TypeScript 方法联合类型及三端测试。
涉及打包态行为时还需运行 WebdriverIO 桌面 E2E；测试插件通过 Cargo `e2e` feature 隔离，不进入正式构建。
