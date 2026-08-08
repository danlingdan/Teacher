# SQLTeacher v2.0.0-alpha.2 阶段门禁

> 验收日期：2026-08-09
> 性质：本地预发布候选；未提交、未打标签、未推送、未发布

## 结论

alpha.2 的 UI 2.0 应用壳、任务中心、真实课程地图和通用实验工作区主路径通过本地门禁。最新稳定版本仍为 `v1.11.5`；本候选不进入稳定更新通道。

## 自动验证

| 门禁 | 结果 |
| --- | --- |
| 课程地图、路由能力、Spring/FXML/CSS 聚焦测试 | 通过 |
| 完整 `mvn test` | 427 项，0 failure，0 error，2 项按既有条件跳过 |
| `ReleaseVerificationApp` | startup、metadata、500-row query、analytics、local search 全部通过；startup 983 ms |
| Markdown 本地链接检查 | 166 个 Markdown 文件，0 个本地断链 |
| `git diff --check` | 通过 |

课程地图测试使用初始化后的真实 schema 11，确认 1 门内置课程、1 个章节、20 个 SQL 活动及知识点关系；访客路由测试确认本地任务、课程、实验和设置可用，教师能力不会泄漏。

## 人工窗口走查

| 场景 | 结果 |
| --- | --- |
| 任务中心 → 课程地图 → 活动 → SQL 工作区 | 通过；活动来自本地数据库，不是占位内容 |
| 暗色主题 | 通过 |
| 浅色主题即时切换 | 通过 |
| 840×600、150% DPI | 通过；图标侧栏和工作区侧栏折叠，SQL 编辑/执行保持可见 |
| `Ctrl+K` | 通过；打开能力过滤后的命令输入窗口 |

走查使用 `target/alpha2-ui-data` 隔离数据目录。截图和运行日志属于本地生成证据，保留在 `target/`，不进入 Git。

## Windows 产物

执行 `./packaging/package-stage1.ps1` 成功。语义版本为 `2.0.0-alpha.2`，对应 Windows
package version `2.0.1002`。

| 项目 | 结果 |
| --- | --- |
| EXE | `SQLTeacher-2.0.0-alpha.2.exe` 已生成 |
| 便携 ZIP | `SQLTeacher-2.0.0-alpha.2-windows-x64.zip` 已生成 |
| app-image | 已生成，并以隔离数据目录启动后进入访客任务中心 |
| `SHA256SUMS.txt` | 严格 2 项，重新计算全部匹配 |
| CycloneDX SBOM | 已生成 |
| 生产 Cloud URL | app-image launcher 配置包含 `https://api.sqlteacher.tech` |
| ZIP 敏感/运行数据名扫描 | 0 个 `.env`、`.secrets`、`app-data`、日志、数据库或凭据条目 |

## 限制

- alpha.2 的通用工作区只挂载 SQL Runner；非 SQL Runner 属于后续 Alpha；
- 本轮不执行 EXE 安装/卸载，不创建标签、GitHub Release 或生产部署；
- 课程地图当前只呈现本地已发布定义，不编辑课程，也不改变 Cloud API。
