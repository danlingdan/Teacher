# Profile 感知的 JDBC 连接与连接测试

## 1. 交付范围

本功能让 `JdbcConnectionFactory` 能够根据 `DatabaseConnectionProfile` 打开连接，并提供 `DatabaseConnectionTestService` 的 JDBC 实现。

支持：

- SQLite Profile 文件连接。
- MySQL Profile 服务器连接。
- MariaDB Profile 通过现有 MySQL Connector/J 使用兼容协议连接。
- 连接与 socket 超时。
- Profile 只读属性传递。
- 临时 `char[]` 密码输入。
- 标准化成功、禁用和失败结果。
- Spring 真实服务装配。

本功能尚未把 SQL 执行和元数据服务切换到用户 Profile，也不保存服务器密码。

## 2. 连接规则

### SQLite

- 使用 Profile 中的规范化文件路径。
- 使用 Xerial `SQLiteConfig` 传递只读和 busy timeout。
- 禁用 Profile 在打开 JDBC 连接前即被拒绝。

### MySQL/MariaDB

- 使用 `MysqlDataSource` 分别设置 host、port、database 和 username，不拼接包含凭据的 JDBC URL。
- 密码仅在本次 DataSource 调用期间从 `char[]` 转换，不写入 Profile、数据库、结果或日志。
- connect timeout 与 socket timeout 使用同一受校验的正数时长。
- 建立连接后设置 JDBC read-only 属性；后续真实外部库接入仍需增加服务端只读账号与 SQL 安全双重保护。

## 3. 安全结果与日志

连接成功返回：

- 成功状态。
- 固定中文成功消息。
- JDBC metadata 提供的数据库产品与版本。
- 实际耗时。

连接失败返回固定中文提示，不包含：

- 文件路径或服务器地址。
- 数据库名称和用户名。
- 密码。
- JDBC 异常消息。

日志只记录连接 ID、方言和异常类名，不记录异常堆栈，避免底层错误消息泄露路径或连接参数。

## 4. 增量验证

```powershell
mvn -q "-Dtest=JdbcConnectionFactoryTest,JdbcDatabaseConnectionTestServiceTest,SqlTeacherApplicationConfigTest" test
```

结果：相关增量测试通过，0 失败。

覆盖场景：

- Profile SQLite 文件创建和连接。
- 正数超时校验。
- 禁用 Profile 在连接前拒绝。
- 成功结果包含 SQLite 产品与版本。
- 失败结果不暴露测试文件路径。
- Spring 中可获取真实连接测试服务。

真实 MySQL/MariaDB 环境连接、错误凭据、权限不足和网络超时将在本地测试数据库环境准备完成后作为独立集成功能验证。

累计完成连接契约、Profile 持久化与 JDBC 连接测试后执行批量全量回归：

```powershell
mvn test
```

结果：180 项测试通过，0 失败、0 错误、0 跳过。
