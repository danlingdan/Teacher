# 2026-09-21 工具链扫描断链崩溃问题诊断

- 制定日期：2026-09-21
- 触发反馈：用户（Windows，SQLTeacher 3.7.0）反馈注册账号失败，界面报"账号操作失败 / Local application operation failed"
- 影响版本：自 v2.3.0 引入该扫描实现起（`git log --follow` 显示 `WindowsToolchainDiscovery.java` 唯一改动为 release 2.3.0），v2.3.0–v3.8.0 含当前 `main` 的全部 Windows 版本
- 状态：已诊断，修复未实施（已挂[积压清单](backlog.md)）；临时解决已提供给反馈用户，并经其后续日志验证有效（删除断链后上下文初始化恢复正常）

## 现象

用户在注册页填写邮箱、显示名称与密码后点击"创建账号并登录"，界面报错"账号操作失败 / Local application operation failed"。

用户机器的 sidecar 日志显示，失败远不止注册：`session.current`、`home.summary`、`data.connections`、`settings.preferences` 等全部本地 IPC 请求都以同一异常失败。即该机器上**整个应用不可用**，注册只是用户最先碰到的界面。

## 根因

因果链（引用当前 `main` 行号，与用户 3.7.0 构建一致）：

1. 任一 IPC 请求触发 `DefaultLocalAppApi.context()`（`DefaultLocalAppApi.java:114`）懒创建 Spring 上下文；
2. 上下文刷新装配 `localCodeRunner` bean（`SystemServiceConfig.java:40`），`WindowsLocalIdeCodeRunner` 构造时执行 `Toolchains.detect()`（`WindowsLocalIdeCodeRunner.java:272`）；
3. `WindowsToolchainDiscovery.javaCompiler()` 扫描 `C:\Program Files\Java` 等目录查找 `javac.exe`。打包侧 `jlink` 运行时只含 `java.se,jdk.crypto.ec,jdk.unsupported`（`build-v3-sidecar.ps1:92`），没有 `jdk.compiler`，故探测第一候选（自带运行时的 `javac.exe`）必不命中，扫描必然落到系统目录；
4. 遍历走到悬空目录联接 `C:\Program Files\Java\latest\jdk-25`（Oracle JDK 卸载残留，链接目标已删除）时，`Files.find` 的惰性遍历在迭代期抛出 `UncheckedIOException: NoSuchFileException`；
5. `addDiscovered`（`WindowsToolchainDiscovery.java:130-136`）只捕获 `IOException | SecurityException`，`UncheckedIOException` 属 RuntimeException 直接穿透 → `localCodeRunner` 创建失败 → 整个 Spring 上下文刷新取消；
6. 协议层兜底把未知异常统一映射为 `LOCAL_APP_FAILURE`："Local application operation failed"（`LocalAppProtocolServer.java:154`），前端显示为"账号操作失败"。

日志中伴随的 `exercise_sessions` 表不存在告警是上下文刷新取消后的关停清理噪音，非独立问题。

## 触发条件与证据

- 机器存在指向已删除目标的目录联接（junction/符号链接），且位于扫描根目录下。本次实锤：用户资源管理器打开 `C:\Program Files\Java\latest`，内含 `jdk-25` 文件夹，点击报"位置不可用……可能已移动或删除"——Oracle JDK 安装器在 `latest` 下维护指向最新 JDK 的链接，卸载 JDK 后残留断链。
- 三份用户日志（`sidecar.log`、`sidecar.old.log`、`sqlteacher-sidecar.log`，2026-09-21 微信转发）中同一堆栈反复出现，与上述第 3–5 步完全吻合。
- 触发不要求用户做任何特殊操作：应用启动后首个 IPC 请求即崩，且每次请求重试均复现。

## 影响范围

- 触发面：卸载过 Oracle JDK 且残留断链的 Windows 机器（含 `latest` 之外的任何断链条目落在六个扫描根下）。
- 后果分级：工具链"找不到"本身是设计内正常结果（`WindowsLocalIdeCodeRunner.java:58` 按 `javac == null` 判定语言不支持，UI 提示即可）；本缺陷把"找不到"变成"扫描过程抛异常"，进而拖死与代码运行完全无关的全部功能（注册、登录、学情、设置），违反"非核心能力失败不得阻塞核心本地流程"的项目原则。
- 版本面：v2.3.0–v3.8.0 全部受影响，当前无任何分支已修复。

## 临时解决（已提供给反馈用户）

删除断链后重启应用即可恢复：

```cmd
rmdir "C:\Program Files\Java\latest\jdk-25"
```

（右键删除该残留链接等效；删除的只是悬空链接本身，不会误删数据。）残留的空 `latest` 文件夹无害。

## 修复方案（待实施）

最小完整修法，集中于 `WindowsToolchainDiscovery.addDiscovered`：

1. 将 `Files.find(...).forEach` 改为 `Files.walkFileTree`，visitor 的 `visitFileFailed` 返回 `CONTINUE`——断链、无权限等不可读条目直接跳过，继续遍历其余目录与扫描根；
2. 保留现有 `IOException | SecurityException` 捕获并补 `UncheckedIOException` 兜底（双保险）；
3. 补针对性测试（Windows 下以 junction 构造断链场景；如无法稳定构造需说明验证限制），并跑相关 focused 测试。

可选后续（独立决策，不并入最小修复）：

- 打包时将 `jdk.compiler` 加入 `jlink` 模块集，使自带运行时可直接编译 Java 练习，普通用户无需安装 JDK（代价：安装包增大约几十 MB）；
- 评估非核心 bean（如 `localCodeRunner`）装配失败时降级而非取消整个上下文的架构方案。

## 证据清单

- 用户日志三份（微信转发至本机会话目录，含机器路径与 requestId，未发现凭据；**不入库**）；
- 用户资源管理器截图（确认 `latest\jdk-25` 断链"位置不可用"）；
- 本仓库代码定位见上文行号引用，均可复核。

返回[迭代计划索引](README.md) · [文档中心](../README.md)。
