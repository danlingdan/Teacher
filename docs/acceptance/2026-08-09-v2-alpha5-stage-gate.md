# SQLTeacher v2.0.0-alpha.5 阶段门禁

> 验证日期：2026-08-09
> 分支：`main`
> 版本：`2.0.0-alpha.5`
> 发布状态：本地完成门禁已通过；尚未提交、打标签或创建 GitHub Release

## 1. 功能门禁

- `SIMULATION` 活动规格、artifact、评价器、序列化和 Spring 注册已贯通；
- 计算机系统、操作系统、计算机网络各有一个 schema 14 内置课程和真实模拟活动；
- 工作区可显示状态、观察、动作、历史和检查点，并支持撤销、重置、提交与减少动态效果；
- 三个实验的正确路径均可离线提交，评价结果和学习事件能够持久化；
- 未知动作、非法转移、未完成检查点和未到达目标使用稳定原因码拒绝。

## 2. 自动化测试

聚焦命令：

```powershell
mvn -q test "-Dtest=SimulationActivityEvaluatorTest,SqliteSchemaMigratorTest,JdbcCourseMapServiceTest,JdbcActivityLearningServiceTest,DesktopFxmlResourceTest,DesktopVisualResourceTest,AppI18nTest,SemanticVersionTest,WindowsPackagingContractTest"
```

结果：通过。

完整命令：

```powershell
mvn test
```

结果：455 项测试，0 failure，0 error，2 项按既有条件跳过。跳过项均为 `LiveAiProviderSmokeTest`，原因是未设置显式的 `sqlteacher.live.ai` 联网测试开关。

该结果证明当前源码候选通过本地自动化门禁，但不替代 Windows 打包、安装、升级、标签和 GitHub Release 发布门禁。

## 3. 离线与安全结论

- 模拟评价为纯本地确定性计算，不依赖 Cloud、AI 或外部工具链；
- 模拟动作不会执行 SQL、学生代码、Shell 或网络请求；
- 规格中的无效引用和不可达目标在构造阶段拒绝；
- SQL、AI、本地代码 Runner、权限与隐私边界未改变。

## 4. Windows 候选包门禁

```powershell
.\packaging\package-stage1.ps1
```

| 项目 | 结果 |
| --- | --- |
| Windows package version | `2.0.1005` |
| EXE | `SQLTeacher-2.0.0-alpha.5.exe` |
| ZIP | `SQLTeacher-2.0.0-alpha.5-windows-x64.zip` |
| app-image | `target/installer/SQLTeacher` |
| SHA-256 | EXE 与 ZIP 复算均匹配 `SHA256SUMS.txt` |
| SBOM | CycloneDX，44 个组件 |
| ZIP | 343 个条目；0 个 `.env`、`.secrets`、`app-data`、数据库、日志、凭据或项目 `target` 条目 |
| 陈旧版本化资产 | 0；目录中恰有当前版本 EXE 与 ZIP 两项 |
| Cloud URL | launcher 包含 `https://api.sqlteacher.tech` |
| app-image | 在隔离的 `LOCALAPPDATA` 下启动成功、进程保持响应并初始化 schema 14 数据目录 |

本次 app-image 冒烟只终止本轮返回的进程 PID。隔离数据位于 Git 忽略的 `target/alpha5-smoke-localappdata`；本机命令策略拒绝递归清理该目录，因此它保留为未跟踪构建数据，不进入版本控制或发布 ZIP。

由于 Alpha5 尚未提交，本地候选 JAR 的 `Build-Commit` 为当前基线提交 `48d15da841ef989b5188627fd4198ba423c3450e`。正式发布必须在 Alpha5 提交后执行 `mvn clean` 并重新打包，确认 `Build-Commit` 与标签提交一致；本轮产物不得直接上传为 GitHub Release 资产。

## 5. 完成结论

Alpha5 计划要求的三类系统模拟、确定性检查点、稳定原因码、课程内容、离线路径、Windows 候选包和本地启动门禁均已完成。提交、推送、标签、GitHub Actions 和公开 prerelease 属于后续发布动作，不包含在本次完成授权内。

实现记录见 [Stage 13](../history/stages/stage13/2026-08-09-v2-alpha5-systems-simulation-implementation.md)。
