# 阶段 0 风险清单

| 风险 | 影响 | 当前状态 | 应对措施 |
|---|---|---|---|
| Java 版本不统一 | 编译、运行和团队协作混乱 | 已统一为 Java 21 LTS 编译目标 | 本机可用 JDK 21 或更高版本，但 Maven 使用 `--release 21` |
| JavaFX 运行环境缺少图形界面 | 无法启动桌面窗口 | 已增加 JavaFX Runtime 和 Graphics Environment 探测；headless 环境返回 WARNING，不阻塞 CLI 验证 | 默认使用 CLI 验证；只有图形环境可用时再运行 `mvn javafx:run` 手动验证 |
| Ollama 未安装或未启动 | AI 功能不可用 | 健康检查返回 WARNING，不阻塞启动 | 阶段 1 增加模型配置页和清晰提示 |
| MySQL 本地服务不存在 | 无法做真实连接测试 | 阶段 0 只验证驱动可加载 | 阶段 4 补充 MySQL 集成测试环境 |
| jpackage 参数随最终应用结构变化 | 打包脚本需要调整 | 已提供初始脚本 | 阶段 6 前持续维护 |
| 文档与构建配置 Java 版本不一致 | 团队执行混乱 | 已统一为 Java 21 LTS | 后续修改 Java 版本必须同步 `pom.xml` 和文档 |
