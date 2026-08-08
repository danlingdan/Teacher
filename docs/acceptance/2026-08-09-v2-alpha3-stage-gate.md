# SQLTeacher v2.0.0-alpha.3 阶段门禁

> 验证日期：2026-08-09
> 分支：`main`
> 版本：`2.0.0-alpha.3`
> 发布状态：仅本地准备；未提交、未打标签、未推送、未创建 GitHub Release

## 1. 功能与迁移门禁

聚焦测试覆盖：

- QUIZ/TRACE 类型匹配、正确、错误和未完成 artifact 的确定性评价；
- schema 12 空库初始化、schema 11 升级、重复启动、失败回滚和未来 schema 拒绝；
- 内置二叉树课程、课程地图和 QUIZ/TRACE 定义解码；
- 活动会话、评价、artifact 哈希和结构化事件持久化；
- SQL/QUIZ/TRACE 跨活动诊断、owner 隔离和 `RETRY_ACTIVITY`；
- 教师/管理员复核权限、反馈发布和学生 owner 隔离读取；
- Spring Bean、FXML、CSS、i18n 与 Windows 打包合同。

完整命令：

```powershell
mvn test
```

结果：438 项测试，0 failure，0 error，2 项按既有环境条件跳过。

## 2. 真实桌面纵向走查

通过 `JAVA_TOOL_OPTIONS=-Dsqlteacher.data.dir=<target 下隔离目录>` 启动桌面端并以访客进入：

1. 课程地图显示“数据结构与算法 / 二叉树遍历”；
2. 直接打开 TRACE 后保持目标活动，不再被异步目录默认选择覆盖；
3. 依次点击 A → B → D → E → C → F，提交后 UI 显示“前序遍历顺序正确”；
4. 隔离数据库产生一条 PASSED TRACE 评价和一条 `ACTIVITY_PASSED` 事件；
5. QUIZ 选择“根 → 左 → 右”后产生一条 PASSED 评价；
6. 窗口保持响应，相关运行日志无 CSS 解析、异常或错误记录。

首次尝试通过 JavaFX Maven 插件参数隔离时，该插件未把系统属性传入子进程，因此本机
`%LOCALAPPDATA%\SQLTeacher\app.db` 已按正常启动流程从 schema 11 追加迁移到 schema 12。
迁移后只读检查确认版本 12、两个二叉树活动和 `activity_feedback` 表存在；没有删除或覆盖旧数据。

## 3. Windows 本地打包门禁

```powershell
./packaging/package-stage1.ps1
```

结果：

| 项目 | 结果 |
| --- | --- |
| Windows package version | `2.0.1003` |
| EXE | `SQLTeacher-2.0.0-alpha.3.exe` |
| ZIP | `SQLTeacher-2.0.0-alpha.3-windows-x64.zip` |
| app-image | `target/installer/SQLTeacher` |
| 校验和 | `SHA256SUMS.txt` 两行，复算全部匹配 |
| SBOM | `sqlteacher-sbom.json` 已生成 |
| 陈旧版本化 EXE/ZIP | 0；目录内只存在当前版本两项发布资产 |
| 生产 Cloud URL | app-image launcher 包含 `https://api.sqlteacher.tech` |
| ZIP 扫描 | 0 个项目 `.env`、`.secrets`、`app-data`、数据库、日志或凭据条目 |

宽匹配扫描命中 JDK 自带的 `jmxremote.password.template` 与 `pkcs11cryptotoken.md`；二者属于
标准运行时模板/许可文档，不含 SQLTeacher 凭据或业务数据。app-image 使用隔离数据目录启动成功，
生成 schema 12 和两个内置二叉树活动。

## 4. 安全结论与限制

- QUIZ/TRACE 评价器不访问 JDBC、AI、网络、文件系统或任意代码执行；
- SQL 安全分析、确认、只读执行、超时和结果上限没有变化；
- 教师复核服务在应用层验证教师/管理员身份，学生反馈读取按 owner 隔离；
- 本版本只实现本地活动证据与反馈；通用活动 Cloud 同步属于后续 Alpha；
- 本轮未执行 EXE 安装/卸载，不创建标签、GitHub Release 或生产部署。

实现记录见 [Stage 11](../history/stages/stage11/2026-08-09-v2-alpha3-binary-tree-learning-loop-implementation.md)。
