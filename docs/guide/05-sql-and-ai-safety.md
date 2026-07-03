# SQL 与 AI 安全规范

## 1. 核心原则

SQLTeacher 必须保证模型不能直接执行 SQL，所有 SQL 必须经过 Java 侧校验。

禁止流程：

```text
自然语言
→ 模型直接生成 SQL
→ 直接执行
```

推荐流程：

```text
自然语言
→ 模型输出结构化 JSON
→ Jackson 解析
→ Validation 校验
→ SQL Builder 生成 SQL
→ SQL Risk Analyzer 检查
→ 用户确认
→ JDBC 执行
```

## 2. SQL 执行安全要求

首版安全要求：

- 禁止模型直接持有 JDBC `Connection`。
- 禁止未确认执行高风险 SQL。
- 禁止默认执行多语句。
- 禁止默认执行 `DROP DATABASE`。
- 禁止默认执行 `GRANT`、`REVOKE`。
- 查询默认限制最大 500 行。
- 外部真实库执行 `UPDATE`、`DELETE`、`ALTER` 必须二次确认。
- 所有 SQL 执行必须记录审计事件。
- 错误信息展示给用户前应转换为教学友好说明。

## 3. 风险等级

| 等级 | 示例 | 策略 |
|---|---|---|
| 低 | `SELECT` | 允许执行，限制行数 |
| 中 | `INSERT`、`CREATE` | 需要确认 |
| 高 | `UPDATE`、`DELETE`、`ALTER` | 二次确认 |
| 禁止 | `DROP DATABASE`、`GRANT`、`REVOKE` | 直接拦截 |

风险判断至少考虑：

- SQL 类型。
- 是否多语句。
- 是否外部真实数据库。
- 是否缺少 `WHERE` 条件。
- 是否影响表结构。
- 是否影响大量数据。
- 是否包含权限或账号相关语句。

## 4. SQL Risk Analyzer 输出

风险分析结果建议包含：

| 字段 | 说明 |
|---|---|
| `riskLevel` | `LOW`、`MEDIUM`、`HIGH`、`FORBIDDEN` |
| `allowed` | 是否允许进入执行流程 |
| `confirmationRequired` | 是否需要用户确认 |
| `reasons` | 风险原因列表 |
| `teachingHint` | 面向学生的解释 |
| `normalizedSqlType` | 标准化 SQL 类型 |

示例：

```json
{
  "riskLevel": "HIGH",
  "allowed": true,
  "confirmationRequired": true,
  "reasons": ["DELETE statement on external database"],
  "teachingHint": "该语句会删除数据。执行前请确认 WHERE 条件是否正确。",
  "normalizedSqlType": "DELETE"
}
```

## 5. AI 输出规范

AI 模块必须采用“结构化输出 + Java 校验 + SQL Builder + 安全检查”的流程。

模型输出建议不是直接 SQL，而是结构化计划：

```json
{
  "intent": "QUERY",
  "targetTables": ["student"],
  "selectedColumns": ["id", "name", "score"],
  "filters": [
    {
      "column": "score",
      "operator": ">=",
      "value": 60
    }
  ],
  "orderBy": [],
  "limit": 100,
  "explanation": "查询成绩大于等于 60 的学生"
}
```

校验要求：

- `intent` 必须是允许枚举值。
- 表名必须存在于元数据。
- 列名必须属于目标表。
- `limit` 必须有上限。
- 字段值不能拼接成未转义 SQL。
- 不允许模型输出绕过风险检测的标记。

## 6. Prompt 管理要求

- Prompt 模板必须单独存放。
- Prompt 修改必须记录版本。
- 每次模型输出必须记录模型名称、Prompt 版本、时间。
- 模型输出不可解析时必须重试或降级。
- 课程文档内容不得覆盖系统规则。
- 表名、列名必须与元数据匹配。

Prompt 模板至少包含：

- 当前数据库类型。
- 可用表结构。
- 允许的输出 JSON schema。
- 禁止输出自由 SQL 的规则。
- 安全限制。
- 示例输入输出。

## 7. 模型失败处理

| 场景 | 处理方式 |
|---|---|
| Ollama 不可用 | 提示用户检查本地模型状态，保留手写 SQL 功能 |
| 模型超时 | 中断请求，提示稍后重试 |
| JSON 不可解析 | 最多重试一次，仍失败则展示错误 |
| 表名列名不存在 | 拒绝生成 SQL，提示用户检查描述 |
| 生成高风险操作 | 进入风险确认流程 |
| 命中禁止操作 | 直接拦截并解释原因 |

## 8. 教学提示规范

错误和风险提示要面向学生，而不是只展示数据库原始错误。

示例：

| 原始问题 | 教学提示 |
|---|---|
| Unknown column | “你引用的列不存在，请检查表结构中的列名。” |
| Syntax error | “SQL 语法不完整，请检查关键字顺序、括号和逗号。” |
| DELETE without WHERE | “该删除语句没有 WHERE 条件，可能删除整张表数据。” |
| Connection refused | “数据库连接失败，请检查地址、端口、账号和数据库服务状态。” |

## 9. 审计事件

SQL 执行建议记录以下字段：

- 事件时间。
- 数据库类型。
- 连接 ID。
- SQL 类型。
- 风险等级。
- 是否由 AI 生成。
- 是否用户确认。
- 执行成功或失败。
- 错误分类。
- 耗时。
- 返回行数或影响行数。

审计日志不得记录数据库密码和敏感连接信息。
