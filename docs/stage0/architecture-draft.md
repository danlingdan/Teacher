# 阶段 0 架构草案

## 1. 目标

阶段 0 的目标不是完成业务功能，而是验证 SQLTeacher 首版需要的关键技术可以在当前开发环境中落地。

本阶段验证范围：

- Maven + Java 21 LTS 目标版本工程可编译。
- JavaFX 桌面窗口可启动。
- SQLite JDBC 可执行最小查询。
- MySQL JDBC 驱动可加载。
- Ollama 健康检查调用路径可用，并能处理服务不可达。
- jpackage 打包路径有初始脚本。

## 2. 当前工程形态

当前先采用单模块 Maven 工程，包名提前按未来分层组织：

```text
com.sqlteacher
├─ desktop
├─ infrastructure.ai
├─ infrastructure.database
└─ infrastructure.environment
```

后续进入阶段 1-2 后，可按文档演进为多模块 Maven 工程。

## 3. 最小技术链路

```text
TechnologyVerificationApp
→ RuntimeEnvironment
→ JdbcTechnologyVerifier
→ OllamaHealthClient
→ VerificationItem
```

JavaFX 验证入口：

```text
SqlTeacherFxApp
→ JavaFX Stage
→ VBox
→ Label
```

## 4. Java 版本决策

项目统一使用 Java 21 LTS 作为编译目标和团队基准版本。

原因：

- Java 21 是长期支持版本，更适合课程项目、团队协作和后续打包交付。
- JavaFX、JDBC、jpackage 等生态对 Java 21 支持稳定。
- 开发者本机可以使用 JDK 21 或更高版本，但 Maven 必须以 `--release 21` 编译。
- 若使用 JDK 25 本地构建，产物仍应保持 Java 21 目标兼容。

## 5. 阶段 0 结论

本阶段只证明技术可用，不代表最终架构冻结。阶段 1 需要继续细化：

- 应用服务接口。
- 全局配置模型。
- 日志配置。
- JavaFX Controller 管理方式。
- 数据库连接配置存储。
- AI 模型配置存储。
