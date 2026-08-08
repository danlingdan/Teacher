# v2.0 alpha.2 UI 2.0 应用壳实施记录

> 实施日期：2026-08-09
> 版本：`2.0.0-alpha.2`

## 已完成

- 应用壳按学习、课程、实验、教学、系统重排，增加始终可见的页面上下文栏；
- 抽出 `ShellRoute`、`ShellWorkspace` 和能力感知的 `ShellNavigationModel`，键盘快捷键和命令检索复用同一路由表；
- 首页改为身份任务中心，继续使用真实的本地诊断、学习队列、任务和反馈入口；
- 新增 JDBC 课程地图查询，直接聚合 alpha.1 发布课程、章节、活动和知识点；
- 新增课程地图页和通用实验工作区，课程活动可定位到工作区，中心挂载现有 SQL Runner；
- 工作区按宽度折叠活动目录和检查器，840×600 下优先保留 SQL 编辑、执行和结果路径；
- 新页面样式加入现有语义 CSS 分层，亮暗主题无需重启即可切换；
- 页面路由改为先布局、后交叉淡化与轻微位移，不再暴露内容区空白帧；
- 全局 Loading 增加延迟显示和淡入淡出，快速任务不展示遮罩，并遵循“减少动态效果”偏好。

## 保留边界

SQL Runner 和原 SQL 页面控制器没有获得活动目录或 JDBC 之外的新权限；AI 仍只生成草案，SQL 安全服务继续决定验证、确认和执行。课程地图为只读本地查询。非 SQL 活动没有可运行入口，属于后续 Alpha。

## 主要实现位置

- `application/course/`：课程地图只读合同；
- `infrastructure/database/JdbcCourseMapService.java`：schema 11 聚合查询；
- `desktop/navigation/`：应用壳路由模型；
- `desktop/navigation/PageTransitionCoordinator.java`：无空白帧的页面过渡与动画降级；
- `desktop/component/GlobalLoading.java`：延迟显示和淡入淡出的全局加载反馈；
- `fxml/course-map.fxml`、`fxml/activity-workspace.fxml`：课程与实验主路径；
- `css/components/pages.css`：应用壳、课程卡片和响应式工作区表面。

验证证据见 [alpha.2 阶段门禁](../../../acceptance/2026-08-09-v2-alpha2-stage-gate.md)。
