# SQLTeacher

SQLTeacher 是一款面向数据库课程教学与 SQL 练习的本地优先 Java 桌面应用。`v1.8.5` 全面增强课程知识中心：在可追溯修订与发布边界上加入 Ollama 本地嵌入、Lucene HNSW、FTS5 + 语义 RRF 混合检索、可重建索引任务、安全联网搜索、PDF/DOCX 导入、阅读进度，以及授权隔离的云端共享知识 API。

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
- 首页根据当前身份的本机练习事实生成限长、去重且可解释的下一步学习队列，并合并未完成班级任务和未读反馈。
- 教师学情看板提供授权班级干预队列，可查看逾期、连续失败和待跟进提交，标记处理状态并进入既有反馈/任务工作区。
- 支持本地课程知识文档导入、SQLite FTS5 检索、来源展示和索引删除。
- 提供升级前自动备份、手动备份、完整性校验恢复和演示库一键复原。
- 提供带应用图标的自包含 Windows EXE 安装器。
- 通过 `https://api.sqlteacher.tech` 提供账号、班级和学习同步服务，支持刷新令牌轮换与 Windows DPAPI 会话加密。
- 支持任务发布、编辑、截止、撤回和归档，以及教师班级统计和 UTF-8 CSV 导出。
- 提供角色化分组侧栏、键盘可用的首页卡片和学生 SQL 练习快捷键。
- 支持浅色、深色、跟随系统主题，以及现代中文/系统/经典字体与舒适/紧凑密度。
- 设置中心向所有角色开放；设备级数据恢复等敏感设置按独立权限限制。
- 使用 AtlantaFX 控件基线和分层语义样式，侧栏、首页与 SQL 工作区可按窗口宽度自适应。

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

脚本会生成与 `pom.xml` 版本一致的正式 EXE 安装器、便携 app-image 和 Windows x64 ZIP。WiX 3.14.1 在首次打包时下载到 `target/tools` 并校验 SHA-256，不进入 Git。推送与 Maven 版本一致的 `vX.Y.Z` 标签后，GitHub Actions 会自动测试、打包并发布这些文件。

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

云端功能仍是可选增强；服务器或网络 AI 不可用时，SQLite 练习、确定性评测、本机学习诊断和本地知识检索仍可使用。主题、字体、密度、布局和 SQL 安全模式均为当前设备设置。无限模式默认关闭，只绕过应用层 SQL 拦截，数据库权限仍然有效；AI 仍只生成草稿。正式 Windows 包内置 `https://api.sqlteacher.tech`，客户端拒绝非回环 HTTP 地址。

## 版本

- `v1.8.0`：新增 schema 8 课程知识文章与修订历史、课程/章节/知识点范围检索、身份隔离的私有草稿与显式发布、关联练习、诊断回链，以及带发送确认、引用校验和确定性降级的 AI 解释。详见 [`v1.8.0 发布说明`](docs/releases/v1.8.0.md)与[`实施计划`](docs/plans/2026-07-31-v1.8-course-knowledge-plan.md)。
- `v1.8.5`：新增 app schema 9 与 Cloud schema 3、本地向量索引、混合 RAG、安全联网来源、共享知识 API、索引运维状态和 40 问离线评测基线。详见 [`v1.8.5 发布说明`](docs/releases/v1.8.5.md)与[`实施记录`](docs/stage5/2026-08-01-v185-rag-implementation.md)。
- `v1.7.0`：新增确定性知识点掌握快照、学生下一步学习队列、云端任务/反馈合并、教师授权干预队列、身份隔离、可重算派生状态和脱敏 CSV。详见 [`v1.7.0 发布说明`](docs/releases/v1.7.0.md)与[`实施计划`](docs/plans/2026-07-30-v1.7-learning-diagnosis-loop-plan.md)。
- `v1.6.1`：兼容网络 AI 根地址和 `/v1` 地址，失败时显示真实脱敏原因并取消模拟 SQL 兜底。详见 [`v1.6.1 发布说明`](docs/releases/v1.6.1.md)。
- `v1.6.0`：统一 AI 教学工作区，包含 DPAPI Provider Profile、统一探测与任务编排、隐私预览、SQL 草稿修订、用量/历史、教师模板和 Ollama 模型切换自动卸载。详见 [`v1.6 发布说明`](docs/releases/v1.6.0.md)、[`实施计划`](docs/plans/2026-07-30-v1.6-controlled-ai-workspace-plan.md)与[`实施记录`](docs/stage5/2026-07-30-v16-controlled-ai-workspace-implementation.md)。
- `v1.5.6`：修复浅色模式与禁用状态可读性，统一本地/网络 AI，增强练习与教学表单，并增加默认关闭的 SQL 无限模式。详见 [`v1.5.6 发布说明`](docs/releases/v1.5.6.md)。
- `v1.5.5`：新增 AtlantaFX 控件基线、语义设计令牌、分层 CSS、三档响应式侧栏、自动换列首页、常驻 SQL 编辑器/结果工作区和可见键盘焦点。详见 [`v1.5.5 UI Foundation 计划`](docs/plans/2026-07-29-v1.5.5-ui-foundation-plan.md)。
- `v1.5.0`：新增角色化侧栏、三主题、字体/密度偏好、统一矢量图标、全员设置入口与设置项权限，并重构学生练习和教师教学工作台。详见 [`v1.5 UI 与易用性升级计划`](docs/plans/2026-07-28-v1.5-ui-usability-delivery-plan.md)。
- `v1.4.0`：新增课程与知识点目录、云端共享题库和不可变题目版本、任务内容快照、教师反馈、薄弱点练习建议、应用内通知及账号隔离离线缓存。详见 [`v1.4 单人迭代计划`](docs/plans/2026-07-28-v1.4-delivery-plan.md)。
- `v1.3.0`：新增完整任务提交闭环、任务学情、管理员运维、数据保留恢复和自动化证书/备份检查。
- `v1.2.1`：修复 Windows 正式包未写入云端地址的问题，并加入基于版本标签的 GitHub Actions 自动发布。
- `v1.2.0`：正式 HTTPS、安全会话持久化、同步重试与诊断、任务生命周期、班级统计导出和云端备份恢复。
- `v1.1.0`：账号登录、多教师/学生班级、学习事件同步、云端教学页和用户自带 API 的网络 AI。
- `v1.0.0`：正式 Windows 安装器、应用图标、版本与数据页、备份恢复、升级保护、键盘和低分辨率适配，以及完整交付文档。
- `v0.4.0`：新增教师学情看板、组合筛选、CSV 导出、本地课程知识检索与学习数据清理。
- `v0.3.0`：新增练习目录、练习包导入导出、隔离练习会话、确定性 SQL 判题、教师管理页、学生练习页和基础学习进度。
- `v0.1.0`：首个可演示版本。

详细开发计划和安全规范见 [`docs/plans/2026-07-30-isolated-delivery-plan.md`](docs/plans/2026-07-30-isolated-delivery-plan.md) 与 [`docs/guide/05-sql-and-ai-safety.md`](docs/guide/05-sql-and-ai-safety.md)。
