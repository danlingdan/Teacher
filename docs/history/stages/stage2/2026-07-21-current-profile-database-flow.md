# 当前 Profile 数据库链路

## 1. 交付范围

本功能取消桌面 SQL、表结构和 NL2SQL 链路中写死的 `demo` 连接，让三个入口在后台任务开始时读取同一个当前 Profile。

已完成：

- SQL 练习使用当前 Profile 的连接 ID。
- 表结构与数据预览使用当前 Profile。
- NL2SQL 使用当前 Profile 的规范化元数据和数据库方言。
- SQLite、MySQL 和 MariaDB Profile 统一通过 Profile 感知的 JDBC 连接提供器。
- MySQL 元数据限定为连接的当前 catalog/schema，支持普通表、视图和系统表类型。
- 页面文案由“SQLite 演示库”调整为“当前数据库”。

## 2. 会话凭据

服务器密码只保存在当前应用进程的内存会话中：

- Profile 和 `app.db` 继续只保存非敏感参数。
- 只有已保存且内容一致的服务器 Profile 连接测试成功后，才写入内存会话。
- 输入密码、查询得到的密码和被替换的密码数组均使用防御性复制，并在不用时覆盖清零。
- 保存或删除 Profile 会清除关联会话凭据。
- Spring Context 关闭时清除全部会话凭据。
- 应用重启后，服务器 Profile 必须在设置页重新输入密码并测试连接。
- 缺少会话凭据时，在发起网络连接前返回固定的安全提示。

密码未写入源码、测试、文档、命令行、日志或 Git。

## 3. 只读安全

- 外部服务器 Profile 必须启用只读模式，否则连接提供器直接拒绝。
- SQL 执行服务在 Java 风险分析之后检查 Profile 只读状态。
- 只读连接只允许 `SELECT`；即使用户确认风险，`UPDATE`、`DELETE`、DDL 等语句也会在打开 JDBC 连接前被拦截。
- 多语句和禁止语句仍沿用原 SQL 风险规则。
- JDBC 连接继续设置驱动层只读状态，形成 Java 规则和驱动属性两层保护。

## 4. 增量验证

```powershell
mvn -q "-Dtest=CurrentProfileDatabaseFlowTest,InMemoryDatabaseCredentialSessionTest,ProfileAwareJdbcConnectionProviderTest,JdbcSqlExecutionServiceTest,JdbcConnectionManagementServiceTest,JdbcDatabaseConnectionTestServiceTest,SqlTeacherApplicationConfigTest,Nl2SqlServiceImplTest,DesktopFxmlResourceTest,ConnectionSettingsControllerTest" test
```

结果：相关增量测试通过，0 失败。

覆盖：

- 当前用户 SQLite Profile 的元数据和 SQL 查询使用同一数据库。
- 只读 Profile 的写操作在连接前被拦截。
- 未提供凭据和非只读服务器 Profile 安全拒绝。
- 会话密码使用防御性复制并可清除。
- Spring 真实服务装配、NL2SQL 元数据回归和 FXML 结构保持通过。

## 5. 本地 MySQL 真实验证

验证环境：

- MySQL Server 9.7.1。
- 本机回环地址、端口 3306。
- Profile 数据库为 `mysql`，启用只读模式。

人工走查结果：

- 设置页连接测试成功，界面显示 MySQL 9.7.1。
- 密码框在测试发起后自动清空。
- Profile 可设为当前连接。
- `SELECT VERSION() AS version, DATABASE() AS db;` 成功返回 `9.7.1` 和 `mysql`。
- 表结构页成功读取 `mysql` catalog 的表和字段。
- 修正 catalog 过滤后，未再展示其他数据库的元数据。

验证期间未执行写 SQL，未读取或输出密码。

## 6. 当前边界

- 本次未使用 MariaDB 实例验证兼容协议。
- 本次未实际提交只读连接上的写语句；Java 侧拒绝由自动化测试覆盖，避免对本地数据库产生修改风险。
- MySQL 错误凭据、无效地址、超时和权限不足已形成确定性的脱敏分类回归，详见 `2026-07-21-mysql-failure-and-dialect-safety.md`；专用受限账号的真实权限验证仍待独立环境。
- MySQL 方言的 NL2SQL Prompt 已接入当前 Profile，但真实模型输出质量需要单独回归。
