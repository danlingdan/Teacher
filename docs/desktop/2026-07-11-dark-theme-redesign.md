# 桌面端深色主题视觉重构（任务十）布局改动说明

> 适用范围：`com.sqlteacher.desktop` JavaFX 桌面模块。
> 目标：对标参考截图的深色精致设计，重构窗口/导航/页面视觉风格，
> **仅调整样式与上层布局结构**，不改动任何业务逻辑、fx:id、Mock 调用与异常捕获。
> 环境：JDK 21 + JavaFX 21；CSS 全部使用 JavaFX 21 原生语法（禁用 text-shadow /
> box-shadow / transition / 滤镜发光等网页专属特效）。

## 1. 改动文件清单

| 文件 | 改动性质 | 说明 |
|---|---|---|
| `src/main/resources/fxml/MainWindow.fxml` | 布局重构 | 左侧窄侧边栏 → 顶部横向全局导航栏 |
| `src/main/resources/fxml/SqlPractice.fxml` | 布局重构 | 单卡片 → 居中内容 + 标题区 + 三张深色卡片 |
| `src/main/resources/css/app.css` | 完整重写 | 浅色单卡片主题 → 深色精致主题全套样式 |
| `src/main/java/com/sqlteacher/desktop/controller/SqlPracticeController.java` | 仅新增 | 新增 `onFillExampleSql(ActionEvent)` 示例填充处理器 |

> Java 侧仅 `SqlPracticeController` 新增一个方法与一行 `import javafx.event.ActionEvent;`，
> 其余 Java 文件（`SqlTeacherFxApp`、`MainWindowController`、ViewModel、Mock）**零改动**。

## 2. 全局风格基准

- 最外层窗口统一深邃暗色 `#141c30`（`.main-window`）。
- 舍弃原左侧 160px 窄侧边栏，改为顶部横向全局导航栏。
- 页面内容居中排布、上下留出充足边距；内容列固定最大宽度（1040），窗口变宽时整体居中、宽度稳定，适配 1366×768 及更高分辨率。

## 3. MainWindow.fxml —— 顶部横向导航

- 根节点 `BorderPane`（`main-window`），`<top>` 放导航栏、`<center>` 放页面容器。
- 顶部导航栏 `HBox`（`top-nav`，底色 `#0f1627`、14px 圆角、`BorderPane.margin` 四周浮起使圆角显现）自左至右：
  - 品牌 Logo 小方块（`brand-logo`，纯 CSS 蓝紫渐变，无图片文件）+ 品牌文字「SQL Teacher」（`brand-title`）；
  - 横向导航菜单：`sqlPracticeNavButton`（`nav-button`，text「SQL 练习」，`onAction=#onNavigateSqlPractice`），选中态由 Controller 追加 `selected` 高亮为蓝紫渐变；
  - 弹性留白 `Region`（`HBox.hgrow=ALWAYS`）把右侧按钮推到最右；
  - 右侧「设置」占位按钮（`nav-settings`，`disable="true"`），预留后续配置入口。
- **保留业务 fx:id**：`sqlPracticeNavButton`、`pageContainer`。`MainWindowController` 路由逻辑（`initialize → onNavigateSqlPractice`、`selectNav`、`showPage`）无需改动。

## 4. SqlPractice.fxml —— 居中内容 + 标题区 + 三张卡片

- 根 `VBox`（`sql-practice-page`，`alignment=TOP_CENTER`，上下充足内边距）。
- 内层内容列 `VBox`（`page-content`，`maxWidth=1040`）自上而下：
  1. **标题区**（`title-area`，居中）：小图标徽标（`title-badge`，纯 CSS 渐变）+ 主标题「SQL 练习」（`page-title`，淡紫蓝）+ 弱化副标题「输入SQL语句，借助离线Mock查看查询效果」（`page-subtitle`）。
  2. **第一层卡片：示例 SQL**（`panel-card`）：卡片小标题「示例 SQL」（`card-accent` 竖条 + `card-title`）；`FlowPane`（`example-flow`）内 4 个可点击标签（`example-chip`），`onAction=#onFillExampleSql`，点击把该 SQL 填入下方输入框（仅填充、不执行）。4 组示例：
     - `SELECT id, name, grade, class FROM student ORDER BY grade DESC;`
     - `SELECT * FROM student WHERE grade > 90;`
     - `SELECT name,AVG(grade) FROM student GROUP BY class;`
     - `SELECT * FROM student WHERE grade > 999;`
  3. **第二层卡片：编写 SQL 语句**（`panel-card`）：卡片小标题「编写 SQL 语句」；`TextArea`（`sqlInputArea`，`sql-editor`）多行输入；右下 `HBox`（`sql-action-row`，`CENTER_RIGHT`）内「执行 SQL」按钮（`executeSqlButton`，`sql-execute-button`，`onAction=#onExecuteSql`）。
  4. **第三层卡片：查询结果**（`panel-card`，`VBox.vgrow=ALWAYS`）：卡片小标题「查询结果」；`StackPane`（`resultPane`，`result-pane`）叠放结果表格（`resultTable`，`result-table`）与空态占位「暂无查询结果」（`emptyPlaceholder`，`table-empty-hint`），二者互斥显示；其下为底部错误提示条（`errorLabel`，`sql-error-hint`，默认 `visible/managed=false`）。
- **保留全部业务 fx:id**：`sqlInputArea`、`executeSqlButton`、`resultPane`、`resultTable`、`emptyPlaceholder`、`errorLabel`。Controller 三态渲染（NORMAL / EMPTY / ERROR）与异常捕获逻辑完全保留。

## 5. app.css —— 深色主题全套样式（完整重写）

- 卡片统一 14px 圆角；输入框 / 结果面板 / 执行按钮 12px 圆角；标签 / 导航按钮 10px 圆角。
- 阴影统一使用 JavaFX 原生 `-fx-effect: dropshadow(gaussian, rgba(...), 半径, 0.0, x, y)`（顶部导航、卡片各一层柔和投影）。
- 渐变统一使用原生 `linear-gradient`：品牌 Logo / 标题徽标 / 卡片竖条为蓝紫渐变装饰；「执行 SQL」按钮为蓝紫横向渐变，hover / pressed 逐档加深饱和度。
- hover / 选中态即时切换（JavaFX 21 不支持 CSS transition，未使用）。
- 深色调色板：窗口 `#141c30`、导航 `#0f1627`、卡片 `#1b253d`、结果面板 `#172035`、输入框 `#212c44`、表头 `#26355a`、行交替 `#1b253d`/`#212d49`、标题淡紫蓝 `#b7c3ff`、错误浅红 `#ff9b9b` + 半透明红底。
- 暗色滚动条：输入框与结果表格滚动条统一细宽度、`#3a4a6e` 滑块、透明轨道、折叠原生箭头。

## 6. SqlPracticeController.java —— 仅新增示例填充处理器

- 新增 `import javafx.event.ActionEvent;`。
- 新增方法 `onFillExampleSql(ActionEvent)`：读取事件源示例标签按钮的文本，`setText` 填入 `sqlInputArea`，`requestFocus` + `positionCaret` 定位光标末尾。**仅填充输入，不触发执行**，不改动任何三态渲染或异常捕获逻辑。
- 其余字段、`initialize`、`onExecuteSql`、`renderExecution` / `showResultRows` / `showEmptyState` / `showErrorState` 等方法均未改动。

## 7. 与历史浅色约束的关系（覆盖说明）

此前任务九确立的浅色视觉约束（侧边栏 160px、内容背景 `#f7f9fc`、选中态 `#3b72d0` 等）
被本任务的深色设计规格**显式覆盖**（按"当前任务显式指令优先"原则）。
仅覆盖视觉层，目录边界、依赖方向、离线 Mock 注入链路、业务 fx:id 与 Controller 逻辑均保持不变。

## 8. 验证结果

- 命令：`mvn -o -Dtest=MockServiceContractTest test`（离线，JDK 21）。
- 结果：`BUILD SUCCESS`；`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`。
- 说明：契约测试不依赖 JavaFX 运行环境，验证 Mock 三态与 ViewModel 映射未受本次纯视觉改动影响。
- 桌面实际观感需在具备图形环境时通过 `mvn javafx:run` 目视确认（本轮未在图形环境运行）。
