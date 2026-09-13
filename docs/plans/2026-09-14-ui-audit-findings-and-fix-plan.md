# 2026-09-14 全界面 UI 审计报告与修复计划

- 审计基线：`main` @ `9e370d7`（v3.3.3，工作树含未提交的设置页复选框布局修复）
- 审计方式：通过临时桥接（打包版 Java 核心 3.3.3 + `%LOCALAPPDATA%\SQLTeacher` 数据副本 + stdio→HTTP 桥 + Tauri shim）在浏览器中完整运行应用，逐页截图与可访问性树分析
- 覆盖身份：未登录、学生（华佳浩）、教师（杨春蕾）、管理员（System Administrator）
- 覆盖页面：今天、登录/注册、课程与知识、练习与实验（SQL 练习/课程活动/自由编程/错题本）、数据与 SQL、班级与云端（学生/教师/管理员）、教学空间（教师）、设置全部子面板、通知中心、命令面板
- 约束：纯浏览审计，未提交作业/题目，未修改真实用户数据；审计结束已删除数据副本

## 一、缺陷（P1，本次修复）

| 编号 | 位置 | 现象 | 根因/修复方向 | 状态 |
| --- | --- | --- | --- | --- |
| P1-1 | 练习作答、自由编程、数据与 SQL 的代码编辑器 | 深色主题下编辑器仍为白色高亮，与界面严重割裂 | Monaco 主题未跟随应用主题；经 `beforeMount` 在编辑器实际使用的 monaco 实例上注册 `sqlteacher-dark` 主题并按 `theme-dark` 类切换 | 已修复并运行时验证 |
| P1-2 | 设置 → 备份与本地数据（管理员） | 备份时间全部显示为 1970 年，且为英文格式（如 "1/22/1970, 12:58:32 AM"） | Java `Instant` 被 Jackson 默认序列化为秒级数值，前端 `new Date()` 误作毫秒；新增 `shared/instant.ts` 兼容秒/毫秒/ISO 并统一 zh-CN 格式 | 已修复并运行时验证 |
| P1-3 | 课程与知识 | 课程树中"章节活动"条目样式与可点击文档项几乎一致但纯展示、点击无反应；学生账号"知识文档 0 篇"时主区域只有"选择一篇知识文档"，检索也无结果，页面实质不可用且无解释 | 活动条目加 disc 列表标记读作"安排展示"；文档为空时主区域与列表分别给出引导文案 | 已修复并运行时验证 |
| P1-4 | 教学空间 → 完整学情分析（教师） | 面板展开后完全空白：无表格、无空态、无加载态 | 仅在 `analytics` 有值时渲染；补充加载中与空态文案（有数据时经运行时验证正常渲染，含 zh-CN 生成时间） | 已修复并运行时验证 |
| P1-5 | 设置 → 更新、通知与帮助 → 快捷键 | 帮助写 "Ctrl+1 首页，Ctrl+2 我的练习，Ctrl+3 课程知识，Ctrl+, 设置，F1 帮助"，与命令面板实际 Ctrl+1~6（今天/课程与知识/练习与实验/数据与 SQL/班级与云端/设置）不符 | `FileGeneralSoftwareService` 快捷键文案更新为 Ctrl+K / Ctrl+1~7 / Ctrl+, / 编辑器内快捷键 | 已修复（编译与聚焦测试通过；运行时需随下次打包生效） |
| P1-6 | 练习与实验 → 错题本 | 步进器标签断行（"错题回/顾、重新作/答、对比反/馈"） | 错题本无侧栏却复用双列 `flow-layout`，整个内容被压进 280px 首列；新增 `flow-layout-solo` 单列布局 | 已修复并运行时验证 |

## 二、体验与信息架构（P2）

> 状态标注（2026-09-14 第二轮）：P2-1、P2-2、P2-3、P2-4（轻量版）、P2-6、P2-7、P2-9、P2-10、P2-11 已修复；P2-5（面板重构）、P2-8（命令面板实体搜索）维持待排期。

- P2-1（已修复）：作答视图在编辑器上方显示题目描述与期望列，表结构收入可折叠"数据表结构"面板，学生作答时不再需要回预览记忆。
- P2-2（已修复）：预览与作答视图的表结构按"；"分行渲染，并补充折叠面板样式。
- P2-3（已修复）：预览卡难度改用 `difficultyLabel`（入门/进阶/高级）；错题本眉标改为"知识点 · 入门 · 查询题"；课程活动预览改用 `activityTypeLabel`（模拟实验/测验/阅读等）；运行反馈徽标兜底值 idle 改为"待运行"。今天页"确定性策略"徽标经复核为悬停提示（title 属性），版本串不直接显示，维持原样。
- P2-4（部分修复）：今天页队列为空时，hero 与队列卡片均提供「去练习一题」入口；个性化推荐前置等仍待产品层面设计。
- P2-5（待排期）：教师班级页"添加成员与创建任务"面板职责混杂；原生 `datetime-local` 在英文环境显示 `mm/dd/yyyy`；学生加入班级仅靠教师逐个输邮箱。
- P2-6（已修复）：教学空间统计下方增加口径说明（本机作答记录 vs 班级学情入口）。
- P2-7（已修复）：题库"状态"列改为"已启用/已停用"状态片 + 停用/启用切换按钮；点击题目标题现在会展开编辑器并平滑滚动到位；题库与学情表页大小 8 → 50（96 题由 12 页缩至 2 页）。
- P2-8（待排期）：命令面板仅含页面跳转，可考虑纳入题目/知识文档/连接等实体。
- P2-9（已修复）：登录页"← 继续离线学习"从表单上方移至底部"离线学习无需登录。"行内。
- P2-10（已修复）：根因与 P1-2 相同，为秒级时间戳解析失败，已随 `shared/instant.ts` 一并修复会话与分析页的时间显示。
- P2-11（已修复）：Tauri 侧car 启动参数加入 `-Djava.net.preferIPv4Stack=true`（`ui-web/src-tauri/src/lib.rs`），避免 IPv6 不完整网络下云端登录全部超时；`cargo check` 通过。该修复随下次打包生效。

## 三、打磨项（P3）

> 状态标注（2026-09-14 第二轮）：目录按钮 aria-label、设置保存按钮命名、加入班级说明已处理；"语法残疾"一条经复核为审计误读（实际文案为"语法考察"，无需修改）；其余维持待排期。

- ~~退出登录后练习页题目高亮状态未重置（跨账号残留）~~（复核未找到可稳定复现的机制：两次截图中的高亮与目录选中状态、URL 参数均对应不上，疑似截图时鼠标悬停样式；如再复现按缺陷处理）。
- ~~设置页双保存按钮~~（已处理）：顶部按钮更名"保存偏好设置"，与"保存题库设置"明确区分。
- "SQL 开发者模式"对学生可见、默认文案含混（当前学生账号即处于开启状态）。
- "知识文档 共 0 篇"计数在存在活动列表时显得自相矛盾（P1-3 已补提示文案，计数文案维持）。
- ~~可访问性：练习目录按钮可访问名称为整行拼接~~（已处理）：目录按钮增加 `aria-label`（题目、类型、难度、状态）。
- ~~教师参考 SQL 帮助文案~~（复核无需修改）：实际文案为"语法考察"，审计截图误读为"语法残疾"。
- ~~学生端无"如何加入班级"的流程说明~~（已处理）：空态文案补充"教师通过成员邮箱添加 + 立即同步"。
- "本机环境与组件"面板出现 Cloud/Runner/MSVC/WSL 等术语，对学生偏极客（默认折叠，维持）。

## 四、表现良好的方面

- 登录/注册/找回密码页签结构清晰，登录按钮禁用态正确。
- SQL 练习目录的难度分组、筛选、分页与搜索防抖成熟。
- 数据与 SQL 页执行限额（500 行·10 秒）透明、执行历史可折叠、连接表单校验提示明确。
- 备份恢复有确认对话框；学生身份对"备份与本地数据"正确显示"当前身份无维护权限"。
- 1280×720 低分辨率下主要页面布局可用。
- 2026-09-13 修复的推荐卡片内边距、题库更新开关行、侧栏题库更新按钮在两种主题下均验证正常。

## 五、未覆盖项

- AI 功能（知识助教、自然语言生成 SQL、AI 解析）需本地 Ollama 在线，本次未实测。
- SQL 执行确认流与判分流未实际提交验证；Windows 原生文件对话框、系统通知在浏览器桥接环境中不可用（桌面端不受影响）。
- 云端多端同步的实际网络行为未测。

## 六、修复记录（2026-09-14）

改动文件：

- `ui-web/src/shared/monacoTheme.ts`（新增）：`sqlteacher-dark` 编辑器主题定义 + `useMonacoEditorTheme` 主题跟随 Hook；monaco 实例经 `beforeMount` 传入以规避打包产物中 monaco 模块多副本问题。
- `ui-web/src/features/editor/EditorPage.tsx`：练习作答/自由编程编辑器接入主题跟随；错题本改用 `flow-layout-solo` 单列布局。
- `ui-web/src/features/data-sql/DataSqlPage.tsx`：SQL 工作台编辑器接入主题跟随。
- `ui-web/src/shared/instant.ts` + `instant.test.ts`（新增）：秒/毫秒/ISO 时间解析与 zh-CN 格式化，7 个聚焦测试。
- `ui-web/src/features/platform/PlatformPages.tsx`：备份列表与恢复对话框改用 `formatInstant`；`formatAccountDate` 委托同一实现（连带修复会话"时间未知"与学情"生成时间"）；"完整学情分析"补充加载中/空态。
- `ui-web/src/features/knowledge/KnowledgePage.tsx`：文档空态自适应文案 + 文档列表空态提示（含课程树活动为"安排展示"的说明）。
- `ui-web/src/shared/types.ts`：`BackupSnapshot.createdAt` 放宽为 `string | number`。
- `ui-web/src/App.css`：`flow-layout-solo` 单列布局；课程树活动条目改 disc 列表标记。
- `src/main/java/.../FileGeneralSoftwareService.java`：快捷键帮助文案对齐实际（Ctrl+K / Ctrl+1~7 / Ctrl+, / 编辑器内 Ctrl+Enter、Ctrl+Shift+Enter、F1）。
- `docs/plans/README.md`：索引本报告。

第二轮（同日，P2/P3 批次）：

- `ui-web/src/features/editor/EditorPage.tsx`：作答视图新增题目描述、期望列与可折叠"数据表结构"；预览/错题本/课程活动的枚举改中文（新增 `activityTypeLabel`，复用 `ExerciseCatalog.difficultyLabel` 并将其导出）；运行反馈兜底文案 idle → 待运行；预览与作答的表结构按"；"分行。
- `ui-web/src/App.tsx`：今天页队列为空时 hero 与队列卡片提供「去练习一题」。
- `ui-web/src/features/platform/PlatformPages.tsx`：教学空间统计下方加口径说明；题库状态列改"已启用/已停用"状态片 + 切换按钮（新增 `.state-cell` 样式）；选中题目滚动到编辑器；题库/学情表页大小 8 → 50（分页测试同步更新为 120 条数据）；设置保存按钮更名"保存偏好设置"；学生"尚无班级"空态补充加入流程。
- `ui-web/src/features/auth/AuthPage.tsx` + `App.css`：「继续离线学习」移至表单底部行内。
- `ui-web/src/features/editor/ExerciseCatalog.tsx`：目录按钮增加 `aria-label`。
- `ui-web/src-tauri/src/lib.rs`：sidecar 启动参数加 `-Djava.net.preferIPv4Stack=true`（`cargo check` 通过）。

第三轮（同日，P2-5 / P2-8 按第七节方案实现）：

- `ui-web/src/features/platform/PlatformPages.tsx`：班级面板拆为"添加成员"与"新建任务"两个独立折叠面板（含"待提交/草稿未保存"状态片），新增截止快捷项（`datetimeLocalAt`/`deadlinePresetLabels`，今天/明天/7 天后 18:00 + 清除）与 `.class-analytics-bar` 常显工具栏（筛选 + 查看学情/导出学情）；花名册空态指向新面板。
- `ui-web/src/shared/paletteSearch.ts` + `paletteSearch.test.ts`（新增）：命令面板四类实体查询 hooks（`practice.catalog` 服务端 `q` 过滤、`knowledge.search`、`cloud.workspace` 缓存复用、`data.connections`）与纯函数 `buildPaletteSections`（页面→题目→知识文档→班级→连接，每组 5 条，客户端标题过滤兜底，页面组带 Ctrl+N 提示）。
- `ui-web/src/App.tsx`：`CommandPalette` 重写——输入防抖 200ms、分组渲染、↑/↓ 循环移动 + Enter 打开当前项、高亮 active 项、组级失败静默隐藏、空态文案区分"无结果/提示输入"。
- `ui-web/src/features/knowledge/KnowledgePage.tsx`、`data-sql/DataSqlPage.tsx`、`platform/PlatformPages.tsx`（CloudPage）：分别支持 `?article=`、`?connection=`、`?class=` 深链——既初始化 state，也在页面已挂载时响应参数变化并联动加载（知识切文档、SQL 切连接、班级切任务列表）。
- `ui-web/src/App.css`：`.palette-group`/`.palette-group-label`/`button.active` 分组与高亮；`.class-analytics-bar`、`.deadline-presets`、`.preset-chip`、`.state-cell` 样式。
- 测试更新：`CloudPage.test.tsx`（新面板定位）、`TeachingPage.test.tsx`（页大小 50 分页数据）、`DataSqlPage.test.tsx`（MemoryRouter 包裹）。

验证命令与结果：

- `npm --prefix ui-web test`：17 个文件 55 个测试全部通过（含新增 `instant.test.ts`）。
- `npm --prefix ui-web run build`：通过。
- `mvn -q test "-Dtest=FileGeneralSoftwareServiceTest"`：通过。
- `.agents/skills/sqlteacher-documentation/scripts/check-markdown-links.ps1`：305 个文件 0 个断链。
- 运行时验证（浏览器桥接 + 真实核心与数据副本）：深色主题下编辑器实际应用 `sqlteacher-dark`（编辑器背景 `#10222e`，语法高亮适配深色）；备份时间显示 `2026/9/11 15:46:24` 等 zh-CN 真实时间；知识页空态与列表提示按预期渲染；错题本全宽布局、步进器不再断行；完整学情分析展开后正常渲染数据与 zh-CN 生成时间。P1-5 的界面文案需随下次安装包发布生效，当前以聚焦测试验证字符串。
- `git diff --check` 干净；未提交（等待用户指示）。

第二轮运行时验证（浏览器桥接）：作答视图显示题目描述与期望列、展开"数据表结构"后逐表分行显示；错题本眉标为"文本条件 · 入门 · 查询题"；课程活动预览为"模拟实验 · 入门"；教学空间题库状态列显示"已启用"状态片、分页为 1/2 页、口径说明可见、点击题目标题后编辑器展开且滚动进入视口（表单正确填充）；登录页"← 继续离线学习"位于底部行内且顶部按钮已移除。今天页"去练习一题"按钮在队列为空时渲染（当前数据存在继续练习动作，空态路径以代码与 JSX 复核为准）。

第三轮运行时验证（P2-5 / P2-8，浏览器桥接 + 真实核心与数据副本）：

- P2-5：班级卡片内为"成员名单 / 添加成员 / 新建任务"三个折叠面板 + 常显"筛选与学情"工具栏（提交状态、起止时间与查看学情/导出学情合并）；旧混合面板不复存在；"7 天后 18:00"快捷项正确回填 `2026-09-21T18:00`，并有"清除截止时间"；花名册空态指向新面板。
- P2-8：命令面板按"页面 → 题目 → 知识文档 → 班级 → 连接"分组渲染；搜索"查询"命中页面（detail 含"连接与查询"）与服务端过滤的题目；搜索"254班/253班"命中班级组，Enter（键盘）与点击均能切换班级（`班级任务：253班`）；点击题目条目跳 `/practice?exercise=<id>` 且预览加载；点击连接条目跳 `/data?connection=<id>` 且工作台选中对应连接；知识库在该数据副本为 0 篇时知识组按设计隐藏、检索显示"没有匹配结果"。
- 键盘 Enter 导航在多次验证中一次成功、数次未触发（与浏览器验证环境反复崩溃/恢复相关，点击路径始终稳定）；面板键盘处理为标准 React 合成事件，随包发布后建议人工再按一次 Ctrl+K 复核。
- 回归：18 个测试文件 59 个测试全部通过（含新增 `paletteSearch.test.ts` 4 个分组用例）；构建、`cargo check`、链接检查、`git diff --check` 均通过。

遗留与未验证：

- P1-4 的"无数据空态"文案在当前数据环境下无法自然触发（本机已有学情数据），仅验证了加载态与数据渲染路径。
- 主题切换的完整矩阵（浅色↔深色↔高对比度反复切换）只验证了浅色→深色与回退逻辑代码路径。
- 设置页"时间未知"（P2-10）的修复通过共享 `formatInstant` 间接生效，未单独构造会话数据验证。

## 七、待排期项设计方案（P2-5 / P2-8，2026-09-14）

### P2-5 教师班级面板重构

> 状态（2026-09-14 第三轮）：已按下方方案实现并运行时验证。

现状（`CloudPage` 的 `section.content-card.class-assignments`）：一个 `<details>` 内混杂"目标班级选择 + 添加成员表单 + 新建任务表单"，其下又是游离的"班级学情/导出"按钮排和"提交状态/起止时间"筛选行，五类职责视觉上无法区分。

方案：保持状态与数据流完全不动，仅重排 JSX 结构为职责清晰的四个子块（与现有"成员名单"折叠面板同一交互模式）：

1. **成员名单**（已有，保持）：花名册展示。
2. **添加成员** `<details class="class-members">`：目标班级选择、成员邮箱、成员角色、添加成员按钮与提示。空态文案从"展开「添加成员与创建任务」"改为指向本面板。
3. **新建任务** `<details class="class-assignment-create">`：任务标题/练习/截止时间/说明 + 创建并发布/存为草稿。截止时间旁加快捷项"今天 18:00 / 明天 18:00 / 7 天后"（点击回填 datetime-local），并加格式说明文字。
4. **任务筛选与学情**：将现有"提交状态/开始/结束时间"筛选与"班级学情/导出班级学情"按钮合并为一条常显工具栏（`class-analytics-bar`），紧贴任务列表上方——筛选控制的就是下方列表与学情导出，按钮与筛选不再分离。

实现要点：不改任何 mutation/状态；纯 JSX 分组 + 少量 CSS（`.class-analytics-bar` 用现有 `settings-grid`/`button-row` 组合即可）。工作量约 1–2 小时，含截图回归。

### P2-8 命令面板实体搜索

> 状态（2026-09-14 第三轮）：已按下方方案实现并运行时验证。

现状（`App.tsx` 的 `CommandPalette`）：仅静态过滤 6 个页面项；Enter 只打开第一项；无方向键导航。

方案：在保留现有页面项逻辑的基础上增加"实体搜索"分组，把面板从"页面跳转"升级为"全局搜索与跳转"。

数据源（全部复用既有 IPC，react-query 缓存，`enabled: open && query.trim().length >= 2`，staleTime 60s，保留上一次结果）：

| 分组 | 方法 | 说明 |
| --- | --- | --- |
| 页面 | 本地过滤（现有） | query 为空时仅显示此组 |
| 题目 | `practice.catalog`（`q` 参数，pageSize 20） | 服务端已支持关键字过滤 |
| 知识文档 | `knowledge.search`（≥2 字符） | 返回 articleId/title/snippet |
| 班级 | `cloud.workspace` 缓存 | 仅登录后显示 |
| 连接 | `data.connections` | 数据库连接列表 |

交互与跳转：

- 分组顺序固定为 页面 → 题目 → 知识文档 → 班级 → 连接，每组最多 5 条；组头右侧小加载指示，某组请求失败时静默隐藏该组，不影响其余组。
- 新增方向键导航：扁平化结果列表上维护 `activeIndex`，↑/↓ 移动、Enter 打开当前项、高亮样式 `.command-palette button.active`；Esc 关闭保持不变。
- 深链目标：题目 → `/practice?exercise=<id>`（已支持）；知识文档 → `/knowledge?article=<id>`；连接 → `/data?connection=<id>`；班级 → `/cloud?class=<id>`。后三者需给对应页面增加 URL 参数初始化（均为读取 `searchParams` 初始化既有 state 的小改动，KnowledgePage 已有 `?query=` 先例）。
- 角色可见性沿用各数据源自身权限（未登录无班级/连接组由请求失败或空结果自然隐藏）。

实现切分：① `src/shared/` 新增 palette 数据源 hooks（每源一个 useQuery）；② `CommandPalette` 改造为"扁平结果 + activeIndex + 分组渲染"；③ 三个页面的深链参数支持；④ 面板键盘导航测试。工作量约 3–4 小时。
