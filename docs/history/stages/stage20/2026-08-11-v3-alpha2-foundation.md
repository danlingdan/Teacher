# v3.0 Alpha.2 IPC、界面与测试基础实施记录

> 日期：2026-08-11
>
> 代码版本：`3.0.0-alpha.2`
> 公开版本：仍为 `v3.0.0-alpha.1`，本阶段未提交、打标签或发布

## 完成范围

- 冻结 `3.0-v1` 本地 IPC 请求、响应、事件、错误、兼容策略与资源上限，并以机器可读合同和三端测试防漂移。
- 增加 Java 所有的 `session.current` 会话角色，React 路由守卫不自行推断权限。
- 建立按钮、表单、步骤、反馈、空状态、对话框、表格、树与辅助功能原语，并提供应用内基线页。
- 使用 React Router 与 TanStack Query 建立路由、角色守卫、查询缓存和按页面加载；IPC 与路由记录结构化日志及性能标记。
- 建立 Vitest 组件/无障碍测试、Rust 合同测试、Vitest benchmark，以及 feature 隔离的 WebdriverIO 打包态桌面 E2E 和截图基线。

## 边界

本阶段不迁移 JavaFX 业务页，不改变 SQL 或 AI 执行链，也不发布 Alpha.2。浏览器预览仍拒绝伪造 Java 数据；正式 Tauri 构建不启用测试专用 WebDriver feature。

## 验证

| 门禁 | 结果 |
| --- | --- |
| `mvn test`（JDK 25） | 512 个测试，0 失败、0 错误、2 跳过 |
| `npm audit` | 0 个已知漏洞 |
| `npm test` / `npm run build` | 10 个测试通过；生产前端构建通过 |
| `cargo test` / `cargo check --features e2e` | 2 个 Rust 测试通过；普通与 E2E feature 编译通过 |
| `npm run test:performance` | 代表性数据原语约 3,110 ops/s，均值约 0.32 ms |
| `npm run test:e2e:build` / `npm run test:e2e` | release 二进制构建通过；1 个 Java 实链路桌面场景通过并生成截图 |
| Markdown / diff | 262 个 Markdown 文件、0 个本地断链；`git diff --check` 通过 |
