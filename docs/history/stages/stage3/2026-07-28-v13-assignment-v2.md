# V13-02 任务 v2、草稿与并发控制

## 范围

本阶段将既有班级任务升级为可并发编辑的任务 v2，不涉及学生正式提交或任务学情统计。

## 已实现

- `ClassAssignment` 增加描述、发布时间、复制来源和单调递增版本号；
- 教师可创建 `DRAFT` 或直接创建 `PUBLISHED` 任务；
- 教师可复制既有任务，副本固定从 `DRAFT`、版本 1 开始；
- 教师可按任务状态筛选，学生永远不能读取草稿、撤回或归档任务；
- 详情、截止时间和状态修改必须携带 `expectedVersion`；
- 旧版本修改返回 HTTP 409、稳定错误码 `ASSIGNMENT_VERSION_CONFLICT` 和最新安全快照；
- 发布、关闭、撤回、归档继续由服务端状态机校验；
- v1.2 `class_assignments` 表启动时原位补齐 v2 字段，既有非草稿任务以 `created_at` 作为 `published_at`；
- 桌面端现有编辑和状态操作改为提交当前版本号，避免静默覆盖。

## 数据变更

`class_assignments` 新增：

- `description text not null default ''`；
- `published_at text`；
- `copied_from_assignment_id text`；
- `version integer not null default 1`；
- `(classroom_id, status, created_at desc)` 查询索引。

迁移保持幂等，不修改已发布字段的语义，不删除旧任务。

## 验证

```powershell
mvn -q "-Dtest=SqlTeacherCloudServerTest,HttpCloudApiClientTest" test
mvn test
```

结果：251 项测试通过，0 失败、0 错误、0 跳过。新增覆盖包括草稿可见性、复制、状态筛选、版本递增、旧版本冲突、发布时刻和 v1.2 表结构升级。
