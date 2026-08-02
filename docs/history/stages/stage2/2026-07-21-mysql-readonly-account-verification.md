# MySQL 专用只读账号真实权限验证

## 1. 交付范围

本功能增加一个只供本地集成验收使用的交互式验证器，并在本机 MySQL 9.7.1 上完成真实权限验证。

验证器执行以下生命周期：

1. 从交互控制台读取管理员密码，不显示输入内容。
2. 生成不可预测的隔离数据库名和账号名。
3. 创建测试表与一行测试数据。
4. 使用 MySQL `IDENTIFIED BY RANDOM PASSWORD` 在服务器端生成临时密码。
5. 只授予隔离数据库的 `SELECT` 权限。
6. 以临时账号验证查询、当前 catalog 元数据、写入拒绝和 `mysql.user` 访问拒绝。
7. 在 `finally` 路径删除临时账号和数据库。
8. 清零内存中的管理员密码与临时密码字符数组。

MySQL 官方说明，`IDENTIFIED BY RANDOM PASSWORD` 会在服务器端生成密码并通过结果集返回，二进制日志记录的是密码哈希而非明文；这避免了客户端在 `CREATE USER` SQL 中拼接临时密码：

- <https://dev.mysql.com/doc/refman/9.7/en/create-user.html>
- <https://dev.mysql.com/doc/refman/9.0/en/password-management.html>

## 2. 安全边界

- 验证器只接受由字母、数字和下划线组成的管理员用户名。
- 数据库名和临时账号名由固定前缀加 64 位随机后缀组成，并经过标识符白名单校验。
- 管理员密码不接受命令行参数，不写入环境变量、脚本、状态文件、日志或 Git。
- 临时密码由服务器生成，只在 JDBC 结果和连接建立期间保存在内存。
- 状态文件只包含 `PASS/FAIL`、服务器版本和脱敏 JDBC 分类，不包含 SQL、对象名或凭据。
- 创建过程任一步骤失败，都会尝试执行 `DROP USER IF EXISTS` 和 `DROP DATABASE IF EXISTS`。
- 验证对象使用 `sqlteacher_verify_` / `sqlteacher_ro_` 隔离前缀，不修改管理员账号。

## 3. 自动化增量测试

```powershell
mvn -q "-Dtest=MysqlReadOnlyIntegrationVerifierTest,JdbcConnectionFactoryTest,JdbcFailureClassifierTest" test
```

覆盖：

- 安全标识符允许列表与注入字符串拒绝。
- 管理员用户名校验。
- MySQL 驱动安全配置和 JDBC 权限错误分类保持通过。

## 4. 本地真实验证

交互启动命令：

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.infrastructure.database.MysqlReadOnlyIntegrationVerifier"
```

环境：

- MySQL Server 9.7.1。
- 回环地址 `127.0.0.1:3306`。
- 管理员凭据仅由用户在可见控制台输入一次。

结果：

- 隔离表 `SELECT COUNT(*)` 成功并返回预期行数。
- JDBC 元数据的当前 catalog 与隔离数据库一致，并找到测试表。
- 临时账号执行 `INSERT` 被服务器以权限不足拒绝。
- 临时账号查询 `mysql.user` 被服务器以权限不足拒绝。
- 验证器状态为 `PASS:9.7.1`。
- 验证完成后临时账号和数据库清理成功。
- MySQL 数据目录中残留 `sqlteacher_verify_*` 数据库目录数量为 0。

验证期间未输出或保存管理员密码、临时密码及其哈希。

## 5. 累计全量回归

MariaDB 独立驱动和本功能完成后执行：

```powershell
mvn test
```

结果：203 项测试通过，0 失败、0 错误、0 跳过。

## 6. 当前边界

- 本轮验证的是 MySQL 9.7.1，不替代阶段门禁中 MySQL 8.x 的兼容性声明。
- 本机没有 MariaDB 实例，因此 MariaDB 仅完成独立驱动、配置和 app-image 装配验证，尚未完成真实服务器会话。
- 验证器具有创建和删除隔离对象的能力，只应由本地管理员在明确的测试环境中交互运行，不接入桌面应用菜单或普通用户流程。
