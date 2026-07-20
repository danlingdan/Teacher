# MySQL 失败分类与方言安全回归

## 1. 交付范围

本功能完善外部 MySQL/MariaDB 连接的失败降级和方言安全边界，使连接测试、元数据读取、SQL 执行和 AI 草案复用同一套安全规则。

已完成：

- 按 JDBC SQLState、MySQL vendor code 和异常原因链识别身份验证、权限、超时、连接和普通 SQL 错误。
- 连接测试、元数据读取和 SQL 执行只向界面返回固定中文提示，不传播驱动异常、服务器地址或密码信息。
- 日志只记录连接 ID、失败类别、SQLState 和 vendor code，不记录异常消息与 SQL 正文。
- SQL 执行和 AI 草案风险检查使用当前 Profile 的数据库方言。
- MySQL/MariaDB 特有危险查询在建立 JDBC 连接前拦截。

## 2. 失败分类

| 类别 | 识别依据 | 应用错误码 | 用户提示特点 |
|---|---|---|---|
| 身份验证 | SQLState `28xxx`、MySQL `1045` | `DATABASE_AUTHENTICATION_FAILED` | 检查用户名和临时密码 |
| 权限不足 | MySQL `1044/1142/1143/1227/1370/1419`、SQLState `42501` | `DATABASE_PERMISSION_DENIED` | 配置只读查询权限 |
| 超时 | `SQLTimeoutException`、socket timeout、SQLState `HYTxx` | `DATABASE_CONNECTION_TIMEOUT` | 检查网络和服务状态 |
| 连接失败 | SQLState `08xxx`、拒绝连接、主机解析失败 | `DATABASE_CONNECTION_FAILED` | 检查地址、端口和服务状态 |
| 普通 SQL 错误 | 其他 JDBC SQL 异常 | `SQL_EXECUTION_FAILED` | 检查语法、表名和字段名 |

分类会遍历有限长度的异常原因链。若外层驱动只报告通信失败、内层明确为 socket timeout，则优先归类为超时。

## 3. MySQL/MariaDB 方言规则

以下单条 `SELECT` 默认禁止：

- `INTO OUTFILE`、`INTO DUMPFILE` 文件输出。
- `FOR UPDATE`、`LOCK IN SHARE MODE` 锁定读取。
- `SLEEP`、`BENCHMARK` 延迟或资源消耗函数。
- `GET_LOCK`、`RELEASE_LOCK` 命名锁函数。
- `LOAD_FILE` 文件读取函数。

分析前会遮蔽字符串和引用标识符内容，避免教学文本中出现上述关键词时误报。原有多语句拦截、外部 Profile 只读限制和驱动层只读设置继续生效。

AI 生成结果仍只作为草案，必须携带当前 Profile 方言进入 Java 风险分析，不会直接执行。

## 4. 验证记录

增量验证：

```powershell
mvn -q "-Dtest=DefaultSqlRiskAnalysisServiceTest,SqlRiskRegressionTest,DefaultNl2SqlSafetyServiceTest,JdbcFailureClassifierTest,JdbcSqlExecutionServiceTest,JdbcDatabaseConnectionTestServiceTest,CurrentProfileDatabaseFlowTest,ProfileAwareJdbcConnectionProviderTest,SqlTeacherApplicationConfigTest" test
```

结果：通过，0 失败。

累计功能全量验证：

```powershell
mvn test
```

结果：197 项测试通过，0 失败、0 错误、0 跳过，构建成功。

自动化覆盖：

- MySQL 身份验证和权限 vendor code。
- 连接、超时及嵌套异常的分类优先级。
- 普通 SQL 语法错误的安全降级。
- MySQL/MariaDB 文件、锁、延迟函数拦截和字符串误报回归。
- SQL 执行在打开连接前使用当前方言拦截。
- AI 草案将当前 Profile 方言传递给风险服务。
- JDBC 敏感异常消息不会成为应用错误消息。

## 5. 环境与边界

- 蓝屏重启后本机 MySQL 服务处于停止状态，本轮未改动 Windows 服务状态。
- 上一功能已完成本机 MySQL 9.7.1 的真实连接、元数据和只读查询验证，本轮未重复输入或保存真实密码。
- 为避免锁定管理员账号，本轮错误凭据和权限不足使用确定性 JDBC 异常样例验证，没有对真实管理员账号连续尝试错误密码。
- 本轮未创建、授权或删除数据库账号，也未执行写 SQL。
- 专用只读测试账号的真实权限边界和 MariaDB 实例兼容性作为下一独立功能验证。
