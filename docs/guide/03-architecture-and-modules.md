# 架构与模块设计

> 适用版本：3.8.0 及后续兼容版本（随发布更新）。

## 当前架构

SQLTeacher 3 采用单一 Tauri/React 桌面端和 Java 25 核心。仓库保持 Maven 单模块 Java 工程，Web/Tauri 工程位于 `ui-web/`。

```text
Tauri/React UI
    -> LocalApp IPC v1
    -> com.sqlteacher.desktop.bridge
    -> com.sqlteacher.application
    -> com.sqlteacher.domain

com.sqlteacher.infrastructure -> application/domain
com.sqlteacher.server         -> application/domain/infrastructure
```

## 目录职责

| 路径 | 职责 |
| --- | --- |
| `ui-web/src/` | React 页面、交互状态和 IPC 客户端 |
| `ui-web/src-tauri/` | Tauri 宿主、sidecar 生命周期和 Windows 打包 |
| `desktop.bridge` | 本地 IPC 合同与 Java 应用服务适配 |
| `application` | 用例合同、编排和 DTO |
| `domain` | 领域模型、值对象和确定性规则 |
| `infrastructure` | JDBC、SQLite、HTTP、Ollama、文件、检索与配置 |
| `server` | Cloud API、认证和服务端持久化 |

## 不可跨越的边界

- React 和 Tauri 不直接访问 JDBC，也不拥有学习判定或 SQL 风险策略。
- Java bridge 只做 IPC 适配，不复制 application/domain 的业务规则。
- 模型不能执行 SQL 或接触 JDBC 连接。
- 所有 SQL 走结构化解析、验证、风险分析、预览、确认、限时限量执行和审计链路。
- 学习记录同步在登录云端账号后默认自动进行（约每 5 分钟，v3.7.0 起默认开启，用户可在「班级与云端」页暂停上传或关闭自动同步）；除同步与用户显式触发的云端操作外，本地首页和离线学习不得隐式联网。

## 运行与打包

- 开发：`ui-web` 使用 Vite，Tauri 启动 Java sidecar。
- 自动化：Java 用 Maven，前端用 Vitest，宿主用 Cargo，桌面主流程用 WebDriver E2E。
- Windows 交付：`packaging/package-v3.ps1` 生成 NSIS 安装包和便携 ZIP，并输出 Java/Rust 双 SBOM 与校验和。

IPC 方法和错误语义见 [LocalApp IPC v1](21-local-app-ipc-v1.md)，正式版冻结边界见 [Tauri 桌面端架构与开发](24-tauri-desktop-architecture.md)。
