# 2026-10-06 顶栏 AI 引擎与数据连接完善计划

> 状态：**已实施并手测通过，发布准备完成（提交/打 tag 等待用户指令）**。制定基线：`main` @ `63ac6c2`，`pom.xml` 已升 `3.10.0`（制定时为 3.9.0），
> 最新标签 `v3.9.0`。依据：[AI 功能优化调研](2026-10-06-ai-capability-optimization-research.md)
> §3/§6，及 2026-10-06 对首页、连接功能与 AI 面板的只读走查（结论吸收进本文 §2）。
> 2026-10-06 用户决策：**AI 设置并入顶栏连接 popover**（否决首页双卡方案，见 §5）；目标版本
> **v3.10.0**（版本串改动随发布流程统一进行，实施阶段未改 `pom.xml`）。
>
> 2026-10-06 实施记录：HAJ-1~6 全部实施。契约四端同步（`contracts/ipc/v1/manifest.json`、Java
> `LocalAppContract`、Rust `ALLOWED_METHODS`、TypeScript 方法联合）新增 `ai.engine.status`、
> `ai.model.list`、`ai.model.select`；`data.connection.test` 支持按 `connectionId` 直取已存配置；
> `Nl2SqlPlan` 增加 `modelUnavailable` 确定性标记；AI 面板抽为 `features/data-sql/AiEnginePanel`
> 并入顶栏弹层，设置页改跳转卡；数据页/练习页增加引擎未就绪配置引导；修正"本地确定性兜底"与
> "自动回落"两处失实文案并补 i18n 词典。验证：Java 聚焦测试（AiApiSectionTest 14、
> Nl2SqlServiceImplTest 19、DefaultNl2SqlSafetyServiceTest 5、DataSqlApiSectionTest 4、
> 桥接覆盖 2）全绿；Rust 白名单同步测试通过；`npm test` 35 文件 200 用例全绿；`npm run build`
> 通过；文档链接检查 0 断链。待办：打包态手测与 E2E，发布时回写版本串与[能力总览](../guide/00-capability-overview.md)。
>
> 2026-10-06 手测修订：AI 引擎改为**独立顶栏按钮 + 独立弹层**（连接按钮左侧），不再并入连接弹层；设置页跳转卡移除，AI 入口仅保留顶栏。
> 2026-10-06 追加（HAJ-8）：网络 AI 表单内置厂商预设（19 家，国内/国际/聚合与本地三组），选择即填端点与默认模型——傻瓜化网络 AI 配置；预设为前端清单，端点与模型仍可自由编辑。
> 2026-10-06 追加（HAJ-9）：网络供应商新增「发现模型」动作（ai.provider.models）——用端点+密钥（编辑时可借用已存密钥）拉取真实模型列表，模型名改为发现式下拉选择，测试结果与发现结果共用同一列表。
> 2026-10-06 手测通过（顶栏双按钮 / 19 家厂商预设 / 发现模型），进入 v3.10.0 发布准备：版本串已升
> 3.10.0，[发布说明](../releases/v3.10.0.md)与[能力总览](../guide/00-capability-overview.md)已回写；
> 提交、打 tag 与 CI 发布等待用户指令。

## 1. 目标、边界与成功定义

用户决策（2026-10-06 确认）：**AI 引擎（模型供应商管理）从设置页移出，并入顶栏连接 popover；
同时完善连接功能。** 解释为：顶栏「连接」弹层升级为「连接与 AI 引擎」入口（所有页面可见，含
首页），学生和教师在"开始查数、用 AI"之前，能在同一弹层完成"连接数据库"和"配置 AI 引擎"两件
装备动作；任何一步不可用时有明确的下一步，而不是事后在数据页撞到无引导的失败。

边界：

- 不改 AI 供应商后端契约与切换语义：`AiApiSection` 既有方法签名、`SwitchableAiModelProvider`
  "无激活网络 profile 才回落本地"的显式语义、DPAPI 密钥存储均不变；仅措辞修正（HAJ-6）。
- 不改 SQL 安全路径、风险分析、判分与学习权威状态。
- 连接凭据继续**只在内存会话暂存、不落盘**（`DatabaseCredentialSession` 语义保持）；不引入凭据
  落盘或加密迁移。
- 不做分步向导；不动 [积压清单](backlog.md)的表结构浏览增强项；不新增方言；不改 `data.schema`
  IPC；不引入遥测。
- 顶栏连接 chip 的唤起方式与位置保持不变，弹层内容扩展；首页不新增区块。

成功定义：未配置 AI 的用户在任意页面经顶栏弹层直接看到"当前引擎"状态并能就地完成配置与选模型；
数据页/练习页 AI 失败可一键唤起该弹层；连接的当前状态、管理、一键校验与认证恢复从顶栏一步可达；
设置页旧位置不再留空壳或死链；960×640、深色、高对比、紧凑密度与英文模式无关键回归。

## 2. 现状要点（走查结论，行号基于制定基线）

- **首页没有连接区域**：`TodayPage` 直接定义在 `App.tsx:576-674`，仅 hero-card（下一步学习）、
  metric-row（4 指标）、action-list（学习建议队列）三块。全局连接入口是顶栏
  `<TopbarConnection />`（App.tsx:357，所有页面可见）——按确认方案，AI 引擎入口落在该弹层内，
  首页不新增区块（双卡方案已否决）。
- **连接功能已较完整**（v3.4.3 CXN 傻瓜化 + v3.4.4 CTB 顶栏化）：14 方言（快速三键 + 更多类型）、
  文件浏览/新建空库、列出数据库、**测试通过才能保存**、凭据内存暂存、`JdbcFailureClassifier` 五类
  错误话术、深链 `/data?connection=`、命令面板索引连接。事实缺口：
  - 服务器型连接在应用重启后（内存凭据清空）使用即认证失败，恢复路径 = 全量编辑表单重测，没有
    "重新输入密码"的最小恢复动作。
  - 无"当前连接一键健康校验"。
  - NL2SQL 无模型时不报错，返回空草稿 + 英文 explanation（`Nl2SqlServiceImpl.java:164-181,
    402-412`），前端显示"（模型没有返回 SQL 草稿）"，**无任何指向 AI 配置的引导**。
- **AI 面板抽取成本低**：`AiModelSettings`（`SettingsPage.tsx:313-554`）自包含、无 props、状态与
  mutation 全内置，仅依赖全局 `useQueryClient`/`useToast` 与 `shared/ui` 原语；`SettingsPage.
  test.tsx:419-483` 的用例需随迁。样式类 `settings-panel/ai-provider-*` 已有（含暗色覆盖），弹层
  场景需要紧凑布局。
- **选模型能力空转**：后端 `OllamaModelSelectionService`（选择/持久化 `data/selected-ai-model.txt`、
  切换时卸载旧模型）完整存在，但 desktop bridge 无任何 `ai.model.*` IPC，前端零消费——本地引擎
  只能靠"模型名称"自由文本间接生效，用户无从选择已安装模型。
- **跨页唤起通道现成**：`features/data-sql/connectionPanel.ts` 模块级事件总线
  `openConnectionPanel()/subscribeConnectionPanel()`，DataSqlPage 侧栏与空态已在用——失败引导与
  设置页跳转卡可直接复用。
- **双入口一致性约束已有先例**：云端同步偏好双入口（`CloudSyncSettings` 与 `CloudPage`）共用同一
  IPC 与 queryKey，v3.9.0 计划 INT-3 明确"双入口共用同一状态源与确认反馈"。本计划设置页仅剩
  跳转卡，不形成平行可编辑入口。
- **文案与代码不一致**（[用户手册](../guide/08-user-manual.md)与 `DataSqlPage.tsx:942/976`）：
  "未配置 AI 时由本地确定性生成兜底"与代码不符（无确定性 SQL 生成兜底）；AI 激活 toast"网络
  不可用时会自动回落本地 Ollama"与 `SwitchableAiModelProvider` 实际语义（显式停用才回落）不符。

## 3. 交付清单与优先级

| 编号 | 优先级 | 交付物与边界 | 主要位置 | 验收证据 |
| --- | --- | --- | --- | --- |
| HAJ-1 | P0 | **AI 引擎并入顶栏连接 popover**：`TopbarConnection` 弹层在连接清单下增设「AI 引擎」分区——当前引擎状态行（HAJ-3）+ 供应商管理（复用 HAJ-2 抽取组件，紧凑变体），内容超高内部滚动；弹层语义升级为「连接与 AI 引擎」。设置页原 `AiModelSettings` 位置替换为"AI 引擎已移至顶栏「连接与 AI」弹层"跳转卡（经既有 `openConnectionPanel()` 通道唤起），不留空壳。 | `TopbarConnection.tsx`、`SettingsPage.tsx`、抽取组件、`App.css` | 任意页面从顶栏完成 AI 供应商增删改、激活/停用、测试全流程；设置页跳转有效；连接功能不回归。 |
| HAJ-2 | P0 | **AI 面板抽取共享组件**：`AiModelSettings` 抽为独立组件（能力与"凭据只写不回显"呈现不变），支持紧凑变体供弹层使用；`SettingsPage.test.tsx` 用例随迁，新增弹层挂载测试。 | 新共享组件、`SettingsPage.test.tsx`、TopbarConnection 相关测试 | 两组件挂载测试通过；密钥不回显断言保留；`ai.provider.*` queryKey 与 IPC 不变。 |
| HAJ-3 | P1 | **引擎状态与失败引导**：新增 `ai.engine.status` 只读 IPC（当前生效供应商类型/显示名、本地选定模型、Ollama 可达性，不含 endpoint 与密钥材料）；弹层 AI 分区头部显示"当前引擎：…"；数据页/练习页 AI 失败且对应模型不可用时，错误态附「打开连接与 AI 引擎」动作（`openConnectionPanel()`）；NL2SQL 生成结果补机器可读的模型不可用标记（响应 DTO 增量字段，契约内兼容）；同步修正 `DataSqlPage.tsx:942/976` 兜底文案。 | `AiApiSection`、`Nl2SqlServiceImpl` 结果 DTO、`DataSqlPage.tsx`、`ExerciseFlow.tsx`、`ipc.ts`、`types.ts` | 无模型状态在弹层可见、可就地修复；失败引导链路走通；新 IPC 白名单三处同步、契约文档更新。 |
| HAJ-4 | P1 | **模型选择 UI**：新增 `ai.model.list`/`ai.model.select` IPC 包装既有 `OllamaModelSelectionService`（切换时旧模型卸载语义不变）；弹层 AI 分区本地引擎行提供"已安装模型"下拉 + 刷新；网络供应商表单模型名支持从测试结果可用模型点选（纯前端，数据已在 `ai.provider.test` 响应）。 | `AiApiSection`、`OllamaModelSelectionService`、AI 分区组件 | 本地引擎能列出/切换已装模型并持久化；网络供应商可点选；Ollama 不可达有可理解错误态。 |
| HAJ-5 | P1 | **连接完善**：① 弹层当前连接行「测试当前连接」一键校验（复用 `data.connection.test` + 会话凭据回退，语义不变）；② 服务器型连接认证失败快捷恢复——弹层连接行「重新输入密码」最小对话框（仅密码字段，经 `data.connection.test` 验证成功即回写会话），不改凭据落盘策略；③ 新话术与 `JdbcFailureClassifier` 五类对齐。 | `TopbarConnection.tsx`、（如需）`DataSqlApiSection` | 重启后服务器型连接失败→恢复 ≤2 步；一键校验成功/失败反馈明确；文件型连接不出现密码恢复入口。 |
| HAJ-6 | P1 | **文案与 i18n**：修正激活 toast 回落措辞与 [用户手册](../guide/08-user-manual.md) AI 模型/NL2SQL 兜底描述；全部新文案入 `uiI18n.ts` 词典（含插值整句、按既有降序匹配规则），并补齐 AI 面板既有英文缺口中涉及本计划面板的部分。 | `uiI18n.ts`、弹层组件、`docs/guide/08-user-manual.md` | 英文模式新面无中文残留；文档描述与实际行为一致。 |

文档同批更新：[用户手册](../guide/08-user-manual.md)、[教师手册](../guide/09-teacher-manual.md)
（如涉及教师路径）、[本地 IPC 契约](../guide/21-local-app-ipc-v1.md)（新增 `ai.engine.status`、
`ai.model.list/select`）、[能力总览](../guide/00-capability-overview.md)（随公开发布回写）。

## 4. 明确不做

- 首页数据准备区/首页连接卡（用户已否决，AI 入口走顶栏弹层）；`TodayPage` 移出 `App.tsx` 的重构。
- 表结构浏览增强（backlog 挂起项，量级未到）；`data.schema` IPC 变更。
- 凭据落盘、加密迁移或"记住密码"选项（安全语义维持现状）。
- `SwitchableAiModelProvider` 增加"网络失败自动回落本地"（改语义需单独评审，本次只修文案）。
- 连接健康自动探活（原 P2 项，暂缓待决策）；新方言、分步连接向导、遥测。

## 5. 决策点

已确认（2026-10-06）：

1. **布局**：AI 设置并入顶栏连接 popover；首页双卡方案否决，首页不新增区块。（2026-10-06 手测修订：见头部实施记录——AI 引擎为独立顶栏按钮与独立弹层）
2. **版本**：目标 v3.10.0；版本串（`pom.xml` 两处、`ui-web/package.json`、打包清单）随发布流程
   统一变更，实施阶段不改版本号。

实施中再定：

3. ~~弹层 AI 分区的收纳方式~~ 已按"常驻分区 + 内部滚动（max-height 340px）"实施，视觉走查后可调。
4. 连接健康自动探活暂缓，待后续决策。

## 6. 验收与门禁

- 前端：`npm --prefix ui-web test`（抽取组件、弹层 AI 分区、重认证对话框、模型选择、i18n 词典
  用例）+ `npm --prefix ui-web run build`。
- Java：`mvn -q test "-Dtest=相关契约与服务测试"`（新 IPC 白名单三处同步：`shared/ipc.ts`
  `LocalAppMethod`、`AiApiSection.supportedMethods()/handle()`、`shared/types.ts`；无新增事件）；
  涉及桥接契约，跑 `mvn -q test -Pfast` 全量回归。
- 状态矩阵走查：弹层在无 AI/本地模型不可达/网络供应商激活/重启后认证失败/无连接/已连接六态，在
  浅色、深色、高对比、紧凑密度与 960×640、1280×800、125%/150% 缩放下核对。
- 打包态 E2E（桌面图形可用时）：任意页面→顶栏弹层配置 AI 引擎→NL2SQL 出草稿；弹层→选择连接
  →去查询；认证失败→重新输入密码→恢复。
- 安全核对：模型/供应商管理仍只经 IPC 白名单；`ai.engine.status` 与模型列表响应不含 endpoint
  细节、密钥材料、连接密码；`git diff --check` 与 `git status --short` 干净，无生成数据入库。

结论与执行安排以本文为准；实施时按 [AGENTS.md](../../AGENTS.md) 工作方法分批交付，每批附
验收证据至 `docs/acceptance/`，并回写本文状态。
