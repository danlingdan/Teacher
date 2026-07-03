# SQLTeacher 5 人团队开发计划与规范

本文档是 SQLTeacher 项目的文档入口。原来的单一大文档已经拆分为多个专题文档，便于团队成员按职责阅读、维护和执行。

## 快速入口

建议新成员按以下顺序阅读：

1. [项目范围与里程碑](guide/01-project-scope-and-roadmap.md)
2. [团队分工与协作机制](guide/02-team-collaboration.md)
3. [架构与模块设计](guide/03-architecture-and-modules.md)
4. [开发规范](guide/04-development-standards.md)
5. [SQL 与 AI 安全规范](guide/05-sql-and-ai-safety.md)
6. [测试、打包与验收](guide/06-testing-packaging-acceptance.md)
7. [交付物与风险管理](guide/07-delivery-and-risk-management.md)

## 阶段记录

- [阶段 0 技术验证报告](stage0/stage0-report.md)
- [阶段 1 验证报告](stage1/stage1-report.md)

## 项目目标

SQLTeacher 是面向数据库课程教学、实验练习和课后辅导的 Java 桌面软件。首版目标是在 16 周内完成一个可演示、可安装、可测试的 MVP，并为后续服务器版扩展保留接口。

## 首版核心链路

```text
启动软件
→ 连接 SQLite 演示库
→ 查看表结构
→ 输入自然语言
→ 生成 SQL
→ 风险检测
→ 用户确认
→ 执行 SQL
→ 展示结果
→ 记录学情
```

## 文档维护规则

- 项目范围、里程碑、优先级变更：更新 `01-project-scope-and-roadmap.md`。
- 成员职责、会议机制、接口变更流程：更新 `02-team-collaboration.md`。
- 模块边界、包结构、接口职责：更新 `03-architecture-and-modules.md`。
- Java、JavaFX、Git、日志、文档规范：更新 `04-development-standards.md`。
- SQL 执行、AI 输出、Prompt、安全策略：更新 `05-sql-and-ai-safety.md`。
- 测试策略、打包、验收指标：更新 `06-testing-packaging-acceptance.md`。
- 最终交付、风险清单、降级策略：更新 `07-delivery-and-risk-management.md`。

任何公共接口、数据库结构、AI 输出格式或 SQL 安全策略变更，都必须同步更新对应文档。
