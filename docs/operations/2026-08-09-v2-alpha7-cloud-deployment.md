# SQLTeacher v2.0.0-alpha.7 云端部署记录

> 部署时间：2026-08-09（Asia/Shanghai）
> 生产域名：`https://api.sqlteacher.tech`
> 发布标签：`v2.0.0-alpha.7`
> 发布提交：`f997080a8b9ed4a67836841073004fe93318cb1b`
> 目标目录：`/opt/sqlteacher/releases/2.0.0-alpha.7`

## 上线前门禁

- 本地从发布提交执行 `mvn clean test`：458 项测试，0 失败，0 错误，2 项真实 AI 测试按设计跳过。
- Windows 正式构建的 JAR `Build-Commit` 与发布提交一致；校验和、敏感条目扫描、44 组件 SBOM 和 app-image 启动冒烟通过。
- GitHub Actions Release 工作流在标签提交上重新执行完整测试和打包，最终成功发布 5 个资产。
- 生产原版本为 `/opt/sqlteacher/releases/1.11.1`，服务 active/enabled，Cloud schema 为 5，公网和回环健康检查正常。
- 上线前在线备份为 `/opt/sqlteacher/backups/cloud-20260809T081040Z.db`，`pragma integrity_check` 返回 `ok`，SHA-256 为 `f7c0d8f85441862893457d0df86b39e0eec5ddec604b0b74c56e79b0f150b943`。

## 部署过程

- 生成 44 个运行时依赖和 `Teacher-2.0.0-alpha.7.jar` 的云端部署包；本地与远端 SHA-256 均为 `142e4e6bb87a9305732939ec3aff213d20e145cf94e736f14e0e00b773e8fa2c`。
- 将部署包解压到版本目录，整理为 `app/Teacher-2.0.0-alpha.7.jar`、`app/lib/*` 和 `bin/*` 布局，并检查启动脚本语法。
- 原子切换 `/opt/sqlteacher/current` 到 `2.0.0-alpha.7`，重启 `sqlteacher-cloud.service`；本次一次成功，未触发回滚。
- schema 迁移只追加版本 6；旧 release 和上线前 schema 5 备份均保留，回滚时必须同时恢复二者。

## 发布与更新通道

- GitHub Release 为已发布的 prerelease：`https://github.com/danlingdan/Teacher/releases/tag/v2.0.0-alpha.7`。
- Release EXE SHA-256 为 `71def0df23042b903113578161f6bcbacd12d8eab88503b47951f5974ca0f243`；便携 ZIP SHA-256 为 `6977a3be04b7ee60803dec723c5ba745a78be1477c9f4339125c3bf38a8c7ed3`，与随附 `SHA256SUMS.txt` 一致。
- Alpha 更新清单使用 `release-2026-01` 密钥签名，Ed25519 验证通过，通道为 `alpha`、发布比例 100%。
- 稳定桌面客户端只接受 `stable` 通道，因此没有用 Alpha 清单覆盖生产更新源；生产仍提供已签名的 `1.10.1 stable` 清单，Alpha 用户从 prerelease 页面显式下载。

## 上线后验证

- `sqlteacher-cloud.service` active，`/opt/sqlteacher/current` 指向 `releases/2.0.0-alpha.7`。
- 回环和公网 HTTPS `/health` 均返回 `status=ok`；兼容健康端点仍报告 `apiVersion=1.11`。
- 回环和公网 `/api/v1/app/capabilities` 均报告 `apiVersion=2.0`，并包含 `COURSE_PACKAGE_V2`、`ARTIFACT_SYNC_V2`、`EXPLICIT_SYNC_CONFLICTS`、`PROJECT_METADATA_SYNC`。
- Cloud schema 为 6，生产数据库 `pragma integrity_check` 返回 `ok`；18080 仅监听 `[::ffff:127.0.0.1]:18080`。
- `sqlteacher-operations-check.service` 现场执行成功；运维检查、数据库备份和证书定时器均 active，最近服务日志未发现新增 error、exception 或 failed 记录。

## 回滚点

- 应用回滚点：`/opt/sqlteacher/releases/1.11.1`。
- 数据回滚点：`/opt/sqlteacher/backups/cloud-20260809T081040Z.db`。
- 因旧版本只支持 schema 5，回滚必须先停止服务、恢复该备份，再原子切回旧 release 并重新验证回环和公网健康端点。
