# SQLTeacher

SQLTeacher 是一款面向数据库课程教学与 SQL 练习的 Java 桌面应用。当前版本聚焦可离线演示的 SQLite 教学闭环，提供 SQL 执行、风险检测、表结构浏览、本地 Ollama 辅助生成 SQL，以及学习事件记录。

## 当前功能

- 自动初始化 SQLite 演示数据库与应用数据库。
- 输入并执行 SQL，展示查询列、结果行和可读错误信息。
- 默认限制查询结果数量，阻止多语句和禁止级 SQL。
- 对需要确认的高风险 SQL 显示明确提示。
- 浏览演示数据库的表和字段结构。
- 检测 Ollama 已安装模型，自动选择可用模型，也可在 AI 助手页手动切换。
- 将自然语言转换为 SQL 草案，并在 Java 侧重新执行安全检查。
- 记录 SQL 执行、AI 生成和风险拦截事件。

## 安全原则

- AI 只生成 SQL 草案，不能直接执行 SQL 或访问 JDBC 连接。
- 所有 AI 草案必须通过 Java 侧风险分析。
- 多语句默认禁止。
- `DROP DATABASE`、`GRANT`、`REVOKE` 等语句默认禁止。
- AI 草案必须由用户预览，并复制到 SQL 练习页后才能执行。

## 环境要求

- Windows 10/11（当前 app-image 发布目标）
- JDK 21 或更新版本
- Maven 3.9 或更新版本
- 可选：本地 [Ollama](https://ollama.com/) 与任一已安装模型

项目默认连接 `http://localhost:11434`。AI 助手会读取 Ollama 的本地模型列表；配置模型不存在时，会自动选择检测到的第一个模型。用户选择保存在 `app-data/selected-ai-model.txt`。

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

运行 `SQLTeacher.exe` 即可启动。可上传发布的压缩包位于 `target\installer\SQLTeacher-0.3.0-windows-x64.zip`。运行期数据库和模型偏好写入 `app-data/`，该目录不会提交到 Git。

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

首个版本以 SQLite 演示闭环为主。MySQL 完整接入、知识库检索、学情看板和安装程序不属于当前发布范围。Ollama 的生成质量取决于用户安装的本地模型；服务不可用或没有模型时，应用应安全降级而不是直接执行未经验证的内容。

## 版本

- `v0.3.0`：新增练习目录、练习包导入导出、隔离练习会话、确定性 SQL 判题、教师管理页、学生练习页和基础学习进度。
- `v0.1.0`：首个可演示版本。

详细开发计划和安全规范见 [`docs/plans/2026-07-30-isolated-delivery-plan.md`](docs/plans/2026-07-30-isolated-delivery-plan.md) 与 [`docs/guide/05-sql-and-ai-safety.md`](docs/guide/05-sql-and-ai-safety.md)。
