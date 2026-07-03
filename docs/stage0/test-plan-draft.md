# 阶段 0 测试计划初稿

## 1. 测试目标

验证 SQLTeacher MVP 的关键技术点可以在当前仓库中编译、测试和运行。

## 2. 测试范围

| 编号 | 测试项 | 验证方式 | 通过标准 |
|---|---|---|---|
| T0-01 | Maven 编译 | `mvn test` | 构建成功 |
| T0-02 | SQLite JDBC | 单元测试 | 内存库建表、插入、查询成功 |
| T0-03 | MySQL JDBC | 单元测试 | 驱动类可加载 |
| T0-04 | Ollama 健康检查 | 单元测试 | 服务不可用时返回 WARNING，不抛出异常 |
| T0-05 | JavaFX 环境探测 | 单元测试 + CLI 验证 | JavaFX 类可加载；无图形界面时返回 WARNING |
| T0-06 | JavaFX 主窗口 | 手动运行 `mvn javafx:run` | 图形环境可用时出现 SQLTeacher 窗口 |
| T0-07 | jpackage 可用性 | 手动运行 `jpackage --version` | 输出版本号 |

## 3. 暂不测试范围

- MySQL 真实连接。
- Ollama 真实模型生成。
- SQL 风险分析完整规则。
- JavaFX 页面导航。
- Windows 安装包完整安装和卸载流程。

## 4. 阶段 0 退出标准

- `mvn test` 通过。
- SQLite 和 MySQL 驱动验证测试通过。
- Ollama 不可用时不会导致程序崩溃。
- JavaFX 入口类可以编译。
- JavaFX 环境探测可报告当前机器是否适合启动桌面窗口。
- jpackage 初始脚本已提供。
