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
- 向量：512 维、Cosine 距离、Payload 落盘；与 `BAAI/bge-small-zh-v1.5` 固定匹配。
- Payload 索引：`courseId`、`visibility`，均为 keyword。
- 未携带 API key 访问集合 API 返回 HTTP 401；携带服务端密钥时集合状态为 `green`。
- Qdrant 遥测已关闭，审计日志启用并保留最近 7 个轮转文件。
- systemd 启用 `NoNewPrivileges`、只读系统目录、私有临时目录与设备、内核保护和地址族限制。

## 备份与恢复门禁

- `sqlteacher-qdrant-backup.timer` 每日触发集合快照，默认保留 30 天。
- `check-cloud-operations.sh` 同时校验 Cloud 语义索引状态与积压、FastEmbed 模型、Qdrant 服务、快照定时器、就绪端点、集合状态和最新快照年龄。
- 首次快照成功，文件大小为 86,528 bytes。
- 切换前先生成 Cloud SQLite 在线备份与 Qdrant 快照；当且仅当生产集合 points 为 0、知识文章和切片均为 0 时，才将集合由旧的 768 维重建为 512 维。
- 使用切换后的最新快照恢复到临时集合后，验证集合为 green、维度为 512、两个 Payload 索引存在；随后删除临时集合并确认 HTTP 404。
- 总运维检查 `sqlteacher-operations-check.service` 最终为 `Result=success`、`ExecMainStatus=0`。

## 生产验证

- Qdrant、FastEmbed、Cloud API 和三个运维定时器均为 active。
- `https://api.sqlteacher.tech/health` 返回 200、`status=ok`、`knowledgeIndex=ready` 和 `knowledgeIndexBacklog=0`。
- 主机 `ss` 显示 Qdrant `6333/6334`、FastEmbed `11434` 与 Cloud API `18080` 均仅绑定 loopback（Java 可能显示为 IPv4-mapped IPv6 loopback）。
- 外部 HTTP 探针没有获得 Qdrant 响应；同步抓包显示请求没有到达 ECS 网卡。云边缘对 TCP 探针会产生假阳性，不能以单独的 TCP connect 作为公开可达证据。

## Cloud 语义检索闭环

- Cloud schema 4 增加事务 Outbox 与嵌入画像；发布知识时事实、切片和索引任务在一个 SQLite 事务内提交。
- `sqlteacher-embedding.service` 使用 Qdrant FastEmbed `0.8.0` 和 `BAAI/bge-small-zh-v1.5`，离线加载本机模型缓存，只监听 `127.0.0.1:11434`，批量上限为 64。
- 后台任务生成 passage embedding 后幂等 upsert Qdrant；查询使用 query embedding，并以 RRF 合并 SQLite 关键词与向量候选。Embedding 或 Qdrant 失败时自动降级为关键词检索。
- Qdrant 查询前附加课程与可见性 Payload filter，返回后再以 Cloud SQLite 的当前修订、所有者和发布状态复核授权。
- 管理接口提供索引状态和显式重建；`/health` 暴露 `knowledgeIndex` 与积压数量，运维门禁限制积压不超过 100。
- 隔离端到端环境真实验证 2 个向量点：非关键词同义查询命中已发布内容，学生可读取已发布内容但无法得到 PRIVATE 内容；测试数据库、集合与临时服务随后全部清理。
- 正式 Cloud 当前尚无课程知识文章，因此生产集合 points 仍为 0；后续发布会自动进入 Outbox 并建立向量。

## 当前边界

- 当前为单节点部署，不提供多节点高可用；事实来源仍是 Cloud SQLite，Qdrant 丢失时应从事实库与索引任务重建。
- 本次只推送后端与运维代码，不创建新的版本标签、安装包或 GitHub Release。
