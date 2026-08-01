# SQLTeacher v1.11 生产云服务部署记录

> 部署时间：2026-08-02（Asia/Shanghai）
> 生产域名：`https://api.sqlteacher.tech`
> 目标版本：`/opt/sqlteacher/releases/1.11.0`
> 部署脚本：`packaging/cloud/deploy-v111.py`（paramiko，分阶段 probe/backup/upload/swap/verify）

## 上线前门禁

- 生产原版本为 `/opt/sqlteacher/releases/1.10.1`，`sqlteacher-cloud.service` 为 active/enabled。
- 上线前在线备份：`/opt/sqlteacher/backups/cloud-20260801T202737Z.db`，`pragma integrity_check` 返回 `ok`。
- 备份 SHA-256：`9c0a2fc75ed9a690e50251e89c4f24bd3d86547d32f5d92c3672b8f86bf60e88`。
- 磁盘剩余 13 GB；本地部署包 35 个运行时依赖 + `Teacher-1.11.0.jar`，远端 SHA-256 与本地一致。

## 部署过程

- 上传部署包到 `/opt/sqlteacher/releases/1.11.0/`，校验远端 SHA-256 与本地一致后解压。
- 将 JAR 与依赖整理为旧版一致的 `app/` 布局：`app/Teacher-1.11.0.jar` + `app/lib/*`（35 个 jar）。
- 从 `releases/1.10.1/bin` 复制运维脚本（`run-cloud.sh`、`backup-cloud.sh`、`check-cloud-operations.sh`、`restore-cloud.sh`）到 `releases/1.11.0/bin`，`run-cloud.sh` 语法检查通过。
- 原子切换 `/opt/sqlteacher/current` → 1.11.0 并重启服务。

### 首次部署失败与回滚记录

- 初次切换时 1.11.0 release 只包含 `app/`（jar+lib），缺少 systemd ExecStart 指向的 `bin/run-cloud.sh`，导致 `status=203/EXEC` 循环重启。
- 立即回滚：`ln -sfn /opt/sqlteacher/releases/1.10.1 /opt/sqlteacher/current` + 重启，服务恢复 active、`/health` 返回 apiVersion=1.10。
- 补复制 `bin/` 脚本后重新切换成功；回滚点保留为 1.10.1 与上线前备份。

## 上线后验证

- `sqlteacher-cloud.service` active，`/opt/sqlteacher/current` → `releases/1.11.0`。
- 本机 `127.0.0.1:18080` 与公网 HTTPS `/health` 均返回 `status=ok`、`apiVersion=1.11`；18080 未暴露为公网监听。
- 公网 `/api/v1/app/capabilities` 返回全部 12 个新能力位：`SIGNED_UPDATES/PROBLEM_REPORTS/CHANGE_PASSWORD/REPORT_STATUS/REPORT_WITHDRAWAL/REPORT_EXPORT/SCREENSHOT_ATTACHMENT/SESSIONS/ACCOUNT_EXPORT/ACCOUNT_DELETION/PASSWORD_RESET/ROLLOUT`。
- v1.11 迁移生效：`email_verifications`、`reset_tokens`、`account_tasks` 三张表均存在（`addColumnIfMissing` 幂等迁移）。
- 使用生产管理员完成登录/登出探针（未写入测试业务数据）。
- 无新增 error/exception 日志。

## 运行边界与已知限制

- **SMTP 邮件通道**：`FileMailSender` 写入本地 outbox（`/opt/sqlteacher/data/mails/`），生产 SMTP 真实投递待运维配置（SPF/DKIM）；在配置前密码重置邮件不会真实送达，客户端 `requestPasswordReset` 仍返回统一响应。
- **受控镜像站**：`mirror.sqlteacher.tech` / `download.sqlteacher.tech` 的 DNS 与静态文件同步未部署；当前客户端镜像开关默认关闭，官方 GitHub 下载不受影响。
- **原生通知**：桌面侧使用白名单策略，未引入 SystemTray 原生依赖，失败静默降级应用内通知。
- 回滚点保留为 `/opt/sqlteacher/releases/1.10.1` 与上线前 SQLite 备份。
