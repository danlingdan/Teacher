# 2026-10-06 AI 功能优化调研（竞品对照、能力现状与 UI 实现）

> 性质：**规划输入，不代表功能已实现**。代码基线：`main` @ `63ac6c2`，`pom.xml` 为 3.9.0，最新标签
> `v3.9.0`。本文由三部分调研组成：同类软件 AI 应用公开资料盘点、本软件 AI 能力与 UI 实现的只读
> 代码走查、AI 界面交互模式参考。竞品结论来自公开文档与报道，未逐一实测产品；UI 行号基于当前
> 基线，实施前须复核。结论供后续 AI 优化计划引用。
>
> 2026-10-06 后续：其中"AI 引擎入口迁移与连接完善"已制定专项计划，见
> [顶栏 AI 引擎与数据连接完善计划](2026-10-06-topbar-ai-engine-and-connection-plan.md)（已确认
> 待实施，目标 v3.10.0）；本文其余建议（提示阶梯、KBQ-2 收尾、NL2SQL 上下文增强等）仍未排期。

## 1. 调研问题与方法

用户准备优化 AI 功能，提出两个问题：类似软件如何应用 AI；本软件怎样应用比较合适。方法：

1. 竞品公开资料盘点：国际教育/编程学习平台、数据库与 SQL 工具、国内教育生态。
2. 本软件只读走查（两路并行）：Java 侧 AI 能力（`com.sqlteacher.*`）与 `ui-web` AI 界面实现。
3. AI 界面交互模式（inline/侧栏/独立窗口、渐进披露）公开设计资料参考。

安全前提不变：模型永不执行 SQL、不持有 JDBC `Connection`；AI 只起草，确定性代码权威；
AI 与云功能失败不阻塞本地核心学习流（见 [SQL 与 AI 安全](../guide/05-sql-and-ai-safety.md)）。

## 2. 竞品 AI 应用盘点

### 2.1 教育学习平台（与产品定位最接近）

| 产品 | AI 应用方式 |
| --- | --- |
| Khan Academy Khanmigo | 学生端"AI 家教"：苏格拉底式引导提问而不直接给答案，内容锚定自有课程；教师端工具箱：教案生成、评分量规、分层差异化练习、课堂退出票等起草工具。 |
| Duolingo Max | `Explain My Answer`（讲评对错原因 + 追问）与 `Roleplay`（场景化对话练习），嵌入练习流程即时节点，不做独立聊天窗。 |
| Codecademy AI Learning Assistant | 自动感知当前课程、题目说明与学生代码，给个性化反馈与项目提示；"给提示不给答案"。 |
| DataCamp | 练习中上下文提示、代码讲解、定向反馈；DataLab AI 帮修代码/解读数据；按学习行为推荐课程。 |

### 2.2 数据库与 SQL 工具（NL2SQL 技术参考）

| 产品 | AI 应用方式 |
| --- | --- |
| DBeaver | 多供应商 BYO（含 Ollama）；NL→SQL、解释查询、解释执行计划、修复 SQL 报错、AI 描述表对象；功能可单项关闭；AI 生成的脚本按普通脚本执行（比本产品的确认门禁激进）。 |
| JetBrains DataGrip / AI Assistant | 自 2024.1 把数据库 schema 作为上下文喂给 AI 再生成 SQL；官方明确 schema 上下文是生成质量的关键杠杆。 |
| Vanna.ai | RAG 式 text-to-SQL：用 DDL、文档、问答对"训练"检索库，成功交互存为记忆供下次检索；提供 hardening 指南。 |
| SSMS + GitHub Copilot | 自然语言写 T-SQL、解释报错；质量同样依赖 schema 上下文。 |

### 2.3 国内生态

头歌 EduCoder 官方介绍将 AI 覆盖到"备课、教学、实验、评价"全闭环（学练评测一体）；通义灵码、
文心快码主要作为编码助手进入教学场景，教育特异性不强；拼题 A（PTA）未见公开的 AI 助教细节。
**国内在教学软件内把"AI 助教"做深的产品不多，是定位空档**：数据库工具都是"帮你干活"的生产力
工具，没有一个是"教你"的教学工具。

### 2.4 共性模式

1. 苏格拉底式引导/提示阶梯：答案永远在学生主动多按一下之后才出现。
2. AI 感知练习上下文（题目 + 学生作答 + 报错）再反馈，不凭空对话。
3. RAG 锚定自有内容防幻觉、带引用。
4. schema 上下文决定 NL2SQL 质量（主外键、注释、示例问答对比裸列名好）。
5. 教师端"起草工具箱"（教案/量规/变式题）：AI 起草，教师定稿。
6. 本地模型（Ollama）作为课堂隐私卖点；竞品均为云方案。

## 3. 本软件 AI 能力现状（后端）

### 3.1 功能清单

后端统一定义 4 种 AI 任务类型（`application/ai/AiTaskType.java`）：`NL2SQL`、
`SQL_ERROR_EXPLANATION`、`FEEDBACK_DRAFT`、`KNOWLEDGE_EXPLANATION`；IPC 白名单见
`desktop/bridge/AiApiSection.java` 与 `ui-web/src/shared/ipc.ts`。

| 端 | 功能 | 主要位置 |
| --- | --- | --- |
| 学生 | NL2SQL 草稿（永不自动执行） | `Nl2SqlServiceImpl` + `DefaultNl2SqlSafetyService`；UI 见 §4.1 |
| 学生 | SQL 错误 AI 讲解 | `Nl2SqlServiceImpl.explainSqlError`，提示词 `prompts/sql-error-explanation-v1.txt` |
| 学生 | 练习失败 AI 讲解草稿（只读，不写学习状态） | `ExerciseTextDraftingServiceImpl.explainFailure`，`prompts/exercise-explain-v1.txt`（≤400 字、禁止泄露参考答案） |
| 学生 | 知识助教（带引用 RAG 问答，独立子窗口） | `DefaultGroundedKnowledgeExplanationService`（≤5 条引用、引用白名单校验、确定性回退） |
| 学生 | 混合知识检索（FTS5 + 本地向量） | `DefaultHybridKnowledgeRetrievalService` |
| 教师 | 题目文本 AI 起草 | `ExerciseTextDraftingServiceImpl.draft`（经确定性校验） |
| 教师 | 作业反馈 AI 润色草稿（云端链路） | `FeedbackDraftEnhancer` + `SafeAiFeedbackDraftEnhancer`（AI 只改措辞，确定性证据权威） |
| 教师 | AI 模型配置面板 | `ai.provider.list/save/activate/deactivate/remove/test` |
| 云端 | 知识语义检索（不做生成） | `server/CloudKnowledgeIndexService`、`QdrantVectorClient`、`OllamaCloudKnowledgeEmbeddingClient`；所有生成类调用都在桌面端 BYO |

### 3.2 供应商架构

- 抽象 `AiModelProvider`，枚举 `OLLAMA | OPENAI_COMPATIBLE`；`SwitchableAiModelProvider`
  有激活的网络 profile 走网络，否则回落本地 Ollama。**网络调用失败不自动切回本地重试**；
  "停用网络 profile 即回本地"是显式开关。
- API Key 按供应商存 `data/ai-provider-keys` 下 DPAPI 加密 blob（`PersistentNetworkAiSettingsService`）。
- 统一任务层 `DefaultAiTaskService` + `AiUsagePolicy`（输入 24,000 字符/输出 8,000 字符/超时 45s）；
  除 `FEEDBACK_DRAFT` 外强制 JSON 返回；审计历史写 `data/ai-task-history.json`（不含 prompt 全文）。

### 3.3 RAG/知识库现状

- 已打通：SQLite（schema 8）+ FTS5、官方知识包分发、教师导入/修订/发布、混合检索
  （FTS5 候选 + 本地向量候选，RRF k=60）；本地向量为 `OllamaEmbeddingProvider`
  （默认 embeddinggemma，512 维）+ `LuceneKnowledgeVectorStore`，未装本地向量模型时自动降级 FTS5。
- 云端已在生产运行：Qdrant + fastembed（`BAAI/bge-small-zh-v1.5`，见
  [云服务部署](../guide/12-cloud-service-deployment.md)）。
- **搁置（KBQ-2，见 [积压清单](backlog.md)）**：本地嵌入链路未实际写入（开发机无向量模型，
  `knowledge_embedding_profiles` 0 行）、嵌入模型一致性校验、受控 overlap 分块，2026-09-18
  随 v3.6.0 移回积压。桌面 embeddinggemma(512 维) 与云端 bge-small-zh-v1.5 存在嵌入配置漂移，
  无"模型/维度/索引版本/重建状态"契约（v1.8.5 计划 §2.2）。
- 相邻搁置：`application/databaselearning` 三件套（`WebDataLabService`、
  `DatabaseModelingService` 需求→建模草稿/DDL、`LearningGoalCatalogService`）v2.1.0 引入，
  无桥接无装配，接线前需过"不可信内容"安全评估。

### 3.4 NL2SQL 链路

提示词 `prompts/nl2sql-v4.txt`（PROMPT_VERSION v4）：上下文为用户请求 + 实时表结构
（**仅列名，无主外键关系、注释、示例数据**；读取失败回落内置示例表并标注警告）+ 修订时上一版
草稿；单语句、仅 QUERY/INSERT/UPDATE/DELETE、禁 DDL/管理/文件操作、SELECT 限 500 行、只返回
JSON。安全衔接：模型只产出草稿 → Java 风险分析分级 → 前端风险条与确认门禁，`accepted` 才可复制
到工作台；拦截与生成均记录学习事件（v3.7.0 TFB-D2）。

### 3.5 已知缺口（与文档记录一致）

- KBQ-2 本地嵌入收尾（最直接的 AI 优化抓手）；嵌入契约漂移。
- NL2SQL 上下文弱（无主外键/注释/少样本）；`DataSqlPage.tsx` 注释与
  [用户手册](../guide/08-user-manual.md)关于"未配置 AI 时本地确定性生成兜底"的措辞与代码
  不一致——代码中 NL2SQL 无确定性生成兜底。
- `FileAiTaskHistoryService` 整表重写性能项（量级可感知再立项）；AI 面板英文覆盖缺口（backlog）。
- 无"SQL→自然语言"反向讲解；练习讲解为一次性成稿，无提示阶梯。
- 检索质量长期项（v1.8.5 计划）：黄金集 Recall@10≥0.85、重排器、上下文压缩、多跳。

## 4. 本软件 AI 界面实现现状（ui-web）

### 4.1 现有 AI 界面

| 界面 | 实现要点 |
| --- | --- |
| NL2SQL 助手 | `features/data-sql/DataSqlPage.tsx` 内嵌 `AiAssistant` 组件（约 933–1050 行）：受控 textarea（≤2000 字符，与 Java 侧一致）→ `useMutation` 两步 IPC（`ai.sql.preview` 本地组装上下文 → `ai.sql.generate` 带 requestId）；进行中可 `cancelLocalAppRequest` 取消；风险条 `.risk-strip risk-{level}` 展示 level/语句类型/`accepted`/原因；"复制到工作台"按钮 `disabled={!result.accepted}`，`onDraft` 直接填 Monaco；`<details class="ai-context-details">` 折叠展示上下文构成并标注"本地组装，不会外发"。 |
| 知识助教子窗口 | `KnowledgePage.tsx` 动态 `import("@tauri-apps/api/webviewWindow")` 新建 `WebviewWindow`（尺寸按显示器物理像素/缩放比自适应）；`main.tsx` 按 `#/assistant-window` hash 分叉只渲染子窗口；`useAssistantTurns.ts` 回合状态机（注释声明主窗口/子窗口共用；有未完成回合时禁止新提问）；引用为文本列表 `.assistant-citation`；`aiGenerated=false` 用 warning 色调 + "确定性回退"标题；`policy-chip`"仅使用本地课程资料"；`aria-live="polite"`。 |
| 练习 AI 讲解 | `features/editor/ExerciseFlow.tsx`：仅在提交**未通过**的反馈块内出现，调 `ai.exercise.explain`，结果用 `Feedback tone="info"`，标题"AI 讲解草稿 · {model}（仅供参考，不影响判分）"。**既有确定性三级提示阶梯**：`practice.hint` 返回 `ExerciseHint {level 1-3, text, exhausted}`，提示计数"提示 X/3"上 policy-chip，用尽禁用按钮，Monaco **F1 快捷键**已绑定提示（`EditorPage.tsx:154`，勿抢占）。 |
| 教师端 | `TeachingPage.tsx`："AI 解析"把自由文本改写为格式草稿**回填同一 textarea**，toast 提醒核对，之后仍走确定性"解析预览→自测校验→导入"；`CloudPage.tsx` + `hooks/useClassroom.ts`：每提交行一个"AI 起草"按钮（逐行 loading），草稿入可编辑 textarea 并标记 dirty，已有评语先 `ConfirmDialog`，失败 toast 提示"可直接手写评语"；`SettingsPage.tsx` AI 模型面板：折叠卡片 + 五个 mutation，凭据只写不回显，删除走 danger 确认。 |

### 4.2 可复用资产

- 共享组件 `shared/ui/index.tsx`：`Button`（`busy` 内联 spinner）、`Feedback`（四色卡片——所有
  AI 结果/错误/回退的标准呈现）、`FormField`、`Dialog/ConfirmDialog`、`EmptyState`、`Loading`、
  `Stepper`（可做阶梯可视化）、`DataTable`、`Toaster`。
- 设计 token：`App.css :root` CSS 变量（语义色板 soft/line/text 三件套、圆角、间距），深色/
  紧凑/高对比经 `.theme-dark`/`.density-compact`/`.high-contrast` 切换；无 Tailwind/CSS modules。
- 现成 AI 样式类：`.ai-panel`、`.ai-answer`、`.ai-context-details`、`.risk-strip.*`、
  `.assistant-window-page/.assistant-turns/.assistant-turn/.assistant-citation`、`.assistant-fab`、
  `.policy-chip`（深色主题已有覆盖）。
- 状态与数据：@tanstack/react-query（retry:1）+ useMutation + URL 深链；AI 调用统一模式为
  `localAppRequestWithId(method, params, uuid)` → pending 用 `Button busy` → 结果 `Feedback`
  标题带模型名与"草稿/仅供参考"免责 → `aiGenerated=false` 降级 warning"确定性回退" → 错误
  error Feedback/toast，**手写/非 AI 主路径永远可用**。
- 多回合聊天整体复用 `useAssistantTurns` + `.assistant-turns` 样式；独立窗口照抄
  `KnowledgePage.openAssistant` + `main.tsx` hash 分叉。

### 4.3 技术约束

1. **IPC 白名单三处同步**：`shared/ipc.ts` 的 `LocalAppMethod` 联合、Java 侧 `ApiSection` 子类
   `supportedMethods()` + `handle()` 分支、`shared/types.ts` 响应类型；事件另需
   `LocalAppContract.java` 的 `EVENT_TYPES` 与 `ipc.ts` 事件联合同步（契约见
   [本地 IPC](../guide/21-local-app-ipc-v1.md)）。
2. **无真流式**：`ai.delta` 通道已打通但 Java 端是完成后伪流（如 `ai.knowledge.ask` 按 240 字符
   补发切块）；真流式需改 Java 服务层边生成边 emit + 前端按 requestId 订阅（订阅范本
   `RunnerFlow.tsx:33-49` 的 disposed 防泄漏处理）。
3. 取消依赖后端在关键步骤间 `throwIfCancelled()`；前端 cancel 均 fire-and-forget。
4. 参数长度硬限制：question ≤2000、上下文字段 ≤240、作答 ≤256KB；新方法须对齐并在 UI 限长。
5. i18n 是 DOM 改写器（`shared/uiI18n.ts`，词典 + MutationObserver）而非 key-value 框架：新
   AI 文案不入词典则英文模式仍是中文；含插值的动态句子需整句入词典。
6. 布局：`min-width: 960px`，断点 1300/1100/980/900px；`.density-compact` 对 `.ai-panel` 有
   专门规则；子窗口上下文只能经 URL 参数传递（不能依赖主窗口内存状态）。

## 5. AI 交互形态参考

| 形态 | 适用 | 对 SQLTeacher 的现状与推论 |
| --- | --- | --- |
| 工作流内嵌（inline） | 摩擦最小、上下文最准； accountability 弱，需与确认门禁绑定 | 已有：NL2SQL 面板、练习讲解按钮、教师"AI 解析/起草"。适合继续承载练习场景的新功能。 |
| 常驻侧栏（copilot sidebar） | 上下文感知、持续辅助 | 未有。若做多轮引导式问答，可在练习/工作台内嵌聊天流（复用 `useAssistantTurns`），不必新建窗口。 |
| 独立窗口/页 | 空间大、任务独立 | 已有：知识助教子窗口（含显示器自适应与主题同步范式）。适合继续承载知识问答类。 |
| 渐进披露/提示阶梯 | 教学产品核心：按需展开，不过载、不代做 | 已有确定性三级阶梯骨架（level 1-3、计数、F1、禁用态），AI 化阶梯可直接挂接（见 §6 P0-1）。 |

参考：inline 与聊天面板取舍（[suhasbhairav.com](https://suhasbhairav.com/blog/inline-ai-suggestions-vs-separate-ai-chat-panel-contextual-assistance-vs-dedicated-conversation-space)）、
嵌入面板与网页聊天对比（[inferensys.com](https://inferensys.com/differences/adaptive-interfaces-and-generative-ui/conversational-ui-design-systems/webchat-ui-vs-embedded-copilot-panel)）、
聊天界面解剖与反模式（[setproduct.com](https://www.setproduct.com/blog/ai-chat-interface-ui-design)）、
学习场景渐进披露（[IxDF](https://ixdf.org/literature/topics/progressive-disclosure)、
[NN/g](https://www.nngroup.com/videos/progressive-disclosure/)）、Codecademy 助教构建记
（[codecademy.com](https://www.codecademy.com/resources/blog/behind-the-build-ai-learning-assistant)）。
这些来源提供设计判断，不证明某种形态会提高学习成效；取舍以本产品任务走查为准。

## 6. 优化建议与优先级

| 编号 | 优先级 | 建议 | 依据 | UI 接入点 |
| --- | --- | --- | --- | --- |
| AI-1 | P0 | 练习讲解改**提示阶梯**：首次只给方向性提示，点"再提示一点"逐级展开，完整讲解放最后级；沿用"仅供参考，不影响判分"免责与提示计数 | Khanmigo/Codecademy 共同核心模式；低风险高教学价值 | 扩展现有确定性三级阶梯（`ExerciseFlow.tsx` hint 块与按钮骨架、`Stepper` 可视化）；后端可扩展 `ai.exercise.explain` 或新增 `ai.exercise.hint` |
| AI-2 | P0 | **SQL 反向讲解**（"讲讲这条 SQL 在做什么"，后续可加执行计划解读） | DBeaver 已验证；教学价值高于生成 | 工作台扩展 `.ai-panel`（照抄 AiAssistant 四件套：上下文透明条 + mutation + Feedback + busy/取消/错误）；练习场景挂 `ExerciseFlow` coding-card |
| AI-3 | P0 | **重启 KBQ-2**：装本地嵌入模型（建议对齐云端 bge-small-zh 系列，一并解决漂移），定义嵌入契约（模型/维度/索引版本/重建状态），恢复本地向量链路 + 受控 overlap 分块 | 知识助教"只基于本课程资料回答、带引用"是区别于通用聊天机器人的核心卖点 | 无新 UI；收益直接体现在知识助教与检索质量 |
| AI-4 | P1 | **NL2SQL 增强**：上下文升级为主外键关系 + 表注释 + 教师审批的示例问答对（RAG 路线，检索库可建在知识库设施上）；可选一次"自检回路"——Java 侧先按 schema 校验草稿，不通过回喂重试一次（仍不执行）；修正兜底措辞与代码不一致 | DataGrip/Vanna 证明 schema 上下文与少样本是质量杠杆 | `Nl2SqlServiceImpl.preparePrompt` 上下文扩展；`.ai-context-details` 透明条同步展示新增上下文类别 |
| AI-5 | P1 | 教师端**起草工具箱**：评分量规草稿、练习变式生成（同考点换数字/场景）、实验指导书草稿；四语言 Runner 判分保持确定性，AI 只起草量规与评语 | Khanmigo Teacher Tools 模式；服务 v4.0 八学科/四语言方向 | `TeachingPage` 复用"AI 解析"回填模式（回填 + toast 核对 + 确定性校验链） |
| AI-6 | P2 | NL2SQL 加**引导模式**开关：AI 不生成 SQL，而以苏格拉底式提问引导学生自己写 | 差异化空档：数据库工具与教育软件都没做在 SQL 上 | 复用 `useAssistantTurns` + `.assistant-turns` 做内嵌聊天流；上下文沿用结构化字段（≤240）扩展 exerciseId/sessionId |
| AI-7 | P2 | 教师触发的**班级学情周报草稿**（从确定性统计起草）；按任务分级配模型（提示类小模型、NL2SQL 大模型） | DataCamp 行为推荐/分层模式；注意隐私不变量：教师触发、不放宽分析边界 | `CloudPage`/`TeachingPage` 新起草卡片；模型分级在设置面板扩展按任务偏好 |
| AI-8 | 决策 | `databaselearning` 三件套（需求→建模草稿/DDL）接线或下线 | 现成 AI 起草资产，v2.1.0 起未接线；接线前需过"不可信内容"安全评估 | 接线则按 AiAssistant 模式补 UI |

顺手项：修正 `DataSqlPage.tsx` 注释与 [用户手册](../guide/08-user-manual.md)的 NL2SQL 兜底措辞；
AI 新文案同步入 `uiI18n.ts` 词典（backlog 已记录英文覆盖缺口）。

## 7. 制定计划时的决策点

1. 产品取舍：引导模式（AI-6）是否纳入首批；AI 起草工具箱（AI-5）范围到哪一层。
2. 嵌入模型选型与桌面/云端契约统一方案（AI-3 的前置）。
3. 是否投入真流式（涉及 Java 服务层改造与 `ai.delta` 订阅），还是继续阻塞式 + 取消。
4. 提示阶梯与现有确定性三级提示（`practice.hint`）的层级关系：AI 级接在第 4 级还是替换部分级，
   `hintsUsed` 计数语义如何延续。
5. v4.0 学科扩展下，各学科 AI 功能是否归一为同一"AI 助教内核 + 按学科提示词模板"。

## 8. 竞品资料来源

- Khanmigo：[教师工具](https://support.khanacademy.org/hc/en-us/articles/14799047733645-What-teacher-tools-are-available-on-Khanmigo)、[产品页](https://www.khanmigo.ai/)
- Duolingo Max：[官方博客](https://blog.duolingo.com/duolingo-max/)、[功能解读](https://lingoly.io/explain-my-answer-duolingo/)
- Codecademy：[AI 助教构建记](https://www.codecademy.com/resources/blog/behind-the-build-ai-learning-assistant)、[功能帮助](https://help.codecademy.com/hc/en-us/articles/23400751016859-AI-Features-available-on-Codecademy)
- DataCamp：[AI Assistant 公告](https://www.datacamp.com/blog/announcing-the-new-data-camp-ai-assistant-announcing-the-new-data-camp-ai-assistant)、[DataLab AI](https://datalab-docs.datacamp.com/work/ai-assistant)
- DBeaver：[AI Assistant 文档](https://dbeaver.com/docs/dbeaver/AI-Smart-Assistance/)
- JetBrains：[AI 与数据库](https://www.jetbrains.com/help/ai-assistant/use-ai-with-databases.html)、[DataGrip 2024.1 schema 感知](https://blog.jetbrains.com/datagrip/2024/04/03/datagrip-2024-1-database-schema-awareness-for-ai-assistant-simplified-sessions-record-view-local-filtering-in-the-data-editor-and-more/)
- Vanna：[文档](https://ask.vanna.ai/docs/index.html)、[GitHub](https://github.com/vanna-ai/vanna)
- SSMS + Copilot：[Microsoft Learn](https://learn.microsoft.com/en-us/ssms/github-copilot/overview)
- 苏格拉底式 AI 教学：[arXiv 2409.05511](https://arxiv.org/html/2409.05511v1)
- 国内生态：[头歌](https://www.educoder.net/)（AI 覆盖备课/教学/实验/评价，据其公开介绍）

结论：本软件 AI 底座与竞品对齐且安全约束更严，差距在教学化深度（引导/阶梯）、RAG 本地收尾与
NL2SQL 上下文质量；最值得做的差异化是"教 SQL"而非"代写 SQL"。计划制定后从
[迭代计划索引](README.md)挂链。
