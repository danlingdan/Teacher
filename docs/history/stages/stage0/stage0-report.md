# 阶段 0 技术验证报告

## 1. 已完成内容

- 建立 Maven 依赖和插件配置。
- 增加命令行技术验证入口 `TechnologyVerificationApp`。
- 增加 JavaFX 最小窗口入口 `SqlTeacherFxApp`。
- 增加 SQLite JDBC 内存库验证。
- 增加 MySQL JDBC 驱动加载验证。
- 增加 Ollama 健康检查客户端。
- 增加阶段 0 单元测试。
- 增加 jpackage 初始打包脚本。
- 增加架构草案、风险清单和测试计划初稿。

## 2. 验证命令

```bash
mvn test
```

2026-07-03 本机执行结果：

- 结果：通过。
- 测试数：5。
- 失败：0。
- 错误：0。
- 跳过：0。
- Java 编译目标：21 LTS。
- JavaFX 版本：21.0.8。
- 本机验证运行时：JDK 25。
- Maven：3.9.14。
- Maven 编译日志确认：`javac [debug release 21]`。
- 已覆盖 JavaFX Runtime 类加载和 Graphics Environment 探测测试。

命令行技术验证：

```bash
mvn -q exec:java -Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp
```

PowerShell 中建议写成：

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp"
```

2026-07-03 本机执行结果：

```text
SQLTeacher stage 0 technology verification
[PASS] Java runtime - 25
[PASS] Operating system - Windows 11 10.0 (amd64)
[PASS] JavaFX runtime - required JavaFX classes are available
[PASS] Graphics environment - desktop graphics environment is available for JavaFX manual verification
[PASS] SQLite JDBC - in-memory query succeeded
[PASS] MySQL JDBC - driver class is available
[WARNING] Ollama health - service not reachable: ConnectException
```

说明：

- 当前项目统一以 Java 21 LTS 为 Maven 编译目标。
- 本机使用 JDK 25 运行验证程序，属于允许情况。
- 当前本机未安装 Ollama，`WARNING` 符合阶段 0 预期，不阻塞退出标准。

JavaFX 窗口验证：

```bash
mvn javafx:run
```

JavaFX 环境处理结论：

- 阶段 0 默认不把 JavaFX 窗口启动作为自动化测试条件。
- CLI 验证会检查 JavaFX Runtime 是否可用。
- CLI 验证会检查当前环境是否 headless。
- 如果环境缺少图形界面，结果为 `WARNING`，团队仍可继续运行数据库、AI 健康检查和打包验证。
- 只有在 Windows 桌面环境中才手动运行 `mvn javafx:run` 验证窗口。

jpackage 可用性验证：

```powershell
jpackage --version
```

2026-07-03 本机执行结果：

```text
25
```

jpackage app-image 验证：

```powershell
.\packaging\package-stage0.ps1
.\target\installer\SQLTeacherStage0\SQLTeacherStage0.exe
```

2026-07-03 本机执行结果：

- `package-stage0.ps1` 执行成功。
- 已生成 `target/installer/SQLTeacherStage0`。
- 生成的 `SQLTeacherStage0.exe` 可启动并输出阶段 0 验证结果。
- 脚本可重复运行，会清理旧的阶段 0 app-image。
- 已为高版本 JDK 下的 SQLite 原生库加载配置 `--enable-native-access=ALL-UNNAMED`。
- Java 版本统一后重新验证通过：Java 21 编译目标 + JDK 25 本机运行时。

## 3. 已知限制

- 阶段 0 只验证 MySQL 驱动加载，不连接真实 MySQL。
- 当前本机未安装 Ollama；阶段 0 不要求 Ollama 必须启动，不可用时健康检查返回 WARNING。
- JavaFX 手动运行需要本机图形界面。
- jpackage 脚本是初始版本，后续会随最终应用结构调整。
