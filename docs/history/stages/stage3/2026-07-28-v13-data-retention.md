# v1.3 数据保留、清理与恢复交付记录

## 保留策略

| 云端数据类别 | 最短保留期 | 清理方式 |
|---|---:|---|
| 已同步学习事件 | 180 天 | 归档后删除 |
| 任务确定性提交摘要 | 365 天 | 归档后删除 |
| 管理员审计 | 365 天 | 归档后删除 |
| 导出审计 | 365 天 | 归档后删除 |
| 运维 SQLite 备份 | 30 天 | 由 `backup-cloud.sh` 滚动维护 |

保留 API 只处理云端数据库中的已同步记录，不访问或清理桌面端 `app-data/`，因此不会删除本地未同步学习数据。

## 受控清理流程

```text
管理员选择类别与截止时间
-> 服务端校验最短保留期
-> 生成 15 分钟有效的影响预览与一次性确认令牌
-> 运维人员确认外部备份引用
-> 服务端再次验证令牌和影响行数
-> 自动创建 SQLite 安全快照并执行 integrity_check
-> 同一事务逐行写入 retention_archive 后删除源记录
-> 写入 retention_jobs 和管理员审计
```

预览后若新增了同一截止范围内的数据，执行返回 `RETENTION_SCOPE_CHANGED`，不扩大原预览范围。备份创建或完整性校验失败时返回 `RETENTION_BACKUP_FAILED`，不进入删除事务。

完成的作业可通过恢复接口将归档记录原样写回；恢复行数与作业记录不一致时事务回滚。归档保留恢复证据，后续不可逆清除必须另行审批和二次确认。

## 接口

```text
POST /api/v1/admin/retention/preview
POST /api/v1/admin/retention/execute
POST /api/v1/admin/retention/{jobId}/restore
```

所有接口只允许管理员调用。执行请求必须同时提供预览 ID、一次性确认令牌和外部备份引用；审计不记录确认令牌或归档内容。

## 验证

```powershell
mvn -q "-Dtest=SqlTeacherCloudServerTest,HttpCloudApiClientTest" test
mvn test
```

自动化测试覆盖影响预览、错误令牌阻断、自动快照、归档删除、恢复、并发范围变化阻断和 HTTP 客户端契约。
