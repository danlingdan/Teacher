# SQLite 连接 Profile 持久化

## 1. 交付范围

本功能实现 `ConnectionManagementService` 的 SQLite 持久化，并将 `app.db` schema 从版本 1 升级到版本 2。

已实现：

- 自动创建并维护内置 `demo` 和 `app` Profile。
- 保存、更新、列表和按 ID 查询用户 Profile。
- 选择当前连接，并跨服务实例保留选择。
- 禁用或删除当前连接时自动回退到 `demo`。
- 禁止覆盖或删除内置 Profile。
- Spring 真实服务装配。
- 连接配置存储失败的安全中文错误映射。

本功能不保存密码，也不建立真实 MySQL 会话。密码仍只允许作为后续连接测试服务的瞬时输入。

## 2. Schema 版本 2

新增 `connection_profiles`：

```text
id
display_name
dialect
sqlite_path | host + port + database_name + username
read_only
enabled
built_in
created_at
updated_at
```

数据库约束保证：

- 方言只能是 `SQLITE`、`MYSQL` 或 `MARIADB`。
- SQLite 只能保存文件路径，不能同时保存服务器字段。
- MySQL/MariaDB 必须具有 host、合法 port、database name 和 username。
- 布尔字段只能使用 0 或 1。
- 表中不存在 password 列。

新增单行表 `connection_selection`，保存当前选择的连接 ID。引用有效性由服务事务维护，避免在 SQLite 外键开关不同的运行环境中产生不一致行为。

## 3. 内置连接

| ID | 名称 | 目标 | 规则 |
|---|---|---|---|
| `demo` | SQLite 演示数据库 | `DatabaseConfiguration.demoDatabasePath` | 默认当前连接，可执行经风险确认的教学 SQL |
| `app` | 应用数据库 | `DatabaseConfiguration.appDatabasePath` | 只读标记，不能由用户覆盖或删除 |

每次服务操作都会幂等校正内置 Profile 的路径和属性，因此应用数据目录变化后不会保留失效的旧内置路径。

## 4. 事务与回退规则

- 每次管理操作使用独立 JDBC 连接和事务。
- 保存、删除、选择与回退在同一事务完成。
- 当前 Profile 不存在或已禁用时，自动选择 `demo`。
- 不能选择禁用的 Profile。
- 删除不存在的 Profile 返回输入错误，不静默成功。
- JDBC 细节通过 `CONNECTION_PROFILE_FAILED` 转换为安全应用异常。
- 返回的 Profile 列表为不可变副本；内置列表固定将 `demo` 放在 `app` 前。

## 5. 增量验证

```powershell
mvn -q "-Dtest=JdbcConnectionManagementServiceTest,SqliteSchemaMigratorTest,SqliteAppDatabaseInitializerTest,ConnectionContractTest,SqlTeacherApplicationConfigTest,DefaultApplicationExceptionMapperTest" test
```

结果：相关增量测试通过，0 失败。

覆盖场景：

- 空库和旧库迁移到 schema 版本 2。
- 内置 Profile 初始化和默认选择。
- MySQL 非敏感 Profile 保存与重新加载。
- 当前连接选择持久化。
- 禁用、删除后的 `demo` 回退。
- 内置连接覆盖与删除拦截。
- Spring Bean 装配和安全错误提示。
