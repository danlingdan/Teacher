# SQLTeacher Demo 基线验收与冻结记录

> 验收日期：2026-07-21
>
> 开发分支：`main`
>
> 源代码基线：`e669805`
>
> 基线版本：`v0.1.0` Demo
>
> 用途：作为 Demo 后单人扩展开发的回归、数据库迁移和发布比较基线。

## 1. 冻结结论

当前 Demo 的自动化测试、无界面应用启动验证、Windows app-image 生成和打包后启动检查均通过，可以作为后续扩展开发基线。

本次冻结不表示 MySQL、题库、学情看板、知识检索或正式安装器已经完成。上述能力继续按照 `docs/plans/2026-07-21-post-demo-expansion-plan.md` 实施。

## 2. 已冻结能力

- SQLite 应用库和演示库自动初始化。
- `student` 演示表、表结构浏览和数据预览。
- 单条 SQL 风险分析、执行、结果映射、错误展示和最大行数限制。
- 多语句拦截、禁止级 SQL 拦截和高风险操作确认。
- Ollama 状态检测、本地模型选择和结构化 NL2SQL 草案。
- AI 草案经过 Java 侧安全复检，只能预览或复制，不能直接执行。
- SQL 执行、风险拦截和 AI 生成学习事件记录与基础查询。
- JavaFX 首页、SQL 练习页、AI 助手页和表结构页真实服务接入。
- Windows app-image 生成。

## 3. Application 接口快照

基线包含以下 application 服务接口：

```text
AiModelProvider
AiModelSelectionService
AiStatusService
AppConfigurationService
DatabaseInitializationService
ApplicationExceptionMapper
LearningEventQueryService
LearningEventRecorder
LearningEventService
SqlExecutionService
DatabaseMetadataService
Nl2SqlSafetyService
Nl2SqlService
SqlRiskAnalysisService
```

后续允许按扩展计划新增接口。修改以上接口时，必须记录兼容影响并更新对应测试；不得让 application 层依赖 JavaFX、JDBC 或 Ollama 具体实现。

## 4. 数据库结构快照

### `app.db`

```sql
create table app_event (
    id integer primary key autoincrement,
    event_type text not null,
    message text,
    created_at text not null default current_timestamp
);

create table learning_events (
    id integer primary key autoincrement,
    event_type text not null,
    occurred_at text not null,
    connection_id text not null,
    successful integer not null,
    attributes text,
    created_at text not null default current_timestamp
);
```

### `demo.db`

```sql
create table student (
    id integer primary key,
    name text not null,
    score integer not null
);
```

初始化数据包含 `Alice / 92` 和 `Bob / 76`。当前基线尚无 schema 版本表；这是扩展计划中的下一个基础设施任务。

## 5. 验证记录

### 5.1 Maven 全量测试

```powershell
mvn test
```

结果：

- 构建成功。
- 测试数：160。
- 失败：0。
- 错误：0。
- 跳过：0。
- 完成时间：2026-07-21 01:35（Asia/Shanghai）。

测试日志中的异常堆栈和 Ollama 警告来自失败/降级测试用例的预期路径，不代表测试失败。

### 5.2 无界面应用验证

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

结果：

- Spring application context 启动通过。
- `app-data/app.db` 初始化通过。
- `app-data/demo.db` 初始化通过。
- 本机 Ollama 服务可访问。
- 已安装并选中模型：`qwen3.5:9b`。

### 5.3 app-image 生成

```powershell
.\packaging\package-stage1.ps1
```

结果：成功生成：

```text
target\installer\SQLTeacherStage1
```

### 5.4 打包后启动检查

启动 `SQLTeacherStage1.exe` 后观察 5 秒，进程保持运行，未提前退出；检查结束后主动停止测试进程。

本次生成的 launcher SHA-256：

```text
01AC9E61C5F6B28B18598752CCAAA76F070C24FA335D5A5A7060DC3BD040D026
```

该哈希只用于本机本次基线验证。`target/` 为生成目录，不提交到 Git。

## 6. 未覆盖与已知限制

- 本次未执行完整 JavaFX 人工交互走查；页面交互结论沿用已有联调记录，后续 UI 变更仍需重新人工验收。
- app-image 已验证生成和短时启动，尚未验证正式安装、升级和卸载。
- MySQL Connector/J 当前只完成驱动可用性验证，没有真实 MySQL 连接管理和查询闭环。
- 当前没有题库、确定性评测、教师学情看板和知识文档检索页面。
- `app.db` 尚未引入 schema 版本与迁移机制。
- Ollama 生成质量依赖本地模型；Ollama 不可用时只能使用手写 SQL 和降级提示。
- SQL 风险检测是 Java 规则分析器，不是完整 SQL 语法解析器；必须持续维护安全回归集。

## 7. 后续变更保护规则

- 每完成一个独立功能，在 `main` 上完成测试和文档更新后直接提交。
- 不使用 PR 流程；提交信息继续使用 `type(scope): short description` 格式。
- 一个提交只包含一个完整、可回归的功能或修复，不提交明显半成品。
- 任何回归导致本记录中的核心闭环失效时，先恢复基线能力，再继续扩展。
- 数据库结构变更必须提供从本基线升级的自动化测试。
