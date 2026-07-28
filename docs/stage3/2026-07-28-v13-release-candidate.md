# SQLTeacher v1.3.0 发布候选验收

## 结论

2026-07-28 在 Windows 11、Java 21 环境完成本地发布候选验证。自动化测试、CLI 集成验证、正式 Windows 打包、云端 HTTPS 健康检查、产物哈希复核和运行数据排除检查通过。

本记录不代表已经创建 Git 标签、推送代码、部署 v1.3 服务端或发布 GitHub Release。

## 验证结果

| 门禁 | 命令/检查 | 结果 |
|---|---|---|
| 全量自动化 | 干净标签源码执行 `mvn test` | 260 项通过，0 失败、0 错误 |
| Stage 0 | `TechnologyVerificationApp` | Java、JavaFX、SQLite、MySQL、MariaDB 通过 |
| Stage 1 | `StageOneVerificationApp` | Spring、app.db、demo.db 通过 |
| 本地 AI | Stage 0/1 Ollama 探测 | 未运行，按设计降级为警告，不阻断非 AI 功能 |
| Windows 打包 | `.\packaging\package-stage1.ps1` | EXE、app-image、ZIP、SHA256SUMS 生成成功 |
| 正式云地址 | 检查 `SQLTeacher.cfg` | 包含 `https://api.sqlteacher.tech` |
| HTTPS 健康 | `GET https://api.sqlteacher.tech/health` | `status=ok` |
| 运行数据排除 | 检查 JAR 363 项、ZIP 322 项 | 未发现 app-data、数据库、日志、会话或 secrets 路径 |
| 哈希复核 | 对 EXE/ZIP 重新计算 SHA-256 | 与 `SHA256SUMS.txt` 一致 |

## 候选产物

```text
SQLTeacher-1.3.0.exe
  93c532a64d42d083b5c8ff923c588f95e7dee1db06611f09b4ac79027f871a53

SQLTeacher-1.3.0-windows-x64.zip
  4e4e4414fe4c90f1eafb393bfda48054bc1f6303a064089282bb0395a6c7812c
```

以上哈希来自提交 `7ef5522` 的独立干净工作树本地构建；GitHub Actions 会重新构建并生成其自身的 `SHA256SUMS.txt`。本地产物位于独立工作树的 `target/installer/`，该目录不提交 Git。安装器未代码签名，Windows 可能显示来源提示。

## 发布前仍需的人工作业

- 在独立测试账号上完成一次教师发布任务、学生断网提交/重试、教师导出和管理员恢复演练。
- 部署 v1.3 云端 JAR 前创建生产备份，并按灰度与回滚步骤验证新表迁移。
- 确认无 P0 缺陷后再提交/推送版本冻结变更，创建 `v1.3.0` 标签并发布 Release。
