# MariaDB 独立驱动与安全连接路由

## 1. 交付范围

本功能将服务器 Profile 的 JDBC 建连按方言拆分，避免 MariaDB 继续隐式复用 MySQL Connector/J。

已完成：

- MySQL Profile 使用 MySQL Connector/J 9.4.0。
- MariaDB Profile 使用 MariaDB Connector/J 3.5.9。
- 两类驱动都继承 Profile 的 host、port、database、username、临时密码和只读状态。
- 两类驱动都设置连接超时和 socket 超时。
- 驱动配置层明确关闭多语句执行和本地文件导入。
- MariaDB 配置关闭异常中的 SQL 正文输出。
- 技术验证程序同时检查 MySQL 和 MariaDB 驱动是否进入运行时 classpath。

MariaDB 官方说明 Connector/J 3.5 支持 Java 21，并兼容 MariaDB 与 MySQL 服务器；项目仍以真实集成验证结果作为最终支持范围，不因驱动声明直接承诺所有服务器版本：

- <https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j>
- <https://mariadb.com/downloads/connectors/connectors-data-access/java8-connector/>

MySQL Connector/J 9.x 的官方目标为 MySQL Server 8.0 及以上：

- <https://dev.mysql.com/doc/connector-j/en/connector-j-versions.html>

## 2. 安全边界

驱动层配置如下：

| 配置 | MySQL | MariaDB |
|---|---:|---:|
| connect timeout | 开启 | 开启 |
| socket timeout | 开启 | 开启 |
| multi queries | 关闭 | 关闭 |
| local infile | 关闭 | 关闭 |
| SQL 正文异常输出 | 沿用驱动安全默认值 | 明确关闭 |
| JDBC read-only | 按 Profile 设置 | 按 Profile 设置 |

这些配置是 Java 风险分析和只读 Profile 检查之外的驱动级纵深保护。AI 草案仍必须先经过方言风险检测，不能直接到达 JDBC。

密码仅在当前进程的凭据会话和驱动建连期间使用，没有写入 Profile、文档、日志或 Git。

## 3. 增量验证

```powershell
mvn -q "-Dtest=JdbcConnectionFactoryTest,JdbcTechnologyVerifierTest,JdbcDatabaseConnectionTestServiceTest,ProfileAwareJdbcConnectionProviderTest,SqlTeacherApplicationConfigTest" test
```

结果：通过，0 失败。

覆盖：

- MySQL 驱动目标、端口、数据库、用户名、超时和危险能力关闭。
- MariaDB 独立驱动目标、端口、数据库、用户名、超时和危险能力关闭。
- 方言与驱动配置不匹配时拒绝。
- SQLite 连接、当前 Profile 提供器和 Spring 装配保持通过。
- MySQL/MariaDB 驱动类均可加载。

## 4. CLI 与 app-image 验证

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp"
.\packaging\package-stage0.ps1
.\target\installer\SQLTeacherStage0\SQLTeacherStage0.exe
```

结果：

- 源码 classpath 下 MySQL JDBC 与 MariaDB JDBC 均为 `PASS`。
- 重新生成 app-image 后，两类驱动在打包运行时中均为 `PASS`。
- SQLite、Java 21、JavaFX 和图形环境验证通过。
- Ollama 当前未启动，健康检查为预期 `WARNING`，不影响本功能。

## 5. 当前边界

- 本机只有 MySQL97 服务，蓝屏重启后仍处于停止状态；本功能未修改其 Windows 服务状态。
- 本机没有 MariaDB 或 Docker 环境，因此本轮验证到独立驱动装配和安全配置，不宣称完成真实 MariaDB 会话验证。
- 本轮没有创建、授权或删除数据库账号，没有修改个人管理员账号。
- 下一功能将在 MySQL 服务可用后，以隔离测试库和临时只读账号验证查询成功、写入拒绝及元数据范围，并在验证完成后清理测试对象。
