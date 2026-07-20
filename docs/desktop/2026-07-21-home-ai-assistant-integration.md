# 首页与 AI 助手页面联调记录

## 交付范围

- 主窗口新增首页和 AI 助手导航入口。
- 首页提供 SQL 练习、AI 助手和表结构浏览三个真实入口。
- AI 助手在后台调用应用层 NL2SQL 安全编排服务，展示 SQL 草案、解释和风险状态。
- 仅通过只读安全门的 `SELECT` 草案可以复制到 SQL 练习页；实际执行仍由 SQL 练习页再次分析风险。
- Ollama 不可用时展示明确标注的模拟草案，不把模拟结果描述为真实模型输出。
- AI 助手页自动检测 Ollama 已安装模型，允许刷新和切换当前模型。
- 优先恢复用户上次选择；否则使用配置模型，配置模型不存在时自动选择首个已安装模型。
- 用户选择保存在运行目录 `app-data/selected-ai-model.txt`，不提交到版本库。

## 安全边界

- AI 页面依赖 `Nl2SqlSafetyService`，不直接把 `Nl2SqlService` 结果视为安全 SQL。
- 禁止语句、多语句、需要确认的修改语句以及展示内容与已校验草案不一致时，复制按钮保持禁用。
- 风险分析或后台调用失败时不放行 SQL。
- AI 请求复用 `DesktopExecutors` 的守护线程池，不阻塞 JavaFX Application Thread，也不创建无生命周期管理的线程。
- Ollama 请求显式关闭思考输出，只消费结构化 JSON 结果。

## 当前实现边界

- 当前可验证闭环使用内置 SQLite 演示数据库。
- 表结构页面提供浏览和刷新，不宣称已经实现可视化建表设计。
- MySQL 完整接入仍属于后续工作，首页不宣称已支持本地 MySQL 真实执行。

## 验证

```powershell
mvn test
mvn -q compile exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
```

测试覆盖 FXML 资源存在性与 XML 结构、模型检测与选择持久化，以及 AI 草案复制安全门的允许、拒绝和内容一致性场景。
