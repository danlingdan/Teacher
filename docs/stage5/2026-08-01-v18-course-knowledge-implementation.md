# v1.8.0 课程知识中心实施记录

> 实施日期：2026-08-01
> 数据库版本：app schema 8
> Prompt 版本：`knowledge-explanation-v1`

## 已完成

- 冻结 `CourseKnowledgeArticle`、`CourseKnowledgeRevision`、`CourseKnowledgeDetail`、`CourseKnowledgeSearchFilter`、`GroundedKnowledgeAnswer` 等应用层契约。
- 追加 schema 8，保存稳定文章 ID、来源文档、身份所有者、课程与章节、可见状态、当前修订、完整正文、哈希、标题路径与知识点关联。
- 保留原 FTS5 文档索引；文章修订在事务内重建当前检索片段并追加不可变历史修订。
- 新导入条目默认私有；只有明确发布才对其他本地身份可见。已发布条目产生新修订后自动恢复私有，避免未审核版本泄漏。
- 课程知识中心支持多文件导入、修订、发布、转私有、停用、删除、正文查看、范围检索、关联练习和来源片段展示。
- 学习队列可按薄弱知识点回到知识中心；知识条目可按知识点进入现有确定性练习流程。
- `KNOWLEDGE_EXPLANATION` 仅允许 `USER_REQUEST` 与 `KNOWLEDGE_EXCERPT`；发送前由 UI 展示字符数和来源并要求确认。
- AI 输出引用编号由 Java 对本次检索集合进行白名单校验；空引用、未知引用、坏 JSON或 Provider 故障均降级为本地片段摘要。

## 有意保留的边界

- v1.8.0 不新增云端表与 API。课程知识仍为本地优先能力，避免在缺少服务端所有权与成员授权实现时上传私有全文。
- 多文件选择提供安全的批量导入；目录递归、符号链接遍历与 Office/PDF 导入未开放。
- 发布范围是同一设备上的已登录学生身份；跨设备课程知识同步后续需要独立的服务端授权设计。

## 发布验证

- `mvn test`：通过，313 项测试、0 失败、0 错误；2 项真实 AI 环境测试按设计跳过。
- `GET https://api.sqlteacher.tech/health`：HTTP 200，`status=ok`。
- `powershell -ExecutionPolicy Bypass -File packaging/package-stage1.ps1`：通过。
- `SQLTeacher-1.8.0-windows-x64.zip`：SHA-256 `bb7196b0383f69c070c99645617d52b8931cde64fc1a49630aaee17b3f48e31c`。
- `SQLTeacher-1.8.0.exe`：SHA-256 `9feda8af9df2cfeea02a4eb1b8ec1fc45a9a074981b497e096a70641562f852b`。
- `SHA256SUMS.txt` 两项均与本地文件复算一致；`target/installer` 仅有两个版本化发布附件。
- ZIP 共 323 个条目；ZIP 与应用 JAR 的运行数据扫描均为 0，未包含 `app-data`、数据库、日志、`.env`、Git 元数据或构建目录。
