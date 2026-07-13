# 主窗口内嵌 SQL 练习页 + 单卡片美化（整改说明）

> 范围：仅桌面模块。改动目录限于 `src/main/java/com/sqlteacher/desktop`、
> `src/main/resources/fxml`、`src/main/resources/css`、`src/test/java/com/sqlteacher/desktop/mock`、
> `docs/desktop`。未触碰 application / infrastructure / 打包脚本 / 其他成员模块。

## 1. 修复两个 FXML 相互独立的问题（嵌套联动）

### 问题定位

| 断点 | 现象 |
|---|---|
| `SqlTeacherFxApp` | `start()` 从未加载 `MainWindow.fxml`，而是启动 Spring 容器 + 真实 DB/AI 服务并拼一个硬编码 VBox，违反“离线 Mock、绝不引入真实后端类”约束。 |
| `MainWindowController` | `buildSqlPracticePage()` 返回占位 VBox，从未加载 `SqlPractice.fxml`。 |
| Mock 源集隔离 | `SqlExecutionMockService` / `MockScenario` 原在 `src/test`，主源码编译期不可见，无法向控制器构造注入。 |

### 解决方案（贯穿构造注入，单一顶层窗口）

```
SqlTeacherFxApp.start()
  └─ new SqlExecutionMockService(NORMAL)                （离线 Mock 实现）
  └─ FXMLLoader(/fxml/MainWindow.fxml)
       └─ setControllerFactory → new MainWindowController(sqlExecutionService)
            └─ 首次导航懒加载 /fxml/SqlPractice.fxml
                 └─ setControllerFactory → new SqlPracticeController(sqlExecutionService)
                      └─ 根节点放入 pageContainer（StackPane 插槽）
```

- 程序**仅启动 `MainWindow` 这一个顶层窗口**；SQL 练习页作为 `pageContainer` 内的嵌套节点渲染，**不再独立弹窗**。
- 侧边导航点击 `#onNavigateSqlPractice` 切换插槽内容；SQL 练习页懒加载一次后缓存复用，保留输入 / 结果状态。后续新增页面同样复用 `showPage(Node)` + 同一插槽。
- 因两个控制器都是构造注入（无无参构造），加载各自 FXML 时必须用 `setControllerFactory`，不能用默认 `load()`。
- `SqlPractice.fxml` 内**全部 fx:id 保持不变**（`sqlInputArea` / `executeSqlButton` / `resultPane` / `resultTable` / `emptyPlaceholder` / `errorLabel`），`SqlPracticeController` 的表格动态列、三态渲染、底部错误提示逻辑**零改动**。

### 关键抉择：Mock 下沉到 main 源集

- 主源码无法引用 test 源集类，故将 `SqlExecutionMockService.java`、`MockScenario.java`
  从 `src/test/java/com/sqlteacher/desktop/mock/` **移动**到 `src/main/java/com/sqlteacher/desktop/mock/`（同包名）。
- 项目**无 `module-info`（非模块化）**，采用 classpath 模式，`com.sqlteacher.desktop.mock` 跨 main/test 的 split package 合法。
- 其余 Mock（`Nl2SqlMockService`、`AiStatusMockService`、`AppConfigurationMockService`、
  `DatabaseInitializationMockService`、`AsyncMockInvoker`、`MockBackendException`）与 `MockServiceContractTest` **仍在 test 源集**，同包引用移动后的两个类，无需改动 import，测试全部照常通过。

## 2. 两层页面美化（统一轻科技风）

- **侧边导航**（原已达标，保留）：固定 160px；雅致冷灰深蓝 `#223048` 双层渐变；品牌标题 18px 加粗 + `#35486a` 细分割线；导航按钮 9px 圆角，常态透明 / 悬浮半透明蓝 / 选中 `#3b72d0` 实底白字三态。
- **右侧内容背景**：透亮浅冷灰 `#f7f9fc`，左侧 1px `#d9e2ef` 竖直分割线。
- **SQL 练习整块封装为单张卡片** `.sql-practice-card`：纯白底 + 10px 圆角 + `#d9e2ef` 细边 + 柔和 dropshadow + 均匀内边距；卡片内自上而下：标题、提示、SQL 输入行、结果面板、底部错误提示（由两张卡片合并为一张）。
- **输入框** `.sql-editor`：8px 圆角 + `#d2dce9` 常态边框，聚焦切换 `#4478d6`；**执行按钮** `.sql-execute-button`：科技蓝 `#3b72d0`，hover `#2f63b8` / pressed `#29579f`，去除原生灰质感。
- **结果面板** `.result-pane`：极浅 `#fcfdff` 底 + `#e3e9f3` 细边 + 8px 圆角，在白卡内勾勒清晰结果区；表格浅蓝表头 `#e8eef7`、隔行 `#ffffff/#f7f9fc`、单元格 `padding 8 14`、细化滚动条；EMPTY 场景居中显示「暂无查询结果」。
- **底部错误提示** `.sql-error-hint`：`#d63b3b` 文字 + `#fdf2f2` 淡红底 + `#f3c9c9` 细边 + 8px 圆角，正常状态自动隐藏。
- **间距 / 适配**：窗口默认 1180×720（最小 960×600），适配 1366×768；卡片区块纵向留白由 FXML `spacing` 统一控制，消除松散空白。
- 清理：删除随占位页移除而失效的 `.page-placeholder*`、`.input-card`、`.result-card`、`.sql-practice-page` 样式类。

## 3. 兼容性与限制

- 全部 CSS 仅用 JavaFX 21 原生属性：`linear-gradient` / 基础 `-fx-border-*` / `-fx-effect: dropshadow`；
  未使用 `text-shadow` / `box-shadow` / CSS `transition` / 滤镜发光（21 不支持），状态切换为原生即时切换。
- 已知限制：`SqlPracticeController.onExecuteSql()` 沿用同步执行（Mock 为毫秒级即时返回，未阻塞体感），
  尚未接入异步执行器；实时 UI 渲染需在有桌面图形环境下用 `mvn javafx:run` 观察，本次未做自动化 UI 截图。

## 4. 验证

```powershell
mvn -o -Dtest=MockServiceContractTest test
# => BUILD SUCCESS；Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
```

- 主源码 + 测试源码均编译通过（split package 生效）。
- 手动运行：`mvn javafx:run` 启动单一 `MainWindow` 窗口，右侧内嵌 SQL 练习卡片，点击「执行 SQL」按 NORMAL 场景展示三行结果。
