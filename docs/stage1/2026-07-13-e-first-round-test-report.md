# E 第一轮测试、打包与验收报告

## 1. 范围

本轮只处理 E 的测试、打包和验收材料边界，不修改业务实现。

交付内容：

- SQL 风险回归数据和数据驱动测试骨架。
- Windows JavaFX app-image 打包脚本初稿。
- 7 月 15 日第一次联调检查清单。
- 第一轮 10 分钟演示脚本。
- 第一轮已知限制清单。

## 2. 自动化测试

验证环境：

- Windows 11 10.0 amd64。
- Oracle JDK 21.0.11 LTS。
- Maven 3.9.16。

验证命令：

```powershell
mvn test
```

结果：

- BUILD SUCCESS。
- Tests run: 69。
- Failures: 0。
- Errors: 0。
- Skipped: 0。
- 编译日志确认使用 `javac [debug release 21]`。

新增回归数据位于：

```text
src/test/resources/regression/sql-risk-cases.tsv
```

第一轮覆盖 10 个基础场景：SELECT、尾分号 SELECT、INSERT、UPDATE、DELETE、CREATE、DROP、多语句、GRANT 和空 SQL。

## 3. CLI 验证

命令：

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

该命令验证 Spring Context、SQLite 初始化和 Ollama 可用性降级。

结果：

```text
[PASS] Spring DI - application context started
[PASS] SQLite app database - app-data\app.db
[PASS] SQLite demo database - app-data\demo.db
[WARNING] Ollama status - Ollama service unavailable: ConnectException
```

Ollama 在本次验证环境中不可达，应用按预期降级并以退出码 0 完成验证。

## 4. app-image 验证

命令：

```powershell
.\packaging\package-stage1.ps1
```

预期产物：

```text
target/installer/SQLTeacherStage1/SQLTeacherStage1.exe
```

脚本要求 JDK 21 或更高版本，并要求 `jpackage` 在 `PATH` 中。

结果：

- app-image 生成成功。
- launcher 文件存在。
- 首次烟雾测试发现 JavaFX 只在 classpath 时无法启动；脚本已增加 JavaFX module-path 和 modules 参数。
- 修正后 launcher 启动并持续运行超过 5 秒，随后由验证脚本主动关闭，烟雾验证通过。

## 5. 不在本轮范围

- JavaFX 接入真实 SQL 服务。
- 事件记录。
- AI 助手页面。
- 最终安装器、签名和图标。
- MySQL 完整集成。

## 6. 验收结论

E 第一轮测试、打包和验收材料通过：自动化测试、CLI 验证、app-image 生成与启动烟雾验证均已完成。

该结论只覆盖第一轮独立实现。JavaFX 仍使用 Mock SQL 服务，真实 UI 到 SQLite 的闭环必须在 7 月 15 日联调后重新验证。
