# v3 Tauri-only 架构与开发

> 适用版本：`3.0.0` 及后续 v3 版本

## 唯一桌面运行路径

SQLTeacher 3 只有一套桌面 UI：Tauri 2 启动 React/TypeScript 工作区，同时管理 Java 25 sidecar 生命周期。
仓库不再包含 JavaFX 启动器、FXML、JavaFX CSS、旧 ViewModel/控制器或 jpackage/WiX 打包脚本。

```text
React workspaces
  -> Tauri command and capability boundary
  -> versioned stdin/stdout IPC
  -> Java LocalAppHost
  -> application/domain services
  -> JDBC, files, AI, Runner and cloud adapters
```

Rust 只负责进程、窗口和受限 IPC 路由，不承载业务规则。Java sidecar 继续拥有 SQL 风险分析、确认令牌、
执行限制、审计、确定性评价、角色复核和数据迁移。删除 JavaFX 不改变这些安全边界。

## 源码布局

- `ui-web/src/`：七个工作区、设计系统、IPC 客户端与前端状态。
- `ui-web/src-tauri/`：Tauri 容器、Capability、sidecar 启停与打包图标。
- `contracts/ipc/v1/`：跨 Java/TypeScript/Rust 的机器可读白名单。
- `src/main/java/com/sqlteacher/desktop/bridge/`：Java sidecar 宿主与 DTO 映射。
- `src/main/java/com/sqlteacher/application|domain|infrastructure|server/`：保留的权威核心。

## 正式版边界

- v3 不再新增第二桌面入口或 UI 技术栈。
- `3.0-v1` IPC 主合同冻结；新增能力采用向后兼容字段或显式新合同版本。
- WebView 不直接访问 JDBC、Spring Bean、任意文件、令牌或系统命令。
- SQL 与 AI 输出继续视为不可信；React 确认框不能替代 Java 强制检查。
- 发布只使用 `packaging/package-v3.ps1`，输出 NSIS EXE、便携 ZIP、Java/UI SBOM 和校验和。

## 验证

```powershell
mvn test
npm --prefix ui-web test
npm --prefix ui-web audit
npm --prefix ui-web run build
cargo test --manifest-path ui-web/src-tauri/Cargo.toml
.\packaging\package-v3.ps1 -JavaHome $env:JAVA_HOME
```

打包态桌面回归使用 `npm --prefix ui-web run test:e2e:build` 与 `npm --prefix ui-web run test:e2e`。
发布门禁还会运行 `packaging/test-v3-no-console.ps1`，验证 Java sidecar 没有可见窗口。
