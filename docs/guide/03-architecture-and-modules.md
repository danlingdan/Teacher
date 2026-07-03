# 架构与模块设计

## 1. 总体架构原则

SQLTeacher 首版以 Java 桌面应用为核心，后续可扩展服务器端能力。架构设计必须优先保证：

- SQL 执行安全可控。
- AI 输出不可直接执行。
- UI 不直接依赖 JDBC 和模型调用细节。
- 数据库、AI、知识检索可替换。
- MVP 能快速集成和演示。

## 2. 推荐工程结构

后续可演进为 Maven 多模块结构：

```text
sqlteacher/
├─ pom.xml
├─ sqlteacher-domain/
│  ├─ entity/
│  ├─ enums/
│  ├─ valueobject/
│  └─ service/
├─ sqlteacher-application/
│  ├─ nl2sql/
│  ├─ execution/
│  ├─ teaching/
│  ├─ knowledge/
│  ├─ analytics/
│  └─ port/
├─ sqlteacher-infrastructure/
│  ├─ persistence/
│  ├─ database/
│  ├─ ai/
│  ├─ knowledge/
│  ├─ logging/
│  └─ security/
├─ sqlteacher-desktop/
│  ├─ javafx/
│  ├─ controller/
│  ├─ view/
│  ├─ css/
│  └─ launcher/
├─ sqlteacher-server/
│  ├─ controller/
│  ├─ security/
│  └─ configuration/
├─ sqlteacher-tests/
└─ packaging/
```

当前项目如果还没有拆成多模块，可以先保持标准 Maven 单模块结构，在包名上提前体现边界。

## 3. 模块职责

| 模块 | 职责 | 负责人 |
|---|---|---|
| `sqlteacher-domain` | 领域模型、枚举、值对象、领域规则 | A |
| `sqlteacher-application` | 用例编排、服务接口、业务流程 | A |
| `sqlteacher-infrastructure` | 数据库、AI、检索、持久化实现 | B、C |
| `sqlteacher-desktop` | JavaFX 桌面界面 | D |
| `sqlteacher-server` | 后续服务器版接口预留 | A |
| `sqlteacher-tests` | 测试与回归用例 | E |
| `packaging` | jlink、jpackage、安装包脚本 | E |

## 4. 包命名建议

```text
com.sqlteacher.domain
com.sqlteacher.application
com.sqlteacher.infrastructure
com.sqlteacher.desktop
com.sqlteacher.server
```

示例：

```java
package com.sqlteacher.infrastructure.database;

public final class SqliteDatabaseAdapter implements DatabaseAdapter {
}
```

## 5. 核心调用链

### SQL 执行链路

```text
JavaFX Controller
→ SqlExecutionService
→ SqlRiskAnalyzer
→ DatabaseAdapter
→ JDBC
→ SqlExecutionResult
→ JavaFX Result Table
```

Controller 不直接调用 JDBC。所有 SQL 执行必须进入 `SqlExecutionService`，否则无法统一做风险检测、超时控制、审计和教学提示。

### NL2SQL 链路

```text
JavaFX Controller
→ Nl2SqlService
→ MetadataProvider
→ AiModelProvider
→ Structured Output Parser
→ Validator
→ SqlBuilder
→ SqlRiskAnalyzer
→ SQL Preview
```

模型输出只作为候选计划，不能直接作为最终可执行 SQL。

## 6. 建议接口草案

### DatabaseAdapter

```java
public interface DatabaseAdapter {
    DatabaseType databaseType();

    ConnectionTestResult testConnection(DatabaseConnectionConfig config);

    DatabaseMetadata readMetadata(String connectionId);

    SqlExecutionResult execute(SqlExecutionRequest request);
}
```

实现要求：

- Adapter 内部处理数据库差异。
- 对外输出统一元数据模型。
- 对外输出统一执行结果模型。
- 不向 UI 暴露 JDBC 类型。

### AiModelProvider

```java
public interface AiModelProvider {
    AiHealthStatus checkHealth(AiModelConfig config);

    AiCompletionResult complete(AiCompletionRequest request);
}
```

实现要求：

- 支持超时。
- 支持模型不可用时返回明确错误。
- 不在 Provider 内执行 SQL。
- 不把 UI 状态耦合进 Provider。

### SqlRiskAnalyzer

```java
public interface SqlRiskAnalyzer {
    SqlRiskAssessment assess(SqlRiskRequest request);
}
```

实现要求：

- 输出风险等级、原因、是否允许执行、是否需要确认。
- 规则可配置。
- 覆盖 SELECT、INSERT、UPDATE、DELETE、ALTER、DROP、GRANT、REVOKE、多语句。

## 7. DTO 命名建议

| 类型 | 命名示例 |
|---|---|
| 请求对象 | `SqlExecutionRequest` |
| 响应对象 | `SqlExecutionResult` |
| 配置对象 | `DatabaseConnectionConfig` |
| AI 请求 | `Nl2SqlRequest` |
| AI 响应 | `Nl2SqlPlan` |
| 风险结果 | `SqlRiskAssessment` |
| 元数据模型 | `DatabaseMetadata` |
| 表模型 | `TableMetadata` |
| 列模型 | `ColumnMetadata` |

## 8. 数据目录建议

运行期数据建议与源代码分离：

```text
app-data/
├─ app.db
├─ demo.db
├─ logs/
├─ prompts/
├─ imports/
├─ exports/
└─ backups/
```

注意：

- 示例数据库脚本可以提交。
- 用户本地真实数据库、日志、导出文件默认不提交。
- 密码和 token 不写入明文配置。

## 9. 首版架构边界

必须做到：

- UI 不直接执行 SQL。
- AI 不直接执行 SQL。
- 外部库操作经过风险分析。
- Prompt 和业务逻辑尽量分离。
- 数据库适配器对上层隐藏方言差异。

可以暂缓：

- 完整 DDD 分层。
- 完整服务器模块。
- 插件化数据库扩展机制。
- 复杂权限系统。
