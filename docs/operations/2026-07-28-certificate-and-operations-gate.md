# 2026-07-28 证书与运维门禁记录

## 结论

证书续期和本机运维自动检查已经通过，HTTP-01 公网验证恢复正常，不需要为本轮续期引入阿里云 DNS API 凭据。

V13-01 仍有一个外部配置项未闭环：服务器尚未配置可达负责人的 CloudMonitor、邮件或 Webhook 通知通道。当前失败会进入 systemd failed 状态并写入 journal，但不会主动推送。

## 证书与 HTTPS

2026-07-28 现场验证结果：

- `http://api.sqlteacher.tech/health` 返回 301 并跳转 HTTPS；
- `https://api.sqlteacher.tech/health` 返回 200；
- `api.sqlteacher.tech` 证书有效至 2026-10-20，检查时剩余 84 天；
- `certbot renew --dry-run` 对 `api.sqlteacher.tech` 和 `sqlteacher.tech` 两张证书全部成功；
- `certbot.timer` 已启用且处于 active；
- 续期部署钩子会执行 `systemctl reload nginx`。

## 备份与恢复

新增并部署：

- `sqlteacher-backup.service`；
- `sqlteacher-backup.timer`，每日 03:15 执行并加入最多 30 分钟随机延迟；
- 30 天备份保留策略沿用 `backup-cloud.sh`。

现场执行生成 `/opt/sqlteacher/backups/cloud-20260728T101431Z.db`：

- 文件大小 94,208 字节；
- 权限 `0640 sqlteacher:sqlteacher`；
- SQLite `integrity_check` 返回 `ok`。

随后在 `/opt/sqlteacher/restore-probe.*` 临时目录完成隔离恢复探针：复制前后 SHA-256 一致，恢复副本完整性为 `ok`，包含 10 张表。探针目录在验证结束后自动删除，生产数据库和服务未停止。

## 每小时运维探针

新增并部署：

- `check-cloud-operations.sh`；
- `sqlteacher-operations-check.service`；
- `sqlteacher-operations-check.timer`。

探针每小时检查：

1. 本机与公网 HTTPS Cloud API `/health`；
2. Nginx 配置有效性；
3. `certbot.timer` 和 `sqlteacher-backup.timer` 状态；
4. 正式证书剩余时间不少于 30 天；
5. 最近备份不超过 36 小时；
6. 最近备份通过 SQLite 完整性检查。

现场执行结果为 `Result=success`、`ExecMainStatus=0`。公网和本机健康端点在部署后均返回 200。

## 已知运维事项

- 服务器没有 CloudMonitor Agent、邮件发送器或 Webhook 告警配置。下一步需要选择通知通道，并订阅 `certbot.service`、`sqlteacher-backup.service` 和 `sqlteacher-operations-check.service` 的失败信号。
- `aegis.service` 从 2026-03-27 起处于 failed，但阿里云安骑士相关进程仍在运行。该状态早于 SQLTeacher 部署，本轮没有重启或清理阿里云安全代理；应在阿里云控制台确认主机安全客户端是否正常纳管。
- UFW 当前未启用。SQLTeacher API 仍只监听 `127.0.0.1:18080`，公网由 Nginx 80/443 提供；ECS 安全组规则需要在阿里云控制台继续确认。

## 验证命令

```bash
certbot renew --dry-run
systemctl start sqlteacher-backup.service
systemctl start sqlteacher-operations-check.service
systemctl list-timers --all
curl --fail http://127.0.0.1:18080/health
curl --fail https://api.sqlteacher.tech/health
```
