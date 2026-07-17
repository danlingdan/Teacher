# 测试、打包与验收

## 1. 测试目标

测试要覆盖核心链路：

```text
启动软件
→ 连接数据库
→ 读取元数据
→ 输入自然语言
→ 生成 SQL
→ 风险检测
→ 执行 SQL
→ 展示结果
→ 记录学情
```

优先保证 P0 功能稳定，再扩展 P1/P2。

## 2. 单元测试

必须覆盖：

- SQL 分类。
- 风险规则。
- SQL Builder。
- DTO 校验。
- 元数据标准化。
- 错误分类。
- 学情统计。
- 密码脱敏。
- Prompt 输出解析。

测试命名建议：

```text
shouldAllowSelectWithLimit
shouldBlockDropDatabase
shouldRequireConfirmationForDeleteWithoutWhere
shouldRejectUnknownColumnFromModelOutput
shouldMaskPasswordInConnectionError
```

## 3. 集成测试

必须覆盖：

- SQLite 连接和查询。
- MySQL 连接和查询。
- 连接失败。
- SQL 超时。
- 查询行数限制。
- 高风险 SQL 拦截。
- Ollama 不可用时的降级。
- 文档导入和检索。
- CSV 导出。
- 首次启动初始化。

MySQL 集成测试如果依赖本地环境，必须在测试说明中写清：

- 数据库版本。
- 测试账号权限。
- 测试库名。
- 初始化脚本。
- 清理方式。

## 4. AI 回归测试

至少准备 100 条样例，分阶段完成：

| 阶段 | 样例数量 |
|---|---:|
| 第 7 周 | 30 |
| 第 9 周 | 60 |
| 第 13 周 | 100 |

每条样例包含：

- 用户自然语言。
- 数据库类型。
- 表结构。
- 预期操作。
- 禁止操作。
- 允许的等价 SQL。
- 风险等级。
- 模型输出。
- 最终执行结果。

样例格式建议：

```json
{
  "id": "nl2sql-001",
  "userInput": "查询所有及格学生的姓名和分数",
  "databaseType": "SQLITE",
  "schema": "student(id, name, score)",
  "expectedIntent": "QUERY",
  "forbiddenOperations": ["DELETE", "UPDATE", "DROP"],
  "acceptableSqlPatterns": [
    "select name, score from student where score >= 60"
  ],
  "expectedRiskLevel": "LOW"
}
```

## 5. 打包规范

Windows 首版必须使用 jlink / jpackage 或等价方式交付自包含安装包。

打包验证项目：

- 干净 Windows 环境可安装。
- 用户无需额外安装 JRE。
- 安装后可启动。
- 卸载后不残留核心程序文件。
- 首次启动能初始化应用数据目录。
- `demo.db` 可用。
- 日志目录可写。
- 图标和应用名正确。

## 6. 验收指标

首版验收指标：

| 指标 | 目标 |
|---|---:|
| 单机首次启动成功率 | ≥ 95% |
| SQLite 演示库连接成功率 | ≥ 99% |
| MySQL 合法配置连接成功率 | ≥ 95% |
| JSON 结构化输出可解析率 | ≥ 95% |
| 高风险 SQL 提示率 | 100% |
| 禁止语句拦截率 | 100% |
| 默认查询最大行数 | 500，可配置 |
| 核心链路耗时 | 标准设备上 ≤ 45 秒 |
| 核心模块测试覆盖率 | 建议 ≥ 70% |
| 用户无需额外安装 JRE | 必须满足 |

## 7. 阶段演示要求

| 阶段 | 演示内容 |
|---|---|
| 第 2 周 | 技术验证 Demo |
| 第 4 周 | 桌面骨架和设置页 |
| 第 6 周 | SQLite SQL 执行闭环 |
| 第 9 周 | NL2SQL 和教学提示 |
| 第 11 周 | MySQL 接入 |
| 第 13 周 | 知识库和学情看板 |
| 第 16 周 | 安装包和完整演示 |

## 8. 最终测试报告结构

测试报告建议包含：

- 测试环境。
- 测试范围。
- 不测试范围。
- 功能测试结果。
- SQL 安全测试结果。
- AI 回归测试结果。
- 安装包测试结果。
- 已知问题。
- 风险和建议。
- 验收结论。

## 9. 常用验证命令

Maven 测试：

```bash
mvn test
```

第一轮 JavaFX app-image 打包：

```powershell
.\packaging\package-stage1.ps1
```

预期产物：

```text
target/installer/SQLTeacherStage1/SQLTeacherStage1.exe
```

该命令要求 Windows、JDK 25 或更高版本，并要求 `jpackage` 在 `PATH` 中。
当前只生成 app-image，不生成 MSI/EXE 安装器。
