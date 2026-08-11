# SQLTeacher v3.0.0-beta.2 阶段门禁

日期：2026-08-12  
结论：Beta 完全迁移的本地门禁通过；当前工作树尚未提交或公开发布。

## 自动化结果

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| Java 全量 | `mvn test`（JDK 25.0.3） | 436 通过口径；0 失败、0 错误、2 live 跳过 |
| 前端单测 | `npm test -- --run` | 4 个文件、10 项通过 |
| 依赖审计 | `npm audit --audit-level=moderate` | 0 个漏洞 |
| 前端生产构建 | `npm run build` | 通过 |
| Rust 合同 | `cargo test` | 3 项通过 |
| E2E 特性编译 | `cargo check --features e2e` | 通过 |
| 桌面 E2E | `ui-web/scripts/build-e2e.ps1` | 1 个桌面场景通过 |
| 性能 | `ui-web/scripts/measure-sidecar.ps1` | 全部门槛通过 |
| Windows 候选 | `packaging/package-v3.ps1` | EXE、便携 ZIP、校验和、Java/npm 双 SBOM 生成成功 |
| 便携烟测 | 启动 `SQLTeacher.exe` 并按精确 PID 停止 | 窗口创建成功 |

## 架构核验

- `pom.xml` 不含 JavaFX 或旧 UI 依赖。
- `src/main/java/com/sqlteacher/desktop` 只保留 `bridge`。
- 旧 FXML、JavaFX CSS、i18n 桌面资源和旧打包脚本不存在。
- CI 只构建 Tauri/NSIS，且校验 Java 与 Rust 双 SBOM。
- Beta 架构状态页返回 `TAURI_ONLY`、`BETA_COMPLETE` 和 `legacyDesktopRemoved=true`。
- 便携 ZIP 共 229 项，未发现 JavaFX 依赖、运行数据库、`.env`、`.secrets` 或 `app-data`。

## 本地产物

- `SQLTeacher-3.0.0-beta.2.exe`：SHA-256 `2b723b746ebb8b41c5ec6fb0647514332f7100906f07a7298050884a5eda3cf2`
- `SQLTeacher-3.0.0-beta.2-windows-x64.zip`：SHA-256 `ad7d0614a677ed28097f47e00ce22480478c47e48ce3993683dcb5207edafaf1`
- `sqlteacher-sbom.json` 与 `sqlteacher-ui-sbom.json` 均通过 JSON 解析。

## 性能证据

| 指标 | 结果 |
| --- | ---: |
| sidecar 启动到健康 | 297.92 ms |
| Java 核心初始化 | 1992.65 ms |
| IPC p95 | 0.46 ms |
| 首页摘要 | 46.86 ms |
| 知识样例 | 1.59 ms |
| Java 工作集 | 163.28 MB |
| 退出 | 319.05 ms |

## 已知限制

- Vite 对 Mermaid/Monaco 相关大 chunk 继续发出警告，但生产构建成功；该项进入后续加载优化，不通过放宽阈值隐藏。
- jsdom 不提供原生 canvas，单测打印降级提示；真实桌面 E2E 已覆盖渲染主流程。
- 尚未执行干净 Windows 10 虚拟机矩阵、两小时稳态、签名更新清单或公开发布核验。
