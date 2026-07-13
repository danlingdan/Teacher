# 阶段 1 验证报告

> 本文保留 2026-07-03 阶段 1 基线记录。2026-07-13 第一轮独立实现后的最新测试、
> 打包和限制说明见 `docs/stage1/2026-07-13-e-first-round-test-report.md` 与
> `docs/acceptance/first-round-known-limitations.md`。下文历史数据不代表当前 `develop` 状态。

## 1. 阶段目标

阶段 1 目标是完成可启动的桌面应用骨架，并补齐配置、日志、异常、应用服务接口、SQLite 初始化和 Ollama 状态检查的基础能力。

## 2. 已完成内容

- 引入 Spring Context，建立非 Web DI 容器。
- 增加 `SqlTeacherApplicationConfig`，统一装配配置服务、数据库初始化服务和 AI 状态服务。
- 增加 `application.properties`，集中管理应用名、数据目录、SQLite 路径和 Ollama 地址。
- 增加 Logback 配置，日志默认输出到控制台和 `app-data/logs/sqlteacher.log`。
- 增加 `SqlTeacherException` 作为基础业务异常。
- 增加统一异常映射契约，将已知、输入和未知异常转换为 UI 可展示的 `ApplicationError`。
- 配置与 AI 状态 DTO 已归入 application 层，不再反向依赖 infrastructure。
- 增加应用服务接口：
  - `AppConfigurationService`
  - `DatabaseInitializationService`
  - `AiStatusService`
  - `SqlExecutionService`
  - `Nl2SqlService`
- 增加 SQLite 应用库和演示库初始化：
  - `app-data/app.db`
  - `app-data/demo.db`
  - `student` 演示表和基础样例数据。
- 增加 Ollama 状态检查服务。
- 增加独立 UI mock 服务配置，页面可在不依赖 SQLite/Ollama 的情况下调用 application 接口。
- JavaFX 主窗口接入 Spring 容器，并展示 SQLite 和 Ollama 状态。
- 增加 `StageOneVerificationApp`，用于不打开 JavaFX 窗口的阶段 1 验证。

## 3. 本机环境记录

- 日期：2026-07-03。
- Java 编译目标：21 LTS。
- 本机运行时：JDK 25。
- Maven：3.9.14。
- Ollama HTTP 服务：可访问。
- Ollama 模型列表：已检测到 1 个模型，`qwen3.5:0.8b`。
- PowerShell 当前 PATH 未识别 `ollama` 命令，但本地服务已运行在 `http://localhost:11434`。

## 4. 验证结果

### Maven 测试

```powershell
mvn test
```

结果：

- 构建成功。
- 测试数：9。
- 失败：0。
- 错误：0。
- 跳过：0。
- 编译日志确认：`javac [debug release 21]`。

### 阶段 0 CLI 回归

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp"
```

结果：

```text
SQLTeacher stage 0 technology verification
[PASS] Java runtime - 25
[PASS] Operating system - Windows 11 10.0 (amd64)
[PASS] JavaFX runtime - required JavaFX classes are available
[PASS] Graphics environment - desktop graphics environment is available for JavaFX manual verification
[PASS] SQLite JDBC - in-memory query succeeded
[PASS] MySQL JDBC - driver class is available
[PASS] Ollama health - service reachable, models=0
```

### 阶段 1 CLI 验证

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

结果：

```text
SQLTeacher stage 1 application verification
[PASS] Spring DI - application context started
[PASS] SQLite app database - app-data\app.db
[PASS] SQLite demo database - app-data\demo.db
[PASS] Ollama status - Ollama service reachable, models=1
```

说明：MySQL Connector/J 会创建后台清理线程，`exec-maven-plugin` 已配置 `cleanupDaemonThreads=false`，避免阶段验证命令因该后台线程被误判失败。

### app-image 回归验证

```powershell
.\packaging\package-stage0.ps1
.\target\installer\SQLTeacherStage0\SQLTeacherStage0.exe
```

结果：

```text
SQLTeacher stage 0 technology verification
[PASS] Java runtime - 25
[PASS] Operating system - Windows 11 10.0 (amd64)
[PASS] JavaFX runtime - required JavaFX classes are available
[PASS] Graphics environment - desktop graphics environment is available for JavaFX manual verification
[PASS] SQLite JDBC - in-memory query succeeded
[PASS] MySQL JDBC - driver class is available
[PASS] Ollama health - service reachable, models=1
```

## 5. 运行期文件

阶段 1 会生成运行期数据目录：

```text
app-data/
├─ app.db
├─ demo.db
└─ logs/
```

该目录已经加入 `.gitignore`，不提交到仓库。

## 6. 已知限制

- JavaFX 仍是基础骨架，没有完整页面导航。
- 设置页按钮只是占位，阶段 2 前后再补真实设置页面。
- SQL 执行服务和 NL2SQL 服务目前只有 UI mock，真实业务实现仍待 B/C 完成。
- Ollama 已可访问，但当前没有安装模型；后续阶段需要补充模型配置和提示词管理。
