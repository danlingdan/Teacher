# SQLTeacher 文档中心

这里是项目文档的统一入口。当前材料按用途分类，已完成的阶段记录统一收进 `history/`；仓库内引用已同步更新，发布证据继续保留。

## 当前基线

- 当前代码版本：`1.11.5`
- 最新发布说明：[v1.11.5](releases/v1.11.5.md)
- 最近一轮功能计划：[v1.11 通用能力收尾计划](plans/2026-08-02-v1.11-general-capabilities-closeout-plan.md)
- 对应实施记录：[v1.11 通用能力收尾实施记录](history/stages/stage8/2026-08-02-v111-closeout-implementation.md)
- 当前生产部署记录：[v1.11 生产云服务部署记录](operations/2026-08-02-v111-cloud-deployment.md)

> “当前”以 `pom.xml`、Git 标签和实际代码为准。计划文档记录制定时的基线，不应被当作实时状态页。

## 按任务查找

| 要做什么 | 从哪里开始 | 内容性质 |
| --- | --- | --- |
| 安装、使用或配置 SQLTeacher | [使用与开发指南](guide/README.md) | 长期维护的操作说明与工程规范 |
| 了解某个版本为何开发、范围是什么 | [迭代计划索引](plans/README.md) | 版本实施前的目标、边界与任务拆分 |
| 查看功能实际上如何落地 | [阶段实施记录](history/stages/README.md) | 实现决策、测试结果与已知限制 |
| 判断某个版本是否通过门禁 | [验收记录索引](acceptance/README.md) | 验收命令、结果与发布前证据 |
| 查看用户可见变化 | [发布说明索引](releases/README.md) | 面向版本的变更、兼容性与升级说明 |
| 部署或排查云服务 | [运维记录索引](operations/README.md) | 生产部署、证书、备份与恢复门禁 |
| 查归档实施资料 | [历史资料](history/README.md) | 已完成阶段与早期团队规划 |
| 查桌面端设计 | [desktop/](desktop/) | JavaFX 页面契约与历史整改记录 |
| 准备软件著作权材料 | [copyright/README.md](copyright/README.md) | 冻结版本的申请材料与生成脚本 |

## 文档生命周期

一个正常版本按下面的顺序留下证据：

```text
plans（计划与范围）
  -> history/stages / desktop（实施记录）
  -> acceptance（验收门禁）
  -> releases（发布说明）
  -> operations（需要生产部署时）
```

这些文件承担不同职责，不互相覆盖：计划保留当时的假设；实施记录说明实际完成情况；验收记录保存验证证据；发布说明只写用户需要知道的最终变化。

## 历史资料

Stage 0 至 Stage 8 和早期五人协作方案已集中到 [history/](history/README.md)。这些资料用于追溯实现和决策；当前开发和交付规则以仓库根目录的 [AGENTS.md](../AGENTS.md) 与当前代码为准。

## 维护规则

新增或更新文档时遵循以下约定：

1. 指南放入 `guide/`；版本计划放入 `plans/`；阶段完成后的实现证据归档到 `history/stages/stageN/`；验收放入 `acceptance/`；发布说明放入 `releases/`；生产操作记录放入 `operations/`。
2. 时间性文档使用 `YYYY-MM-DD-主题.md`；发布说明使用 `vX.Y.Z.md`；稳定指南使用两位数字前缀保持阅读顺序。
3. 文档开头写清适用版本或制定日期。历史计划中的旧基线不回写，只在后续实施或验收文档中说明偏差。
4. 同一事实只设一个权威来源，其他文档使用相对链接引用，避免复制整段内容。
5. 完成版本交付时同步更新对应分类索引和本页“当前基线”；不要提交 `target/`、`app-data/`、日志、数据库或本地密钥。
