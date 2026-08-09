# v2.0.0-alpha.7 阶段门禁

> 日期：2026-08-09
> 状态：已作为 prerelease 发布并部署生产 Cloud

## 功能与安全证据

- `ProjectActivityEvaluatorTest`：里程碑、证据、反思、未知标识和量规权重；
- `JdbcActivityLearningServiceTest`：内置 PROJECT 解码、版本递增、owner 隔离与私密导出确认；
- `V14CloudApiClientIntegrationTest`：教师课程包预检、许可拒绝、哈希篡改、幂等、能力协商、重复/陈旧版本冲突和隐私字段拒绝；
- `SqliteSchemaMigratorTest`：schema 16 追加、幂等、回滚和未来版本拒绝。

## 本地门禁

| 门禁 | 结果 |
| --- | --- |
| `mvn -q test -Pfast` | 418 项通过，0 失败 |
| `mvn test` | 458 项，0 失败，0 错误，2 项真实 AI 测试按设计跳过；最终代码门禁 34.746 秒 |
| `./packaging/package-stage1.ps1` | 通过；生成 EXE、ZIP、app-image、SHA256SUMS 与 CycloneDX SBOM |
| EXE | `SQLTeacher-2.0.0-alpha.7.exe`；正式发布构建的大小和 SHA-256 以 Release 附带的 `SHA256SUMS.txt` 为准 |
| ZIP | `SQLTeacher-2.0.0-alpha.7-windows-x64.zip`；正式发布构建的大小和 SHA-256 以 Release 附带的 `SHA256SUMS.txt` 为准 |
| 包内容 | 343 个 ZIP 条目；0 个 `.env`、`.secrets`、数据库、日志、凭据、`app-data` 或嵌套 `target` 条目 |
| SBOM / 云地址 | 44 个组件；启动器固定 `https://api.sqlteacher.tech` |
| app-image 冒烟 | 隔离数据目录启动成功并创建 `app.db`；测试进程已全部关闭 |
| Markdown / diff | 194 个 Markdown 文件、0 断链；`git diff --check` 无空白错误 |

## 边界

发布时必须从最终标签提交执行干净构建，并验证 JAR 的 `Build-Commit` 与标签提交一致；最终资产哈希由 Release 附带的 `SHA256SUMS.txt` 固化。项目公开分享、GitHub 集成、大文件分片上传和教师量规打分界面不在本 Alpha 的已交付范围；当前实现保留契约和隐私边界供 Beta 收敛。
