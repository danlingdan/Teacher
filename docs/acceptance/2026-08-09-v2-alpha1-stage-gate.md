# SQLTeacher v2.0.0-alpha.1 阶段门禁

> 验收日期：2026-08-09
> 性质：本地预发布候选；未提交、未打标签、未推送、未发布

## 结论

alpha.1 的通用活动内核、SQL 兼容适配、schema 11 迁移和预发布打包门禁通过。最新稳定版本仍为 `v1.11.5`；本候选不会进入稳定更新通道。

## 自动验证

| 门禁 | 结果 |
| --- | --- |
| 聚焦活动/事件/迁移/SQL/诊断/同步测试 | 通过 |
| 首次完整 `mvn test` | 425 项中 11 项失败；定位为 schema 10 手工夹具和 trigger 计数口径，未发现 SQL 安全回归 |
| 修正后受影响聚焦测试 | 通过 |
| 最终完整 `mvn test` | 425 项，0 failure，0 error，2 项按既有条件跳过 |
| `ReleaseVerificationApp` | startup、metadata、500-row query、analytics、local search 全部通过；startup 911 ms |
| Markdown 本地链接检查 | 通过 |
| `git diff --check` | 通过 |

全量测试覆盖现有 SQL 风险回归、多语句/禁止语句、练习全流程、诊断重算、Cloud 兼容和 schema
迁移。SQL 活动调用仍落到原确定性评测器；通用层没有 JDBC 执行能力。

## Windows 产物

执行 `./packaging/package-stage1.ps1` 成功。语义版本为 `2.0.0-alpha.1`，Windows package
version 为 `2.0.1001`。

| 项目 | 结果 |
| --- | --- |
| EXE | `SQLTeacher-2.0.0-alpha.1.exe` 已生成 |
| 便携 ZIP | `SQLTeacher-2.0.0-alpha.1-windows-x64.zip` 已生成 |
| app-image | `target/installer/SQLTeacher` 已生成 |
| `SHA256SUMS.txt` | 严格 2 项且重新计算匹配 |
| CycloneDX SBOM | 已生成 |
| 生产 Cloud URL | app-image launcher 配置包含 `https://api.sqlteacher.tech` |
| ZIP 敏感/运行数据名扫描 | 0 个 `.env`、`.secrets`、`app-data`、日志或数据库条目 |

本轮未执行 EXE 的真实安装/卸载，也未做 JavaFX 窗口人工走查；这些外部状态与视觉门禁留给明确授权的预发布发布动作。CLI 启动与本地运行路径已通过，打包结构和启动器配置已核验。

## 兼容与安全结论

- 空库、重复迁移、失败回滚、未来 schema 拒绝和未知活动类型拒绝通过；
- 既有 SQL 题目/会话通过迁移回填和 trigger 保持通用投影一致；
- 数据清理仍以旧 SQL 会话事实计数，通用投影不会重复计数；
- 旧事件类型继续保留，新增活动/评价器/证据字段不包含原始 SQL、凭据或无限长结果；
- 未注册活动和不匹配产物由应用层稳定拒绝，数据库也限制活动类型白名单；
- 预发布工作流会标记 prerelease 且 `latest=false`，不会替换稳定 Latest。
