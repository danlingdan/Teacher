# v1.8.5 混合 RAG 与联网知识实施记录

> 日期：2026-08-01
> 应用数据库：schema 9
> 云数据库：schema 3

## 已实施

- 保留 schema 8 课程知识事实表，并新增片段元数据、可恢复索引任务、嵌入配置、阅读进度、联网来源和反馈表。
- 新导入/修订会在同一事务写入片段和待索引任务。旧 schema 8 当前版本在迁移时自动回填，索引失败不影响 FTS5。
- Ollama 批量嵌入每批最多 64 段；Lucene 索引位于 `app-data/indexes/knowledge`（或平台数据目录），不进入备份事实表或 Git。
- 混合检索以 FTS5 为稳定基线，用 RRF 合并向量候选；查询前后的身份与课程范围过滤保持在 Java 侧。
- 文档导入上限提升为 20 MiB，支持 UTF-8 文本、Markdown、PDF 与 DOCX；加密 PDF 和无可读正文文件拒绝导入。
- 联网搜索默认关闭、查询前确认；安全抓取阻断 SSRF、限制 3 次重定向、1 MiB 响应和 HTML/纯文本内容类型。
- Cloud schema 3 和 `/api/v1/v14/courses/{courseId}/knowledge` 提供所有者写入、发布可见读取与关键词降级搜索。
- 服务端提供可选 Qdrant 适配边界；生产启用需要单独配置服务地址、集合、密钥、备份与告警，不把该基础设施暴露给桌面客户端。
- `v185-rag-golden-set.jsonl` 固化首批 40 个 SQL 课程问题，评测器输出 Recall@K、MRR、拒答精度、引用覆盖率和 P95 延迟。

## 设计边界

- 向量和搜索缓存都是派生状态，随时可重建；文章修订、所有者与发布状态由 SQLite 事实表决定。
- Ollama、Brave 或 Qdrant 不可用时，界面必须显示降级状态，不伪装为完整语义检索。
- Brave 结果只作为独立标注的联网来源展示，不自动发布、不自动参与掌握度判断。
- Qdrant 生产激活属于运维步骤；本版本交付代码边界与 Cloud schema，不在没有基础设施授权时修改线上服务。

## 发布门禁

- `mvn -B -ntp test`：通过，319 项测试，0 失败、0 错误；2 项在线 AI smoke test 按设计跳过。
- 打包后类路径 smoke：Java 21、JavaFX、SQLite、MySQL 与 MariaDB 均通过；本机 Ollama 未运行，按设计报告 warning 并保持 FTS5 降级。
- `https://api.sqlteacher.tech/health`：HTTP 200，JSON `status=ok`。
- Windows 产物严格为两个：`SQLTeacher-1.8.5.exe` 与 `SQLTeacher-1.8.5-windows-x64.zip`；`SHA256SUMS.txt` 严格两行。
- ZIP SHA-256：`d19b6f99d830311f9086f01c9acbcf53dc0e62d5d3fa4505c98b76c45638ae23`。
- EXE SHA-256：`731ea94c2957ba46c576b5f8f44c07b7409ee3e5e38929a3da6c5e3b4c29dccf`。
- ZIP 条目扫描未发现 `app-data`、`.env`、Git 元数据或项目日志。命中的 `jmxremote.password.template` 与 `pkcs11cryptotoken.md` 是 JDK 21 标准运行时文件，不含 SQLTeacher 凭据。
