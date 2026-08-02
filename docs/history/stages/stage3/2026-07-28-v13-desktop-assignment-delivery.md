# v1.3 桌面任务与离线提交交付记录

## 交付范围

- 学生在云端教学页选择已发布任务后，可直接打开任务关联的本地题目。
- 任务继续使用既有 `ExercisePracticeService` 创建隔离数据集，不把云端任务 SQL 交给服务端执行。
- 本地确定性评测完成后，仅上传通过状态、受控错误码、客户端完成时间和 SHA-256 结果摘要。
- 网络不可用、超时、限流或服务端暂时错误时，提交写入本地 SQLite 队列。
- 队列按云端账号隔离；退出登录不删除，其他账号不可读取或上传。
- 队列保留稳定操作 ID，应用重启和重复同步不会在服务端形成重复逻辑提交。
- 截止、撤回、权限等永久拒绝不会进入无限重试；重试时遇到永久拒绝会转为 `REJECTED`。
- JavaFX 练习、上传和重试均在后台执行器运行，界面线程只更新状态。

## 本地迁移

`app.db` schema 从 4 升至 5，新增 `assignment_submission_queue`。队列不保存 SQL 正文、令牌、密码或连接信息，仅保存提交所需的最小确定性摘要。

队列状态：

```text
QUEUED -> DELIVERED
QUEUED -> REJECTED
```

临时失败使用 2 至 60 分钟的指数退避；“立即同步”最多处理当前账号 50 条到期任务，避免长时间占用后台线程。

## 用户路径

```text
云端教学 -> 选择班级与已发布任务 -> 开始所选任务
-> 本地隔离练习 -> 确定性评测 -> 后台提交
-> 已提交/已通过，或断网进入待同步队列
-> 云端教学“立即同步”重试
```

桌面端会预先提示已缓存的截止时间，但服务端仍是任务状态和截止时间的最终判定者。

## 验证

```powershell
mvn -q "-Dtest=JdbcAssignmentDeliveryServiceTest,SqliteSchemaMigratorTest,SqlTeacherApplicationConfigTest,DesktopFxmlResourceTest,HttpCloudApiClientTest" test
mvn test
```

自动化测试覆盖 schema 5 升级与幂等、断网持久化、应用重启重试、操作 ID 稳定、账号隔离、永久拒绝不排队、最小字段存储及 FXML 资源加载。
