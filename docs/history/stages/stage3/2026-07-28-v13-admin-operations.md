# v1.3 管理运维与审计交付记录

## 交付范围

- 新增管理员健康概览：启用/禁用账号数、有效会话数、任务数和提交数。
- 新增账号列表、禁用、恢复和强制撤销会话接口。
- 账号禁用后立即撤销访问令牌与刷新令牌，并阻止再次登录。
- 禁止禁用最后一个可用管理员，冲突时返回稳定错误码 `LAST_ACTIVE_ADMIN`。
- 新增管理员审计查询，支持动作、起止时间和分页筛选。
- 对登录、注册、任务变更、账号运维和分析导出记录结构化审计事件。
- 审计记录只保存动作、对象、结果、原因码和关联标识，不保存密码、令牌或 SQL 正文。

## 接口

```text
GET  /api/v1/admin/health
GET  /api/v1/admin/users
POST /api/v1/admin/users/{userId}/disable
POST /api/v1/admin/users/{userId}/restore
POST /api/v1/admin/users/{userId}/revoke-sessions
GET  /api/v1/admin/audit?action=&from=&to=&page=&pageSize=
```

以上接口仅允许 `ADMIN` 角色访问。禁用、恢复和撤销会话必须提交受控的 `reasonCode`，避免将任意敏感说明写入审计库。

## 验证

```powershell
mvn -q "-Dtest=SqlTeacherCloudServerTest,HttpCloudApiClientTest" test
mvn test
```

聚焦验证覆盖管理员权限、账号禁用与恢复、令牌即时失效、最后管理员保护、审计筛选、敏感字段排除以及 HTTP 客户端序列化路径。
