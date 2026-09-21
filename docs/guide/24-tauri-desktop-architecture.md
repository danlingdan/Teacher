# Tauri 桌面端架构与开发

> 适用版本：3.8.0 及后续兼容版本（随发布更新）。

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

## 本地迭代流程

日常 UI 迭代按改动类型分三档，逐档收窄重建范围。完整打包只属于发布门禁，不承担“查看效果”的职责。

`tauri dev` 依赖仓库内开发版 sidecar，而 `ui-web/src-tauri/sidecar/` 不入 Git
（见 `ui-web/.gitignore`）。干净检出或清理后需先运行一次
`.\packaging\build-v3-sidecar.ps1 -JavaHome $env:JAVA_HOME` 生成 jar 与捆绑运行时。

### 纯 UI 改动：`tauri dev` 热更新

```powershell
npm --prefix ui-web run tauri dev
```

- 该命令启动 Vite 开发服务器（端口 `1420`，`strictPort`）和真实 Tauri 桌面窗口；保存文件即热更新，
  无需重新构建或打包。首次运行需编译 Rust debug 构建与拉取 crates，之后为增量启动。
- debug 构建下 Rust 宿主自动使用上述开发版 sidecar（`ui-web/src-tauri/src/lib.rs` 的 `sidecar_root`），
  窗口连接真实 Java 核心与本地数据，不是模拟数据。
- 直接用浏览器打开 `http://localhost:1420` 会被有意拒绝（`ui-web/src/shared/ipc.ts` 抛出
  `DESKTOP_HOST_REQUIRED`），这是“不把模拟数据当真实功能”约束的一部分；UI 预览一律从 Tauri 桌面壳进入。

### UI 与 Java 同时改动：只替换开发 sidecar 的 jar

```powershell
mvn -q -DskipTests package
Copy-Item target\Teacher-<pom.xml 版本>.jar ui-web\src-tauri\sidecar\app\ -Force
```

然后重启 `tauri dev`。sidecar classpath 是 `app/*`；若版本号变化，先删除旧版本 jar，
避免同一批类在 classpath 上出现两份。仅当依赖或 JDK 变化时才运行 `packaging\build-v3-sidecar.ps1`
重建捆绑运行时，注意该脚本会整体删除并重建 sidecar 目录。

### 涉及云端服务的改动：本地验证，不指向生产

- 不要把 UI 预览指向生产 `https://api.sqlteacher.tech`，也不要在生产服务器上试改看效果。
- 服务端逻辑先在本地验证：cloud API 回环运行在 `127.0.0.1:18080`，直接以 HTTP 调用验证响应；
  只有响应契约变化时 UI 才需要同步调整。
- 云地址是 Spring 属性 `sqlteacher.cloud.base-url`（默认值见 `SqlTeacherApplicationConfig`）。
  桌面 sidecar 的启动参数由 Rust 固定，让开发版桌面连接本地服务器目前需要重新打包一个
  改了配置的开发 jar；生产路径保持固定不变。

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
