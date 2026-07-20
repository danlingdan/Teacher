# 第二次联调记录（2026-07-17）

## 1. 联调范围

本次提前启动原计划 2026-07-20 的第二次联调窗口，基线为 `develop` 提交
`2dd0985`。联调范围严格限定为 P0 链路：

```text
SQLite 元数据
→ SQL 风险分析
→ 用户确认后执行
→ SQL/风险事件落库
→ NL2SQL 结构化草案
→ Java 风险复检
→ AI 生成事件落库
```

不在本轮扩展 MySQL、知识检索、学情看板或安装程序。

## 2. 模块接入结果

| 模块 | 联调检查 | 结果 |
|---|---|---|
| Application | SQL 执行、风险结果、事件服务契约可被统一调用 | 通过 |
| Database | SQLite 初始化、元数据查询、行数限制、确认执行 | 通过 |
| SQL Safety | 多语句禁止；写操作和结构变更要求确认；`DROP DATABASE` 禁止 | 通过 |
| Event | SQL 执行、风险拦截、AI 草案生成写入 `learning_events` | 通过 |
| AI | 结构化 JSON 校验，草案复用 Java 风险分析，不直接执行 | 通过 |
| Desktop | 真实服务注入，风险确认状态可传递，元数据和 SQL 操作在后台线程执行 | 默认窗口人工走查通过 |

## 3. 新增联调覆盖

新增 `SecondIntegrationFlowTest`，在同一个临时 SQLite 环境内验证：

1. 表结构服务能够读取 `student` 表。
2. 多语句 SQL 被阻止并记录 `SQL_RISK_BLOCKED`。
3. 确认后的写操作能够执行并记录 `SQL_EXECUTION`。
4. 固定 AI Provider 返回结构化 `SELECT` 草案。
5. AI 草案通过统一风险分析，但不会直接修改数据库。
6. `AI_SQL_GENERATED` 事件成功写入应用数据库。

## 4. 验证记录

| 命令 | 结果 |
|---|---|
| `mvn -Dtest=SecondIntegrationFlowTest test` | 1 项通过，0 失败 |
| `mvn test`（新增联调测试后） | 108 项通过，0 失败 |
| `mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"` | Spring、应用数据库、演示数据库通过 |
| `mvn javafx:run` | 默认 1180×720 窗口启动成功；真实 SELECT、表结构浏览、数据预览和风险弹窗通过 |

人工走查期间使用真实 SQLite 服务执行只读查询；高风险 UPDATE 仅检查弹窗中的风险类型、
涉及表名和 SQL 预览，随后选择取消。确认执行链路由自动化联调测试覆盖。

## 5. 当前阻塞项与限制

- 本机 `http://localhost:11434` 当前不可用，Stage One 验证按设计输出 Ollama 降级警告；
  不影响 SQL、风险、事件和确定性 AI 测试。
- 默认 1180×720 JavaFX 走查通过；800×600 和 1366×768 的精确尺寸走查仍待执行。
- 当前未发现 P0 代码阻塞项。

## 6. 下一步

1. 启动 Ollama 后执行 5 个典型自然语言查询的手工回归。
2. 补充 800×600 和 1366×768 两个精确窗口尺寸的布局走查。
3. 人工验收完成后更新本记录，并冻结第二次联调结论。
