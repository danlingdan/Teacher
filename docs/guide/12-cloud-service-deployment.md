# v1.1 云端服务部署

目标服务器：`8.130.47.235`（阿里云 ECS，Ubuntu 24.04）。云服务仅处理账号、角色、班级成员关系和同步 API；桌面端数据库密码及用户自带网络 AI API Key 不上传至服务端。

## 前置条件

- 为服务器绑定一个正式域名，例如 `api.<学校域名>`，并将 A 记录指向 ECS 公网 IP。
- 在阿里云安全组中仅开放 `80/tcp`、`443/tcp` 和受来源限制的 `22/tcp`。不要开放 `18080`、SQLite 或 MySQL 端口。
- 服务器上的既有 Node 服务使用 3000、3001、5173 端口；SQLTeacher 不得复用或停止它们。
- 所有真实密码、API Key、令牌和证书私钥只存在服务器受限文件中，不进入 Git、桌面配置、备份、日志或文档。

## ECS 初始化

以 root 登录后执行：

```bash
apt-get update
apt-get install -y openjdk-21-jre-headless nginx certbot python3-certbot-nginx
adduser --system --group --home /opt/sqlteacher sqlteacher
install -d -o sqlteacher -g sqlteacher -m 0750 /opt/sqlteacher/data
install -d -o root -g sqlteacher -m 0750 /etc/sqlteacher
```

创建 `/etc/sqlteacher/cloud.env`，权限必须为 `0640 root:sqlteacher`：

```ini
SQLTEACHER_CLOUD_PORT=18080
SQLTEACHER_CLOUD_DB=/opt/sqlteacher/data/cloud.db
SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_EMAIL=admin@your-school.example
SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD=<随机强密码>
```

首次启动时，服务将创建或提升此邮箱对应的管理员账号。完成首次管理员登录后，应删除 `SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD` 并重启服务，避免该引导凭据持续存在。

## 应用与 systemd

将构建产物部署到 `/opt/sqlteacher/releases/<version>/app/`，其中包含应用 JAR 和 `lib/` 依赖目录；将 `packaging/cloud/run-cloud.sh` 放入对应版本的 `bin/`。

```bash
ln -sfn /opt/sqlteacher/releases/<version> /opt/sqlteacher/current
install -m 0644 packaging/cloud/sqlteacher-cloud.service /etc/systemd/system/sqlteacher-cloud.service
systemctl daemon-reload
systemctl enable --now sqlteacher-cloud
curl --fail http://127.0.0.1:18080/health
```

服务进程以 `sqlteacher` 账户运行，端口仅监听本机。应用日志通过 `journalctl -u sqlteacher-cloud` 查看；日志禁止记录授权头、密码、刷新令牌或 API Key。

## HTTPS 反向代理

将 `packaging/cloud/sqlteacher.conf.example` 复制为 `/etc/nginx/sites-available/sqlteacher`，替换示例域名；创建链接并在证书签发前先校验 HTTP 配置：

```bash
ln -sfn /etc/nginx/sites-available/sqlteacher /etc/nginx/sites-enabled/sqlteacher
nginx -t && systemctl reload nginx
certbot --nginx -d api.<学校域名>
nginx -t && systemctl reload nginx
```

若没有域名，保持 Nginx 配置未启用且 API 仅监听 `127.0.0.1`。不能以裸 IP 暴露登录、同步或令牌接口。

## 发布后检查

```bash
systemctl status sqlteacher-cloud --no-pager
curl --fail https://api.<学校域名>/health
ss -lntp | grep 18080
```

验证注册、登录、越权拒绝、班级隔离、令牌撤销和服务重启后恢复。每次发布前备份 `/opt/sqlteacher/data/cloud.db`，并在隔离目录完成一次恢复演练。
