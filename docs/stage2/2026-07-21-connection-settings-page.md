# JavaFX 设置与连接管理页面

## 1. 交付范围

本功能将连接管理服务接入 JavaFX 主窗口，原“设置”占位入口替换为真实数据库连接设置页。

页面支持：

- 后台加载并刷新 Profile 列表。
- 显示当前连接。
- 新增和编辑 SQLite、MySQL、MariaDB 非敏感 Profile。
- 表单字段与端口校验。
- 使用临时密码测试连接，测试发起后立即清空密码框。
- 选择当前连接。
- 启用或禁用用户 Profile。
- 删除用户 Profile前显示确认对话框。
- 内置 `demo/app` Profile 只读展示，禁止保存覆盖和删除。
- 服务失败时通过 `ApplicationExceptionMapper` 展示安全中文消息。

所有 Profile 加载、保存、删除、选择和连接测试均使用 `DesktopExecutors` 后台线程，不阻塞 JavaFX Application Thread。

## 2. 页面结构

```text
主窗口“设置”导航
└─ 数据库连接设置
   ├─ 左侧：Profile 列表、刷新、新增、当前连接
   └─ 右侧：连接表单
      ├─ SQLite 文件目标
      ├─ MySQL/MariaDB 服务器目标
      ├─ 只读与启用状态
      └─ 删除、设为当前、测试连接、保存
```

SQLite 类型只启用文件路径字段；MySQL/MariaDB 类型启用 host、port、database、username 和临时密码字段。

## 3. 密码与安全

- 密码框明确标注“仅用于连接测试”。
- 密码不进入 `DatabaseConnectionProfile`，保存操作不会持久化密码。
- 测试开始后清空界面密码框。
- 后台测试结束后使用 `Arrays.fill` 清空临时 `char[]`。
- 连接失败只展示标准安全结果，不显示 JDBC 异常、地址或文件路径。
- 删除需要显式确认；内置连接不能进入删除流程。

## 4. 增量验证

```powershell
mvn -q "-Dtest=DesktopFxmlResourceTest,ConnectionSettingsControllerTest,JdbcConnectionManagementServiceTest,JdbcDatabaseConnectionTestServiceTest" test
```

结果：相关增量测试通过，0 失败。

覆盖：

- 新 FXML 资源存在且 XML 结构正确。
- SQLite/MySQL 表单到 Profile 的转换。
- 缺少 SQLite 路径和非法端口拒绝。
- Profile 列表、持久化、选择、禁用和删除回退。
- 连接测试成功、失败脱敏和禁用状态。

## 5. JavaFX 人工走查

在本机运行：

```powershell
mvn javafx:run
```

走查结果：

- 主窗口启动成功。
- 顶部“设置”导航可见并可进入设置页。
- 页面成功显示 `demo`、`app` 两个内置 Profile。
- 当前连接显示为“SQLite 演示数据库”。
- 选择内置 `demo` 后，ID 与方言不可编辑，保存和删除按钮禁用。
- SQLite 字段可用，服务器与临时密码字段按类型禁用。

本轮未连接真实 MySQL/MariaDB，也未执行删除确认的最终确认按钮。

## 6. 当前边界

- 当前连接选择已经持久化，但 SQL 执行、元数据和 NL2SQL 尚未统一切换到所选用户 Profile；该集成作为下一功能实施。
- 服务器密码不持久化，应用重启后需要用户再次输入。
- 正式页面尺寸、键盘导航和所有异常弹窗仍需在阶段 `v0.2.0` 验收时完整走查。
