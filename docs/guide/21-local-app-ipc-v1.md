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

## 变更门禁

修改合同必须同时更新 JSON Schema/manifest、Java `LocalAppContract`、Rust 白名单、TypeScript 方法联合类型及三端测试。
涉及打包态行为时还需运行 WebdriverIO 桌面 E2E；测试插件通过 Cargo `e2e` feature 隔离，不进入正式构建。
