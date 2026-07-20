# 连接管理领域模型与 Application 契约

## 1. 交付范围

本功能冻结 `v0.2.0` 连接管理所需的 application 层边界，不包含 JDBC 实现、配置持久化或 JavaFX 设置页面。

新增能力：

- 区分 SQLite、MySQL 和 MariaDB 方言。
- 分别表达 SQLite 文件目标和 MySQL/MariaDB 服务器目标。
- 定义不包含密码的连接 Profile。
- 定义连接列表、保存、删除、当前选择和按 ID 查询服务。
- 定义使用临时密码进行连接测试的服务与标准结果。

## 2. 模型

```text
DatabaseDialect
DatabaseConnectionTarget
├─ SqliteConnectionTarget
└─ ServerConnectionTarget
DatabaseConnectionProfile
DatabaseConnectionTestResult
```

`DatabaseConnectionProfile` 包含：

- 稳定 `id`。
- 用户可见名称。
- SQLite 文件或服务器目标。
- 只读标志。
- 启用状态。

Profile ID 限制为 1-64 个字母、数字、点、下划线或连字符，继续兼容现有 `SqlExecutionRequest.connectionId` 的字符串边界。

## 3. 密码安全边界

- `DatabaseConnectionProfile` 和 `ServerConnectionTarget` 不包含 password 字段。
- 连接测试通过临时 `char[] password` 接收密码。
- 调用方负责在使用后清空数组。
- 实现不得保留、记录密码，也不得把密码放入测试结果、异常或学习事件。
- 本功能暂不决定长期密钥存储实现；在完成安全存储评审前，服务器密码只用于当前会话或由用户再次输入。

## 4. Application 服务

### `ConnectionManagementService`

```text
listProfiles
findProfile
saveProfile
removeProfile
currentProfile
selectProfile
```

该接口只管理非敏感 Profile 和当前选择，不向 JavaFX 暴露 JDBC 连接。

### `DatabaseConnectionTestService`

```text
testConnection(profile, transientPassword)
```

返回统一的成功状态、学生可读消息、数据库产品、版本和耗时。底层 JDBC 异常需要由后续实现转换，不能直接交给 UI。

## 5. 后续实现约束

- MySQL/MariaDB Profile 默认由 UI 创建为只读，基础设施还需通过 JDBC 设置和权限验证再次执行只读保护。
- 禁用的 Profile 不得被选为当前连接。
- 删除当前 Profile 时必须定义可预测的回退行为，优先回到内置 `demo`。
- `listProfiles()` 必须返回不可变列表或防御性副本，不返回 `null`。
- 连接测试、元数据加载和查询都必须在 JavaFX 后台执行器运行。
- Profile 持久化需要使用 `app.db` 的后续 schema 迁移，不直接写散落配置文件。

## 6. 增量验证

```powershell
mvn -q "-Dtest=ConnectionContractTest,ApplicationContractTest" test
```

验证覆盖：

- SQLite 路径规范化。
- MySQL/MariaDB host、port、database 和 username 校验。
- 非法 Profile ID 与错误目标类型拒绝。
- 持久化模型中不存在 password 字段。
- 连接测试结果的文本、耗时和空值校验。
