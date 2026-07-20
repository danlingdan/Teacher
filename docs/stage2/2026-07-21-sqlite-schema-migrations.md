# SQLite 应用库 schema 版本与迁移机制

## 1. 交付范围

本功能为 Demo 后扩展开发引入 `app.db` schema 版本管理，解决后续连接、题库、学情和知识检索表无法安全升级的问题。

本次只管理应用数据库 `app.db`。内置教学数据 `demo.db` 仍按现有初始化方式维护，后续“一键恢复演示库”功能再单独设计其数据集版本。

## 2. 数据库变化

新增版本表：

```sql
create table schema_version (
    version integer primary key,
    description text not null,
    applied_at text not null default current_timestamp
);
```

当前 schema 版本为 `1`，对应 Demo 基线中的：

- `app_event`
- `learning_events`

已有 Demo 数据库没有 `schema_version` 表。首次由新版本启动时，迁移器使用 `create table if not exists` 接管已有表、补齐缺失表并记录版本 1，不删除现有事件数据。

## 3. 执行规则

- 迁移版本必须从 1 开始连续递增，并按版本顺序声明。
- schema 版本表创建和所有待执行迁移位于同一 JDBC 事务。
- 任一 SQL 失败时回滚本次全部待执行迁移，不写入半完成版本。
- 已应用迁移不会重复执行，应用重复启动保持幂等。
- 如果数据库记录的版本高于当前程序支持版本，初始化失败并停止继续写入，防止旧程序破坏新数据。
- 迁移异常继续通过 `SQLITE_INIT_FAILED` 应用异常路径向上报告，UI 不直接展示底层 SQL 或文件细节。

## 4. 后续新增迁移

后续数据库结构变更应在 `SqliteSchemaMigrator` 的迁移列表末尾追加一个版本，不修改已经发布的迁移内容。例如：

```text
version 1: Demo 基线表
version 2: 连接配置表
version 3: 题库与练习记录表
```

每个新版本必须同步增加：

- 空库初始化测试。
- 从上一个已发布版本升级的测试夹具。
- 重复启动测试。
- 失败回滚或约束失败测试。
- 对应阶段文档和计划进度记录。

## 5. 验证

聚焦验证：

```powershell
mvn -q "-Dtest=SqliteSchemaMigratorTest,SqliteAppDatabaseInitializerTest" test
```

覆盖场景：

- 空 `app.db` 初始化到版本 1。
- 无版本表的 `v0.1.0` Demo 数据库升级并保留数据。
- 重复执行迁移不重复记录、不丢数据。
- 后续迁移 SQL 失败时全部回滚。
- 数据库版本高于程序支持版本时拒绝继续初始化。
