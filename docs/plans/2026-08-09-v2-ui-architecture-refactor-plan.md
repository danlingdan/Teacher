# SQLTeacher 2.0 桌面 UI 架构重构计划

> 制定日期：2026-08-09
> 适用基线：`2.0.0-alpha.2` 及后续 Alpha
> 状态：实施中；先建立公共架构，再按主路径迁移全部页面

## 1. 为什么需要再次重构

这次重构不是更换配色。v1.5 与 v1.5.5 已建立 AtlantaFX、语义色和响应式壳层，
但当时明确留下了复杂页面结构迁移。当前代码量化结果再次暴露了同一问题：

- 21 个 FXML 页面和 21 个控制器，最大控制器超过 1,200 行；
- 遗留 `css/app.css` 仍有约 1,820 行，页面样式继续分散在多层文件；
- FXML 中约有 142 处固定宽高，低分辨率适配主要依赖页面自行处理；
- CSS/FXML 中仍有约 345 个直接颜色值，语义 token 尚未成为唯一来源；
- 页面标题、空态、加载、失败、指标卡、筛选区和操作区仍被重复拼装；
- `MainWindowController` 同时拥有路由、权限、页面工厂、缓存、命令、跨页联动和大量依赖。

因此，未来继续增加课程类型、Runner、项目工作区和教师能力前，必须先冻结页面骨架与扩展契约。

## 2. 技术路线

继续使用 JavaFX 21 和 AtlantaFX，不迁移 Electron、Tauri 或 Compose Desktop。现有业务、
FXML、测试和 Windows 打包链已经围绕 JavaFX 建立，迁移 UI 技术栈会扩大风险，却不会自动解决
信息架构和组件复用问题。

精选依赖遵循“一库一职责”：

| 依赖 | 用途 | 不承担的职责 |
| --- | --- | --- |
| AtlantaFX | 亮暗主题、基础控件外观和语义色基线 | 页面结构和完整组件库 |
| ControlsFX | 可搜索选择、面包屑、通知、校验和主从布局等成熟控件 | 替换 AtlantaFX 主题 |
| RichTextFX | SQL/代码编辑、行号、语法样式和未来诊断标记 | 普通表单文本输入 |
| Ikonli | 集中、可访问、可缩放的矢量图标 | 用图标代替文字或无障碍标签 |

不同时引入 MaterialFX、JFoenix 等第二套主题框架。图表、Markdown、终端或 WebView 组件仅在
出现真实纵向需求后单独评估，禁止为了“看起来库更多”而增加依赖。

## 3. SQLTeacher UI Kit

项目在 `com.sqlteacher.desktop.component` 建立自己的稳定组件层。业务控制器只提供状态和动作，
公共组件不依赖应用服务、JDBC、AI 或文件系统。

首批组件：

- `PageHeader`：统一 eyebrow、标题、说明和页面级动作；
- `StatePanel`：统一加载、空态、失败和无权限表达；
- `MetricCard`：统一学习证据和教师看板指标；
- RichTextFX `CodeArea`：作为 SQL 与未来代码 Runner 的编辑器基线；
- Ikonli 语义图标映射：页面不再保存或复制 SVG path。

后续组件：

- `WorkspaceScaffold`：目录轨、Runner、检查器和状态栏；
- `FilterBar`、`FormSection`、`ActionBar`、`DataTablePane`；
- `NotificationCenter` 和页面内 `AsyncState` 渲染；
- `PageDescriptor`、页面工厂、缓存生命周期和导航历史。

## 4. 壳层与页面契约

壳层只负责全局信息架构：身份、工作区、导航、命令和当前页面上下文。页面负责自己的局部加载、
空态、错误、取消和过期结果保护。除登录切换、数据库切换等真正阻断全局状态的操作外，不再用
全屏 Loading 遮罩覆盖整个应用。

每个页面最终应满足：

1. 使用统一 `PageHeader`，只保留一个主标题和一个主要动作；
2. 内容区采用工作区、主从、表单或看板四种模板之一；
3. loading / empty / error / content 状态互斥且由明确状态模型驱动；
4. 固定尺寸只用于图标、触控目标和合理的侧栏边界，主体可伸缩；
5. 所有颜色来自语义 token，所有图标带可访问文本；
6. 耗时操作在后台执行，结果回写校验页面和请求是否仍然有效；
7. 控制器不再承担可抽取的路由、格式化、表单校验和组件构造逻辑。

## 5. 全页面迁移顺序

| 批次 | 页面 | 重点 |
| --- | --- | --- |
| A：基础与主路径 | MainWindow、首页、课程地图、实验工作区、SQL 练习 | UI Kit、命令面板、图标、代码编辑器、导航生命周期 |
| B：学习路径 | 学生练习、知识中心、表结构 | 统一任务上下文、主从结构、空态与结果区 |
| C：教师路径 | 教学内容、题库管理、学习进度 | 拆分超大控制器、筛选器、指标卡、表格和批量动作 |
| D：AI 与协作 | AI 助手、Cloud Center | Provider/身份状态、异步取消、通知和敏感输入边界 |
| E：系统路径 | Settings 及其子页、登录门 | 表单分区、校验、窄屏、键盘和焦点顺序 |
| F：收敛 | 全部页面 | 清除遗留直接颜色和无效 CSS，停止加载 `app.css` |

一个批次只有在行为测试、FXML/CSS 契约、亮暗主题、低分辨率和键盘路径通过后才能关闭。
不以截图相似度代替交互和状态验证。

## 6. 当前第一批落地

本次已在工作区完成以下基础切片：

- 引入 ControlsFX、RichTextFX 与 Ikonli，并更新第三方许可清单；
- 新建 `PageHeader`、`StatePanel`、`MetricCard`；
- 课程地图和 SQL 工作台改用统一 `PageHeader`；
- SQL 编辑器迁移为带行号和视觉语法高亮的 RichTextFX `CodeArea`，保留既有 Java 安全执行链；
- 壳层 Ctrl+K 使用 ControlsFX 可搜索选择器；
- 导航图标由 Ikonli 统一管理，不再维护手写 SVG path。
- 首页、课程地图和实验工作区开始使用显式路由缓存生命周期，命令面板不再由主控制器构造。
- 14 个具有页面级标题的 FXML 已统一到 `PageHeader`；学习进度使用 `MetricCard`，SQL 结果空态使用 `StatePanel`。
- 页面切换不再先清空内容区；新页面完成 CSS 与布局后，以 180ms 淡入和 10px 位移进入，旧页面同步淡出。
- 全局 Loading 延迟 120ms 后才显示并带淡入淡出，快速操作不再闪现遮罩；“减少动态效果”会关闭这些动画。

这只是全量重构的架构起点，不表示 21 个页面已经完成视觉迁移。

## 7. 验收与退出标准

- 聚焦测试先覆盖 UI 资源、导航模型、SQL 控制器和新增组件契约；
- 每批完成运行完整 `mvn test`，跨依赖变更还需执行打包和 SBOM 检查；
- 以 1280×720、1600×900、亮色、暗色、高对比和 125%/150% 缩放走查主路径；
- Ctrl+K、Tab/Shift+Tab、Enter、Escape 和主要快捷键无需鼠标即可完成；
- F 批次完成后，`app.css` 不再加载，FXML 直接颜色清零，非必要固定尺寸显著收敛；
- 不改变 SQL 风险确认、权限、凭据、AI 不可信输入和确定性学习状态边界。

## 8. 关联资料

- [v2.0 总计划](2026-08-02-v2.0-computer-science-learning-platform-plan.md)
- [alpha.2 UI 应用壳 RFC](2026-08-09-v2-alpha2-ui-shell-rfc.md)
- [v1.5 UI 与易用性计划](2026-07-28-v1.5-ui-usability-delivery-plan.md)
- [v1.5.5 UI Foundation 2.0](2026-07-29-v1.5.5-ui-foundation-plan.md)
- [语义化 UI 主题重构记录](2026-07-30-semantic-ui-theme-refactor.md)

返回 [迭代计划索引](README.md)。
