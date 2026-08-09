# v2.0.0-rc.1 发布冻结门禁

> 日期：2026-08-09
> 状态：本地 RC 候选；未提交、未发布、未部署

## 冻结结论

- `2.0.0-rc.1` 不新增功能、活动类型、schema、Cloud 主端点或顶层 UI 架构。
- 当前缺陷清单中没有已确认的 P0/P1 阻断项；发现新的阻断问题时必须修复并递增 RC 序号。
- RC 更新通道、完整测试、候选包与安全扫描由本文件记录；外部人工/预生产门禁不会用本地自动化结果替代。

## 本地自动化门禁

| 门禁 | 结果 |
| --- | --- |
| RC 更新通道聚焦测试 | `SecureUpdateServiceTest,WindowsPackagingContractTest` 通过；RC 接受 `rc`/`stable`，拒绝 `beta` |
| `mvn clean test` | 465 项，0 失败，0 错误；2 项真实 AI 测试按设计跳过；总耗时 41.429 秒 |
| 迁移、性能、安全、课程包与 Runner 恶意夹具 | 全部纳入完整测试并通过；5,000 事件诊断用例耗时 0.219 秒 |
| `ReleaseVerificationApp` | 通过；启动 3,862 ms / 5,000 ms，元数据 11 ms、500 行查询 13 ms、分析 6 ms、本地检索 6 ms，单项操作上限 2,000 ms |
| Windows EXE / ZIP / app-image / SHA-256 / SBOM | 打包通过；EXE、ZIP、app-image、两行校验清单和 44 组件 SBOM 完整，无陈旧版本资产 |
| EXE | `SQLTeacher-2.0.0-rc.1.exe`，109,195,264 bytes，SHA-256 `5965d7195555f737d5acf83651dd034980dc35ec5375a966eba230fa5cef7458` |
| ZIP | `SQLTeacher-2.0.0-rc.1-windows-x64.zip`，116,479,351 bytes，SHA-256 `d6b970a2e86dde3c3a6e779d1d1aee14b8dcb3ae39981e205166f1fe1581fc5d` |
| ZIP 敏感内容与运行数据扫描 | 343 个条目；`.env`、`.secrets`、`app-data`、数据库、日志、凭据、私有课程和意外 `target` 命中 0 |
| 生产 API 配置与 app-image 启动冒烟 | CFG 指向 `https://api.sqlteacher.tech`；隔离目录启动成功并初始化 schema 17，启动进程树已停止 |
| 版本元数据 | JAR `Implementation-Version=2.0.0-rc.1`；Windows 包版本 `2.0.3001`；未提交工作树的 `Build-Commit=4f52d25e2aa7137a9af98972b410ab69a5b97c46` |
| Markdown 链接与 `git diff --check` | 205 个 Markdown 文件、0 个失效本地链接；差异空白检查通过 |

## 发布前外部门禁

| 门禁 | 当前状态 |
| --- | --- |
| Windows 10/11，100%/125%/150%，亮/暗/高对比度人工走查 | 待在目标设备执行 |
| 离线、弱网和代理人工走查 | 待在目标网络条件执行 |
| 从 v1.11.5、最后稳定 1.x、Beta 安装包的真实安装升级/失败恢复 | 待在已提交候选包执行 |
| Cloud 2.0 预生产权限、限流、幂等、备份恢复、兼容与 HTTPS | 待有预生产环境后执行 |
| 签名更新清单、标签、GitHub Actions 与公开 prerelease | 未授权，本轮不执行 |

## 边界

当前工作树同时包含尚未提交的 Beta 累积变更，因此本地包的 `Build-Commit` 仍指向工作树基线，不能直接作为公开 Release 资产。正式提交或发布后必须从候选提交重新执行 `mvn clean`、打包、元数据核对与升级验证。
