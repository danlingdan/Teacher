# 开发规范

## Java

- 使用 Java 25 构建，保持 `domain`、`application`、`infrastructure`、`desktop.bridge` 和 `server` 的依赖方向。
- 使用 Spring Context、SLF4J + Logback、UTF-8 和 4 空格缩进。
- 业务规则不放进 IPC 适配器；异常转换为有意义的领域或应用失败。
- 运行数据、数据库、日志、凭据和导入的私有内容不得提交。

## Tauri 与 React

- Tauri/React 是唯一桌面端，不新增第二套 UI 或回退启动器。
- 页面通过类型化 LocalApp IPC 调用 Java，不直接访问数据库、模型或本地秘密。
- 耗时操作必须有加载、空态、成功和可理解的失败状态。
- 高风险 SQL 必须展示 Java 返回的风险说明并走确认门。
- 页面需支持键盘操作、合理缩放和低分辨率；不把占位数据冒充真实结果。

## SQL 与 AI

- 模型输出和检索/导入内容一律视为不可信。
- 禁止模型执行 SQL 或接触 JDBC `Connection`。
- 生成 SQL 必须经过 Java 的解析、验证、构建、风险分析、预览、确认、受限执行和审计。
- AI 只提供草案和解释；确定性代码拥有掌握度、队列、干预、权限和其他权威学习状态。

## 测试

先运行最相关的 Java/Vitest/Cargo 测试，再按风险扩大。跨模块、schema、安全、累计功能或发布候选必须运行完整 `mvn test`；桌面候选还需完成前端构建、Rust 测试、E2E、性能基线和 Windows 打包验证。

## Git 与发布

- 单人项目直接在 `main` 串行开发，不使用常设 `develop` 或 PR 流程。
- 提交信息使用 `type(scope): short description`。
- 提交、推送、标签、GitHub Release 和生产部署是独立授权动作。
- 不得用破坏性 Git 命令丢弃用户修改。

更详细、具有执行优先级的规则见根目录 [AGENTS.md](../../AGENTS.md)。
