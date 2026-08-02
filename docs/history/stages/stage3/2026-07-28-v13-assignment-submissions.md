# V13-03 学生任务提交闭环

## 范围

本阶段建立正式任务提交域。正式提交与普通练习事件分表，只接收 Java 确定性判题摘要，不接收或执行 SQL。

## 提交契约

客户端提交：

- `operationId`：8 至 128 位安全字符组成的稳定幂等键；
- `passed`：Java 确定性判题结果；
- `resultHash`：确定性结果摘要的 SHA-256；
- `errorCode`：失败时的稳定错误类型，不包含原始 SQL 或敏感数据；
- `clientCompletedAt`：仅用于诊断，不参与截止判定。

服务端生成提交 ID、尝试序号和 `submittedAt`。同一账号重试相同 `operationId` 返回同一逻辑提交；该行为在服务重启后保持。一个操作 ID 被复用于其他任务时返回 `SUBMISSION_OPERATION_CONFLICT`。

## 服务端约束

- 只有任务所属班级的学生成员可以提交；
- 教师、管理员、非班级成员和跨班级账号不能代替学生提交；
- 只有 `PUBLISHED` 且未到服务端截止时间的任务接受提交；
- 草稿、截止、撤回和归档任务分别返回稳定拒绝码；
- 客户端时间不能绕过截止规则；
- 提交表不存储数据库连接信息、凭据或可执行 SQL。

## 数据结构

新增 `assignment_submissions`，关键约束：

- `(user_id, operation_id)` 唯一；
- `(assignment_id, user_id, attempt_number)` 唯一；
- 外键限定班级、任务和用户；
- 按任务、学生和提交时间建立查询索引。

所有云端 SQLite 连接现在统一启用 `foreign_keys=on` 和 5 秒 busy timeout。

## 验证

```powershell
mvn -q "-Dtest=SqlTeacherCloudServerTest,HttpCloudApiClientTest" test
mvn test
```

聚焦测试覆盖正常提交、重复点击、服务重启后重试、尝试序号、操作 ID 冲突、教师/外部账号拒绝、截止/撤回/归档拒绝、服务端时间和客户端序列化路径。
