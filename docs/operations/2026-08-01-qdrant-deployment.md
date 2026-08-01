# 2026-08-01 Qdrant 生产部署记录

## 结果

- 在 SQLTeacher ECS 上部署 Qdrant `1.18.3-1`，官方 Debian 包 SHA-256 为
  `bd928b6f2c2cfa04c4290353a0a51c196a7867a2d48fb85f4ce75af185c69d99`。
- 服务由 `sqlteacher-qdrant.service` 托管，使用独立的 `qdrant` 系统用户运行。
- REST `6333` 与 gRPC `6334` 仅监听 `127.0.0.1`；Nginx 未代理这两个端口。
- API key 保存在 `/etc/qdrant/qdrant.env`，权限为 `root:qdrant 0640`，未写入仓库、日志或桌面配置。
- 持久化数据、快照和审计日志分别位于 `/var/lib/qdrant/storage`、
  `/var/lib/qdrant/snapshots` 和 `/var/lib/qdrant/audit`。

## 集合与安全基线

- 集合：`sqlteacher_course_knowledge_v1`。
- 向量：768 维、Cosine 距离、Payload 落盘。
- Payload 索引：`courseId`、`visibility`，均为 keyword。
- 未携带 API key 访问集合 API 返回 HTTP 401；携带服务端密钥时集合状态为 `green`。
- Qdrant 遥测已关闭，审计日志启用并保留最近 7 个轮转文件。
- systemd 启用 `NoNewPrivileges`、只读系统目录、私有临时目录与设备、内核保护和地址族限制。

## 备份与恢复门禁

- `sqlteacher-qdrant-backup.timer` 每日触发集合快照，默认保留 30 天。
- `check-cloud-operations.sh` 同时校验 Qdrant 服务、快照定时器、就绪端点、集合状态和最新快照年龄。
- 首次快照成功，文件大小为 86,528 bytes。
- 使用最新快照恢复到临时集合后，验证集合为 green、维度为 768、两个 Payload 索引存在；随后删除临时集合并确认 HTTP 404。
- 总运维检查 `sqlteacher-operations-check.service` 最终为 `Result=success`、`ExecMainStatus=0`。

## 生产验证

- Qdrant、Qdrant 快照定时器与 SQLTeacher Cloud API 均为 active。
- `https://api.sqlteacher.tech/health` 返回 200 和 `status=ok`。
- 主机 `ss` 仅显示 `127.0.0.1:6333` 与 `127.0.0.1:6334`。
- 外部 HTTP 探针没有获得 Qdrant 响应；同步抓包显示请求没有到达 ECS 网卡。云边缘对 TCP 探针会产生假阳性，不能以单独的 TCP connect 作为公开可达证据。

## 当前边界

- 本次完成的是 Qdrant 生产基础设施、集合、安全、快照、恢复与运维门禁部署。
- 当前 Cloud 数据库中课程知识文章与切片数量均为 0，因此集合 points 数为 0。
- Cloud API 当前仍使用 SQLite 关键词降级路径；Qdrant 客户端适配器尚未接入 Embedding、Outbox 和检索请求链。启用云端语义检索前，必须先冻结服务端 Embedding Provider、维度/模型版本和授权后复核流程，不能用伪向量填充集合。
- 当前为单节点部署，不提供多节点高可用；事实来源仍是 Cloud SQLite，Qdrant 丢失时应从事实库与索引任务重建。
