# v3.0 Alpha.6 至 Alpha.7 本地阶段门禁

> 日期：2026-08-11
>
> 本地代码版本：`3.0.0-alpha.7`
> 发布目标：`v3.0.0-alpha.7` 最后 Alpha 预发布；生产默认入口不切换

## 验收范围

- Alpha.6：教师题库与学情、真实会话角色、账号登录/退出、班级创建、显式云同步、设置向导与环境探测。
- Alpha.7：七个工作区功能对齐、IPC 跨语言一致性、离线与失败恢复、JavaFX 回退和 Alpha 收口。

## 自动化门禁

| 门禁 | 命令 | 结果 |
| --- | --- | --- |
| Java 聚焦测试 | `mvn -q test "-Dtest=DefaultLocalAppApiTest,LocalAppContractTest,JdbcSqlExecutionServiceTest"` | 通过 |
| Java 全量测试 | `mvn test` | 通过；513 个测试，0 失败，0 错误，2 跳过 |
| 前端单元测试 | `npm test -- --run` | 通过；4 个测试文件、10 个测试 |
| 前端依赖审计 | `npm audit` | 通过；0 个漏洞 |
| 前端生产构建 | `npm run build` | 通过 |
| 交互性能样本 | `npm run test:performance` | 通过；约 2803.23 ops/s，平均约 0.3567 ms |
| Rust 测试 | `cargo test` | 通过；3 个测试，包括 IPC 白名单与 manifest 一致性 |
| Tauri E2E 特性检查 | `cargo check --features e2e` | 通过 |
| 打包态 E2E 构建 | `npm run test:e2e:build` | 通过；Java 25 sidecar 与 Alpha.7 release 二进制均重新生成 |
| 桌面 E2E | `npm run test:e2e` | 通过；1 个场景覆盖版本、Sidecar、角色拒绝、原有工作区、云端、设置、迁移状态与视觉快照 |

## 权限、安全与隐私证据

- 前端守卫之外，Java 对教师题目操作和班级创建再次检查教师/管理员角色；云端服务端继续执行最终授权。
- 默认云端页面只读取本地安全会话和同步队列，只有用户点击刷新、同步、登录或创建班级时才访问网络。
- 密码不会进入 Web Storage 或日志；Java 使用字符数组调用登录后立即清零。响应不包含访问令牌、刷新令牌或连接字符串。
- SQL 开发者模式只调用现有 Java 安全模式服务；禁用语句、多语句、只读连接、高风险确认、执行上限和审计路径保持不变。
- Alpha.7 未改变 SQLite schema 语义，生产默认入口未切换，JavaFX 回退仍存在。

## 人工检查与已知限制

- 已检查 1920×1200 Alpha.7 迁移状态截图；导航、功能清单和状态信息无重叠或截断。
- 首次并行 release 构建曾触发一次 Windows Rust 编译器异常退出；单独重试完整构建通过，新二进制 E2E 通过。
- Vite 仍报告 Monaco 与 Mermaid 大分块；这是 Beta 专项优化项，不阻断 Alpha 功能门禁。
- `jsdom` Canvas 警告和 WebdriverIO 外部 `tauri-driver` 诊断属于测试工具非阻断信息；实际 E2E 使用内嵌 WebDriver 并通过。

实现记录：[Alpha.6](../history/stages/stage24/2026-08-11-v3-alpha6-teaching-cloud-settings.md)、[Alpha.7](../history/stages/stage25/2026-08-11-v3-alpha7-parity-closeout.md)。

返回 [验收记录索引](README.md)。
