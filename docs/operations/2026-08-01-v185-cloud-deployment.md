# SQLTeacher v1.8.5 生产云服务部署记录

> 部署时间：2026-08-01（Asia/Shanghai）
> 生产域名：`https://api.sqlteacher.tech`
> 目标版本：`/opt/sqlteacher/releases/1.8.5`

## 上线前门禁

- 生产原版本为 `/opt/sqlteacher/releases/1.4.0`，`sqlteacher-cloud.service` 为 active/enabled。
- Cloud schema 为 2，`pragma integrity_check` 返回 `ok`，数据库大小 327,680 bytes。
- 磁盘剩余 14 GB，备份与运维检查 timer 均为 active，最近两小时无服务 warning。
- 上线前在线备份：`/opt/sqlteacher/backups/cloud-20260801T083800Z.db`。
- 备份大小：327,680 bytes；SHA-256：`3ce2e35d06eb518dd7173f07f0c0778e67a662274275c6b2f0c88c906f2d146f`；完整性检查通过。

## 部署过程

- 将提交 `6338254` 的云服务 JAR、35 个运行时依赖和运维脚本上传到隔离目录。
- 上传文件共 40 个、50,708,034 bytes；远端主 JAR SHA-256 与本地一致。
- Linux/JDK 21、SQLite/MySQL/MariaDB JDBC 预检通过；服务器无图形环境与 Ollama 不可用均为预期 warning。
- 停止服务后原子切换 `/opt/sqlteacher/current` 到 1.8.5；启动失败时预设自动回滚到 1.4.0，本次未触发回滚。
- 首次启动完成 Cloud schema 2→3 迁移。

## 上线后验证

- `sqlteacher-cloud.service` 为 active，未发生自动重启。
- 本机 `127.0.0.1:18080` 与公网 HTTPS `/health` 均返回 `status=ok`，18080 未暴露为公网监听。
- Cloud schema 为 3；数据库完整性为 `ok`。
- `cloud_knowledge_articles`、`cloud_knowledge_revisions`、`cloud_knowledge_chunks`、`cloud_knowledge_sync_cursor` 四张表均存在。
- 使用生产管理员完成登录、课程列表、共享知识新路由和登出探针；新路由对不存在课程返回预期 `INVALID_REQUEST`，未写入测试课程或知识数据。
- `sqlteacher-operations-check.service` 结果为 success，备份 service 最近结果为 success。
- systemd unit 将 Java 接收 SIGTERM 后的退出码 143 声明为正常退出，避免正常发布重启产生假告警。

## 运行边界

- 生产 Qdrant 已在后续运维步骤完成部署、安全加固、快照和恢复演练，详见
  `docs/operations/2026-08-01-qdrant-deployment.md`。
- Cloud API 仍使用 Cloud schema 3 的关键词降级路径；服务端 Embedding 与索引 Outbox 接线不属于本次基础设施部署，桌面客户端不会直连 Qdrant。
- 桌面本地语义检索使用 Ollama embedding 与 Lucene HNSW；Ollama 不可用时自动降级到 FTS5。
- 回滚点保留为 `/opt/sqlteacher/releases/1.4.0` 与上线前 SQLite 备份。
