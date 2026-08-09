# SQLTeacher v2.0.0-alpha.6 阶段门禁

> 验证日期：2026-08-09
> 分支：`main`
> 版本：`2.0.0-alpha.6`
> 发布状态：本地完成门禁已通过；尚未提交、打标签或创建 GitHub Release

## 功能门禁

- schema 15 的五门课程均可从课程地图加载；
- 五个完整动作序列均以 `SIMULATION_PASSED` 离线通过；
- 空 artifact 分别产生软件工程、编译、离散数学、AI、安全课程专用原因码；
- 评价结果和学习事件持久化链路覆盖全部新活动；
- 所有活动只使用内置数据，不调用外部代码、模型、网络、Git、CI 或攻击工具。

## 自动化测试

聚焦命令：

```powershell
mvn -q test "-Dtest=SqliteSchemaMigratorTest,SqliteAppDatabaseInitializerTest,JdbcCourseMapServiceTest,JdbcActivityLearningServiceTest,SimulationActivityEvaluatorTest,DesktopFxmlResourceTest,DesktopVisualResourceTest,AppI18nTest,SemanticVersionTest,WindowsPackagingContractTest"
```

结果：通过。

完整命令：

```powershell
mvn test
```

结果：455 项测试，0 failure，0 error，2 项按既有条件跳过。跳过项均为 `LiveAiProviderSmokeTest`，原因是未设置显式的 `sqlteacher.live.ai` 联网测试开关。

日常快速反馈命令：

```powershell
mvn test -Pfast
```

热态结果：415 项测试，0 failure，0 error，耗时 9.055 秒；`integration`、`runner` 和 `live` 共 40 项由 profile 排除。默认 `mvn test` 仍执行完整 455 项，发布门禁没有缩减。

## 安全结论

- 无公网攻击、扫描、凭据尝试或主机逃逸路径；
- 无真实模型调用，AI 不参与评价、掌握度或推荐决策；
- 无真实 Git/CI/编译器/Shell 执行；
- SQL、AI、权限、凭据、隐私和本地 Runner enforcement 未改变。

## Windows 候选包门禁

```powershell
.\packaging\package-stage1.ps1
```

| 项目 | 结果 |
| --- | --- |
| Windows package version | `2.0.1006` |
| EXE | `SQLTeacher-2.0.0-alpha.6.exe`；SHA-256 `6b92756b82cd7fbe9b163060d1ff81fa311281eb82f57238f87c1efe6a348134` |
| ZIP | `SQLTeacher-2.0.0-alpha.6-windows-x64.zip`；SHA-256 `477694357ceeecafc62bde4c7cb3aa230c32347c59d1613d5b25a4532417e4e5` |
| app-image | `target/installer/SQLTeacher` |
| 校验和 | EXE 与 ZIP 复算均匹配 `SHA256SUMS.txt` |
| SBOM | CycloneDX，44 个组件 |
| ZIP | 343 个条目；敏感、运行数据和项目 `target` 扫描结果为 0 |
| 陈旧版本化资产 | 0；目录中恰有当前版本 EXE 与 ZIP 两项 |
| Cloud URL | launcher 包含 `https://api.sqlteacher.tech` |
| app-image | 在隔离的 `LOCALAPPDATA` 下启动成功、进程保持响应并创建 `auto-before-schema-15-*` 迁移备份 |

隔离启动数据位于 Git 忽略的 `target/alpha6-smoke-localappdata`，不会进入提交或发布 ZIP。由于 Alpha6 尚未提交，本轮 JAR 的 `Build-Commit` 仍指向基线提交 `48d15da841ef989b5188627fd4198ba423c3450e`；正式发布必须在提交后执行 `mvn clean` 并重新打包，本轮产物不得直接上传。

## 完成结论

Alpha6 的五条课程路径、确定性原因码、离线持久化、安全边界、完整测试和 Windows 本地候选包门禁均已完成。提交、推送、标签、Actions 和公开 prerelease 属于后续发布动作。

实现记录见 [Stage 14](../history/stages/stage14/2026-08-09-v2-alpha6-professional-foundations-implementation.md)。
