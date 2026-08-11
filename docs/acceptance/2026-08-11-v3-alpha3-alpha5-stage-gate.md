# v3.0 Alpha.3 至 Alpha.5 本地阶段门禁

> 日期：2026-08-11
>
> 本地代码版本：`3.0.0-alpha.5`
> 发布状态：未提交、未打标签、未推送、未发布；公开版本仍为 `v3.0.0-alpha.1`

## 验收范围

- Alpha.3：课程与知识工作区、Obsidian 安全增量导入、检索与受限 Markdown 阅读。
- Alpha.4：确定性 SQL 练习主路径、Java/Python/C/C++ 本地 Runner、Monaco 多语言编辑与取消。
- Alpha.5：数据库结构与 SQL 工作台、一次性高风险确认令牌、分页结果、带引用 AI 助手。
- IPC v1：Java、TypeScript、Rust 与机器可读契约保持一致，并覆盖新增方法和事件。

## 自动化门禁

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Java 全量测试 | `mvn test` | 通过；513 个测试，0 失败，0 错误，2 跳过 |
| 前端依赖审计 | `npm audit` | 通过；0 个漏洞 |
| 前端单元测试 | `npm test` | 通过；4 个测试文件、10 个测试 |
| 前端生产构建 | `npm run build` | 通过；存在 Monaco 与 Mermaid 大分块警告，不阻断 Alpha 门禁 |
| 交互性能样本 | `npm run test:performance` | 通过；约 1899.52 ops/s，平均约 0.5264 ms |
| Rust 测试 | `cargo test` | 通过；3 个测试，包括 IPC 白名单与契约清单一致性 |
| Tauri 检查 | `cargo check --features e2e` | 通过 |
| Tauri 无安装包构建 | `npx tauri build --no-bundle` | 通过 |
| E2E 构建 | `npm run test:e2e:build` | 通过；先重建 Java sidecar 再构建壳层 |
| 桌面 E2E | `npm run test:e2e` | 通过；1 个场景覆盖版本、角色门禁、知识、练习、数据工作区和设计快照 |

## 安全与一致性证据

- Obsidian 执行必须消费限时预览令牌；预览后文件变化、符号链接、越界和超限输入被拒绝。
- SQL 确认令牌绑定连接与 SQL 哈希、限时且只能消费一次；执行前 Java 再次进行风险分析。
- JDBC 执行继续强制只读策略、超时、最多 500 行和审计；前端分页不会获得无界结果。
- AI 只接收 Java 策略筛选和脱敏后的上下文；权威学习状态和 SQL 执行权不进入模型或前端。
- Java 契约测试与 Rust 白名单测试共同校验 `contracts/ipc/v1/manifest.json`，减少跨语言漂移。

## 人工检查与已知限制

- 已在 1920×1200 E2E 截图中检查数据工作区，无明显遮挡、重叠或不可读区域。
- `jsdom` 在单元测试中输出非阻断的 Canvas `getContext` 警告。
- Vite 报告部分生产分块超过 500 KiB；这是后续加载优化项，不影响本阶段功能正确性。
- AI 分段事件目前对完成后的回答分块传输，不代表 Provider 原生 token 流。

实现记录：[Alpha.3](../history/stages/stage21/2026-08-11-v3-alpha3-course-knowledge-import.md)、[Alpha.4](../history/stages/stage22/2026-08-11-v3-alpha4-practice-runner-editor.md)、[Alpha.5](../history/stages/stage23/2026-08-11-v3-alpha5-data-sql-ai.md)。

返回 [验收记录索引](README.md)。
