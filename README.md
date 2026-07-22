# SQLTeacher

SQLTeacher 是一款面向数据库课程教学与 SQL 练习的本地优先 Java 桌面应用。`v1.2.1` 提供正式 HTTPS 云服务、安全会话恢复、可靠学习记录同步、教学任务生命周期、班级统计与审计导出，同时保留完整离线教学闭环。

## 当前功能

- 自动初始化 SQLite 演示数据库与应用数据库。
- 输入并执行 SQL，展示查询列、结果行和可读错误信息。
- 默认限制查询结果数量，阻止多语句和禁止级 SQL。
- 对需要确认的高风险 SQL 显示明确提示。
- 浏览演示数据库的表和字段结构。
- 检测 Ollama 已安装模型，自动选择可用模型，也可在 AI 助手页手动切换。
- 将自然语言转换为 SQL 草案，并在 Java 侧重新执行安全检查。
- 记录 SQL 执行、AI 生成和风险拦截事件。
- 提供教师学情看板，可按日期、题目、知识点和错误类型筛选并导出 UTF-8 CSV。
- 支持本地课程知识文档导入、SQLite FTS5 检索、来源展示和索引删除。
- 提供升级前自动备份、手动备份、完整性校验恢复和演示库一键复原。
- 提供带应用图标的自包含 Windows EXE 安装器。
- 通过 `https://api.sqlteacher.tech` 提供账号、班级和学习同步服务，支持刷新令牌轮换与 Windows DPAPI 会话加密。
- 支持任务发布、编辑、截止、撤回和归档，以及教师班级统计和 UTF-8 CSV 导出。

## 安全原则

- AI 只生成 SQL 草案，不能直接执行 SQL 或访问 JDBC 连接。
- 所有 AI 草案必须通过 Java 侧风险分析。
- 多语句默认禁止。
- `DROP DATABASE`、`GRANT`、`REVOKE` 等语句默认禁止。
- AI 草案必须由用户预览，并复制到 SQL 练习页后才能执行。

## 环境要求

- Windows 10/11（安装包运行无需另装 JDK）
- 源码开发：JDK 21、Maven 3.9 或更新版本
- 可选：本地 [Ollama](https://ollama.com/) 与任一已安装模型

项目默认连接 `http://localhost:11434`。AI 助手会读取 Ollama 的本地模型列表；配置模型不存在时，会自动选择检测到的第一个模型。Windows 正式版用户数据保存在 `%LOCALAPPDATA%\SQLTeacher`，首次启动会迁移工作目录中的旧 `app-data`。

## 本地运行

```powershell
mvn test
mvn javafx:run
```

无图形环境时可以运行命令行验证：

```powershell
mvn -q compile exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

## Windows 打包

```powershell
.\packaging\package-stage1.ps1
```

生成的 app-image 位于：

```text
target\installer\SQLTeacher
```

脚本会生成 `SQLTeacher-1.2.1.exe` 正式安装器、便携 app-image 和 `SQLTeacher-1.2.1-windows-x64.zip`。WiX 3.14.1 在首次打包时下载到 `target/tools` 并校验 SHA-256，不进入 Git。推送与 Maven 版本一致的 `vX.Y.Z` 标签后，GitHub Actions 会自动测试、打包并发布这些文件。

## 项目结构

```text
src/main/java/com/sqlteacher/application     应用服务与稳定契约
src/main/java/com/sqlteacher/domain          领域异常与规则
src/main/java/com/sqlteacher/infrastructure  SQLite、Ollama、Spring 等适配器
src/main/java/com/sqlteacher/desktop         JavaFX 界面与控制器
src/main/resources                           FXML、CSS 与应用配置
src/test                                     单元、集成和回归测试
packaging                                    app-image 打包脚本
docs                                         架构、计划、验收与软著材料
```

## 当前边界

`v1.2.1` 的云端功能仍是可选增强；服务器或网络 AI 不可用时，SQLite 练习、确定性评测和本地知识检索仍可使用。正式 Windows 包内置 `https://api.sqlteacher.tech`；客户端拒绝非回环 HTTP 地址，正式教学数据只能经域名与 HTTPS 传输。

## 版本

- `v1.2.1`：修复 Windows 正式包未写入云端地址的问题，并加入基于版本标签的 GitHub Actions 自动发布。
- `v1.2.0`：正式 HTTPS、安全会话持久化、同步重试与诊断、任务生命周期、班级统计导出和云端备份恢复。
- `v1.1.0`：账号登录、多教师/学生班级、学习事件同步、云端教学页和用户自带 API 的网络 AI。
- `v1.0.0`：正式 Windows 安装器、应用图标、版本与数据页、备份恢复、升级保护、键盘和低分辨率适配，以及完整交付文档。
- `v0.4.0`：新增教师学情看板、组合筛选、CSV 导出、本地课程知识检索与学习数据清理。
- `v0.3.0`：新增练习目录、练习包导入导出、隔离练习会话、确定性 SQL 判题、教师管理页、学生练习页和基础学习进度。
- `v0.1.0`：首个可演示版本。

详细开发计划和安全规范见 [`docs/plans/2026-07-30-isolated-delivery-plan.md`](docs/plans/2026-07-30-isolated-delivery-plan.md) 与 [`docs/guide/05-sql-and-ai-safety.md`](docs/guide/05-sql-and-ai-safety.md)。
