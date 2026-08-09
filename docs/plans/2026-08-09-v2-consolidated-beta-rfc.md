# SQLTeacher 2.0 一次性 Beta 收敛 RFC

> 制定日期：2026-08-09
> 目标版本：`2.0.0-beta.1`
> 状态：已实施

## 决策

原总计划把 Beta 拆为功能完整、内容校准、兼容质量和安全预演四个连续里程碑。为缩短串行周期，`beta.1` 一次完成四组门禁，不再分别创建 `beta.2`、`beta.3`、`beta.4` 功能版本；后续直接进入 RC 缺陷收敛。门禁没有删除，只合并执行。

Beta 起进入顶层功能冻结：产品名称继续使用 SQLTeacher；八种活动类型、本地 schema 17、课程包格式、Cloud 2.0 主端点、UI Kit 路线和独立 Runner 边界冻结。RC 不再新增活动类型、数据库体系、Cloud 主端点或顶层工作区。

## 四组收敛

| 原里程碑 | 一次性 Beta 落地 |
| --- | --- |
| beta.1 功能完整 | 为 `LAB`、`READING` 补齐类型化规格、Artifact、评价器、持久化解码、Spring 注册和真实 JavaFX 入口；八种活动全部可运行。增加第 12 条综合项目路径。 |
| beta.2 内容与诊断 | 12 条内置路径具备来源、作者、许可和内容版本记录；建立跨课程关系；按活动类型冻结证据权重，阅读完成本身不产生掌握结论。 |
| beta.3 兼容与质量 | schema 17 保持追加迁移、重复启动、失败回滚和未来版本拒绝；沿用 5,000 事件、500 人班级、i18n、对比度、键盘与响应式契约门禁。 |
| beta.4 安全与发布预演 | 稳定安装只接受 stable 更新清单；Alpha/Beta/RC 清单可验签但只能进入匹配预发布通道。课程包、Runner、SQL、AI、隐私扫描和 Windows 候选打包继续执行既有门禁。 |

## 冻结契约

- 活动类型固定为 `SQL/QUIZ/TRACE/SIMULATION/CODE/LAB/PROJECT/READING`。
- `LAB` 只确定性检查步骤、结构化观测和结论完整性，不自动断言学生自由文本事实正确。
- `READING` 显示来源与许可；只有主动回忆检查可以通过，单纯勾选阅读完成不能形成掌握证据。
- 跨活动证据策略版本为 `activity-evidence-policy-beta1-r1`；阅读、测验、跟踪、执行型实验和项目使用不同权重，仍保留最小证据量。
- 课程内容来源表和跨课程关系表进入 schema 17；Beta 后只允许兼容式追加或缺陷修复。
- 公开产品名保留 SQLTeacher，安装 Upgrade UUID、数据目录、Java package 和签名密钥体系不变。

## 安全边界

- 本地 IDE Runner 继续是用户主动运行的真实本地环境，不伪装成安全沙箱；安全评价继续使用独立 Runner。
- LAB/READING 不获得 SQL、Runner、文件或网络执行句柄。
- AI 不评分、不改变掌握度、不发布任务，也不自动运行代码或 SQL。
- stable 安装不能因服务端误配而接受 `alpha`、`beta` 或 `rc` 清单。
- 内置内容只记录 SQLTeacher 原创或仓库可核验来源，不引入外部教材正文。

## 退出标准

- 八种评价器均由 Spring 注册，并有稳定原因码和聚焦测试。
- 空库迁移后存在 12 条课程路径、八种活动类型、12 条内容来源记录和跨课程连接。
- 全量 Maven、性能、安全、i18n、视觉资源、迁移和 Cloud 契约测试通过。
- Windows EXE、ZIP、校验和、SBOM、生产 API 配置、敏感条目扫描和 app-image 冒烟通过。
- 不在本 RFC 中授权提交、推送、标签、Release 或生产部署。

返回 [v2.0 总计划](2026-08-02-v2.0-computer-science-learning-platform-plan.md) 或 [计划索引](README.md)。
