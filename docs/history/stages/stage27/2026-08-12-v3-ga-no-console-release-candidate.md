# v3.0 正式版与无控制台启动实现记录

日期：2026-08-12  
候选版本：`3.0.0`  
状态：本地正式版实现完成，未提交、未推送、未发布

## 完成范围

- 将 Maven、npm、Cargo、Tauri 和界面版本统一为 `3.0.0`，架构状态更新为 `GENERAL_AVAILABILITY`。
- 保持 Tauri/React 唯一桌面入口和 Java sidecar 权威核心，不恢复 JavaFX 或任何旧桌面链路。
- 修复 Windows Java sidecar 启动方式：设置 `CREATE_NO_WINDOW` 并关闭继承的 stderr 控制台。
- 新增 Rust 静态回归、Maven 发布合同检查和实际打包进程烟测；发布工作流在产物生成后自动验证无控制台窗口。
- 恢复中英文界面、主题/字体/密度、快捷导航、单实例、窗口状态和白名单 Windows 原生通知，对齐 2.3 的日常便利功能。
- 关闭课程混排和活动自动开始问题：通用活动先按课程隔离，预览确认后才进入真实交互。
- 将生产 mock 移至测试源树，移除设计系统、迁移状态和样例测试入口；正式 JAR/ZIP 均增加旧桌面与测试条目扫描。
- 修复增量 Maven 残留进入 sidecar 的包装缺陷：正式包装固定先执行 `mvn clean`，并在 JAR 复制后立即拒绝 JavaFX/FXML/生产 mock。
- 生成 NSIS 安装器、便携 ZIP、校验和与双 SBOM，并完成打包态 E2E 和性能门禁。

## 验证摘要

Java 全量测试、React 单测与构建、npm 审计、Rust 测试、E2E 特性编译、打包态桌面 E2E、性能门禁和
Windows 正式包构建均通过。实际启动便携版后，Java sidecar 的 `MainWindowHandle` 为 `0`；连续启动两次
仍只有一个桌面进程和一个 sidecar。JAR/ZIP 中 JavaFX、FXML、生产 mock 和测试页面条目均为 0，桌面和
sidecar 按精确 PID 退出且没有遗留进程。

完整结果与产物摘要见 [v3.0.0 验收记录](../../../acceptance/2026-08-12-v3.0.0-stage-gate.md)。

## 外部发布边界

本轮没有提交、推送、打标签、签名更新清单或创建 GitHub Release；这些操作需要单独发布授权。
