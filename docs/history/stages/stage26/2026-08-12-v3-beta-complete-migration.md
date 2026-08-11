# v3 Beta 完全迁移记录

日期：2026-08-12  
候选版本：`3.0.0-beta.2`  
状态：本地 Beta 实现完成，未提交、未推送、未发布

## 完成范围

- Tauri 2 + React 19 成为唯一桌面端，删除旧 JavaFX 启动器、控制器、ViewModel、组件、FXML、JavaFX CSS、资源包与相关测试。
- Maven 移除 JavaFX、AtlantaFX、ControlsFX、RichTextFX、Ikonli 和 JavaFX Maven 插件。
- 删除 jpackage/WiX 旧打包脚本与旧图标，CI 和本地候选统一使用 `package-v3.ps1` 的 Tauri/NSIS 路径。
- LocalApp IPC 冻结为 Beta 合同；Java bridge 继续委托 application/domain/infrastructure，不把业务规则搬进 Rust 或 React。
- 首页摘要改为本地确定性诊断，不再因残留会话隐式触发云端请求；同步只在云端工作区显式执行。
- 现役架构、协作、开发、安装和发布指南已切换到 Tauri-only；早期版本材料仅作为历史证据保留。

## 安全边界

此次迁移未改变 schema 语义和 SQL/AI 强制链路。模型仍不能执行 SQL 或接触 JDBC；SQL 仍经过 Java 的解析、验证、风险分析、确认、超时、结果限制和审计。

## 验证摘要

- Java 25：`mvn test`，436 项，0 失败，0 错误，2 项 live 测试跳过。
- React：Vitest 4 个文件、10 项测试通过；生产构建通过；`npm audit` 为 0 个漏洞。
- Rust：3 项合同测试通过；`cargo check --features e2e` 通过。
- 桌面 E2E：Beta.2 完整桌面场景通过，架构状态页人工截图走查通过。
- 性能：sidecar 到健康 297.92 ms，核心初始化 1992.65 ms，IPC p95 0.46 ms，首页摘要 46.86 ms，知识样例 1.59 ms，退出 319.05 ms；门禁通过。

完整门禁见 [Beta.2 验收记录](../../../acceptance/2026-08-12-v3-beta2-stage-gate.md)。

## 未代替的外部门禁

本地完成不等于公开发布。干净 Windows 10 安装/升级/卸载矩阵、两小时稳态、签名更新清单、GitHub Actions 和公开 Release 仍需在获得发布授权后执行。
