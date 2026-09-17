# 迭代积压清单

本文件是跨版本积压的**唯一活跃索引**。各计划"移至后续版本"章节的条目随计划收口汇入此处；启动某项时从本清单领取、写回对应计划，完成后在本文件标记去向。条目保持挂起时的原始理由，不因时间推移改写。

来源计划：[v3.4.2](2026-09-15-v3.4.2-plan.md) · [v3.4.1](2026-09-15-v3.4.1-plan.md) · [v3.4.0](2026-09-14-v3.4.0-optimization-plan.md)。

## 功能与产品决策类

| 条目 | 来源 | 挂起理由 / 重启条件 |
| --- | --- | --- |
| databaselearning 三件套处置（接线或下线） | v3.4.2 排查 | `WebDataLabService`（网页表格数据预览 + INSERT 草稿）、`DatabaseModelingService`（需求 → 建模草稿/DDL）、`LearningGoalCatalogService`（学习目标目录）——v2.1.0 引入，现无桥接、无 Spring 装配、无消费方（289 行生产代码 + 126 行测试仍随 `mvn test` 执行）。接线前 WebDataLab 须先过"不可信内容"安全评估；下线则删除并归档测试。需产品决策。 |
| `editor.languages` 端点清理 | v3.4.2 排查 | 前端已静态 import monaco 语言定义，IPC 闲置。合同策略 `additive-within-v1` 不允许 v1 内删方法，待 IPC 合同升版（v2）时随版删除。 |
| 首次启动一次性隐私提示 | v3.4.2 | 非阻塞、可关闭；当前 PRIVACY 全文已经设置页帮助与关于面板可达，无强制同意诉求。 |
| 反馈截图附件 | v3.4.2 | `ScreenshotAttachment` 域模型与服务端已支持（PNG/JPEG、2 MiB、须无 EXIF），但 v3 桥 1 MiB IPC 请求上限装不下——需放宽上限或加图片压缩管线。 |
| 卸载时"是否删除用户数据"选项 | v3.4.2 | 与 PRIVACY"卸载不静默删除"互补的用户主动选项；涉及 NSIS 卸载钩子。 |
| 安装器多语言与 Windows 文件属性完善 | v3.4.2 | 收益低；`bundle.copyright` 已随 v3.4.2 计划落地。 |
| 软著新基线申报材料 | v3.4.2 | 以 3.4.x 新基线重做申报材料，走 [docs/copyright](../copyright/README.md) 既有流程；登记信息获批后关于页替换占位。 |
| 班级码增强：有效期、停用开关、一课多班多码、加入审批流 | v3.4.1 | v3.4.1 已交付最小班级码（CLS）；增强项待教学反馈后按需立项。 |
| 知识页课程树活动可点击跳转 | v3.4.1 | KNW-1 取"移除"方案；若要"可点"需评估 `learning_activity_definition.source_kind='SQL_EXERCISE'` 到练习页的跨页路由设计。 |
| 表结构浏览增强：按表懒加载列、虚拟滚动、表名点击插入编辑器、右键查看表数据 | v3.4.1 | 教学库表数量级小，v3.4.1 的过滤 + 默认收起已覆盖诉求；表达数百规模再议（届时需扩展 `data.schema` IPC 与 `JdbcDatabaseMetadataService`）。 |

## 架构与技术债类（v3.4.0 §7 移交 3.5.0+）

| 条目 | 来源 | 挂起理由 / 重启条件 |
| --- | --- | --- |
| app.db 全局 `foreign_keys` 开启 | v3.4.1 | 迁移链 1–23 已大量使用外键，风险预计低，但全局开启影响 app 库全部读写路径，需独立审计后作为独立小版本处理。 |
| "3.5.0 数据迁移版本"：迁移链 1–22 压缩、`SqliteInstantFormat` Timestamp 回退移除、云端 V\* store 改名 | v3.4.0 | 前两项是高风险 schema 改动，计划本身即建议独立成版本；V\* store 改名为纯可读性项。 |
| 学习队列 N+1 与 interventions 批量端点、V14CloudStore 行级 N+1、干预状态机产品规则 | v3.4.0 | 需服务端新端点与跨端设计，或产品规则决策。 |
| `uiI18n` 调用点 key 化字典（REF-15 第二步） | v3.4.0 | 373 条表 × 跨全部 feature 的机械改写属高风险批量；v3.4.0 已交付去重 + 构建期完整性测试，key 化待评估。 |
| surefire 按类并行（TST-8 第二项） | v3.4.0 | 与 SQLite 临时目录 / WSL runner 测试存在资源竞争风险，暂不启用。 |
| Monaco 体积优化（register-\*.js 2.66MB） | v3.4.0 | 收益/风险比一般，挂起。 |
| `FileAiTaskHistoryService` 整表重写 | v3.4.0 | 性能项；若历史任务量级可感知再立项。 |
| 本地嵌入链路打通 + 嵌入模型一致性校验（v3.6.0 KBQ-2，含受控 overlap 分块） | v3.6.0 | 用户确认本机 Ollama 未安装向量模型，语义检索暂无运行对象；安装向量模型（如 embeddinggemma）后从本条启动，届时一并启用分块 overlap。 |

返回 [迭代计划索引](README.md) · [文档中心](../README.md)。
