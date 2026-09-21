# 云端服务部署

> 适用版本：3.8.0 及后续兼容版本（随发布更新）；内容自 v1.10 起持续维护，早期历史配置（如 v1.2 的 JVM 参数寻址）以当前运维记录为准。

目标服务器：`8.130.47.235`（阿里云 ECS，Ubuntu 24.04）。云服务处理账号、角色、班级成员关系、任务与提交、课程题库版本、任务快照、教师反馈、通知和同步 API；桌面端数据库密码、本地数据库文件及用户自带网络 AI API Key 不上传至服务端。

从 v1.2 起，桌面端的云服务地址必须通过 JVM 参数 `-Dsqlteacher.cloud.base-url=https://api.<学校域名>` 配置。未配置时应用会使用不可路由的安全占位地址，云端教学功能保持不可用；客户端拒绝任何非回环地址的 HTTP URL。`http://127.0.0.1` 与 `http://localhost` 只可用于自动化或本机集成测试。

## 前置条件

- 为服务器绑定正式域名 `api.sqlteacher.tech`，并将 A 记录指向 ECS 公网 IP。
- 在阿里云安全组中仅开放 `80/tcp`、`443/tcp` 和受来源限制的 `22/tcp`。不要开放 `18080`、SQLite 或 MySQL 端口。
- 服务器上的既有 Node 服务使用 3000、3001、5173 端口；SQLTeacher 不得复用或停止它们。
- 所有真实密码、API Key、令牌和证书私钥只存在服务器受限文件中，不进入 Git、桌面配置、备份、日志或文档。

## ECS 初始化

以 root 登录后执行：

```bash
apt-get update
apt-get install -y nginx certbot python3-certbot-nginx sqlite3
adduser --system --group --home /opt/sqlteacher sqlteacher
install -d -o sqlteacher -g sqlteacher -m 0750 /opt/sqlteacher/data
install -d -o root -g sqlteacher -m 0750 /opt/sqlteacher/shared
install -d -o root -g sqlteacher -m 0750 /etc/sqlteacher
```

Java 运行时不使用系统包：将 Temurin JDK 25 JRE（如 `OpenJDK25U-jre_x64_linux_hotspot_25.x.x_x.tar.gz`，可经清华 TUNA 镜像在 ECS 本地下载）解压到该版本的 `runtime/jdk25/` 目录，由 `run-cloud.sh` 以 `runtime/jdk25/bin/java` 绝对路径启动。系统 Java（若存在）不受影响，回滚到旧版本目录即回到旧运行时。

创建 `/etc/sqlteacher/cloud.env`，权限必须为 `0640 root:sqlteacher`：

```ini
SQLTEACHER_CLOUD_PORT=18080
SQLTEACHER_CLOUD_DB=/opt/sqlteacher/data/cloud.db
SQLTEACHER_UPDATE_MANIFEST=/opt/sqlteacher/shared/update-manifest.json
SQLTEACHER_FEEDBACK_HASH_SECRET=<至少 32 字符的独立随机值>
SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_EMAIL=admin@your-school.example
SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD=<随机强密码>
# v3.4.3 OKB-4：官方课程知识库分发（可选；未配置时两个端点均返回明确 404，不影响其它功能）
SQLTEACHER_KNOWLEDGE_BUNDLE_MANIFEST=/opt/sqlteacher/shared/knowledge-bundle-manifest.json
SQLTEACHER_KNOWLEDGE_BUNDLE_FILE=/opt/sqlteacher/shared/official-db-concepts-1.0.0.zip
```

首次启动时，服务将创建或提升此邮箱对应的管理员账号。完成首次管理员登录后，应删除 `SQLTEACHER_CLOUD_BOOTSTRAP_ADMIN_PASSWORD` 并重启服务，避免该引导凭据持续存在。

### 邮件通道（v3.8.0 ACC-S1，密码找回与邮箱验证依赖）

密码重置与邮箱绑定验证码经邮件发送。配置以下环境变量后走真实 SMTP；**未配置 `SQLTEACHER_CLOUD_SMTP_HOST` 时邮件写入 `<data>/mails` 文件 outbox（行为与旧版一致），公网用户将收不到邮件，密码找回不可用**，因此生产环境必须配置：

```ini
SQLTEACHER_CLOUD_SMTP_HOST=smtp.your-provider.example
SQLTEACHER_CLOUD_SMTP_PORT=587
SQLTEACHER_CLOUD_SMTP_USERNAME=noreply@your-domain.example
SQLTEACHER_CLOUD_SMTP_PASSWORD=<SMTP 凭据>
SQLTEACHER_CLOUD_SMTP_FROM=noreply@your-domain.example
# 默认启用 STARTTLS；仅内网明文中继才显式设为 false
# SQLTEACHER_CLOUD_SMTP_STARTTLS=true
```

行为约定：SMTP 未配置或缺少发件地址时自动降级文件 outbox 并记录告警日志；发送失败不改变接口的防枚举语义（请求恒返回统一响应），仅在服务端日志记录失败类别与收件人域（不含本地部分与凭据）。验证码为 6 位数字、重置码 15 分钟/验证码 30 分钟内有效，找回密码不再要求邮箱已验证，也**不再产生指向服务器的重置链接页面**。

### 教师升级码签发（v3.8.0 ACC-S4，决策点 1 方案 B）

教师角色除"已有教师建班时附带授予"外，新增管理员签发的**一次性升级码**通道：码为 8 位大写字符（去除 0/O/1/I），库中仅存 SHA-256 哈希，明文只在签发响应出现一次，默认 30 天有效（`ttlDays` 可调 1–365），可撤销；签发、撤销与兑换全部写 `admin_audit`，兑换接口限流 5 次/账号/时，任何失败统一返回同一种错误且不消耗码。教师拿到码后在「班级与云端 → 账号安全与数据治理 → 教师身份」输入兑换，会话立即获得教师角色。

管理操作经 SSH 在服务器本机用 curl 完成（端口仅监听回环，不要通过 Nginx 暴露 `/api/v1/admin`）：

```bash
# 1. 管理员登录获取 accessToken
curl -s http://127.0.0.1:18080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@your-school.example","password":"..."}' | jq -r .accessToken

# 2. 签发升级码（响应中的 code 只出现这一次，直接交给教师）
curl -s http://127.0.0.1:18080/api/v1/admin/role-codes -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"ttlDays":30}'

# 3. 查看签发记录（只有码哈希与使用状态）
curl -s http://127.0.0.1:18080/api/v1/admin/role-codes -H "Authorization: Bearer $TOKEN"
# 4. 撤销未使用的码（路径参数为列表中的 codeHash）
curl -s -X POST http://127.0.0.1:18080/api/v1/admin/role-codes/<codeHash>/revoke -H "Authorization: Bearer $TOKEN"
```

## 应用与 systemd

将构建产物部署到 `/opt/sqlteacher/releases/<version>/app/`，其中包含应用 JAR 和 `lib/` 依赖目录；将 `packaging/cloud/run-cloud.sh` 放入对应版本的 `bin/`。
正式发布后，将 Release 生成的 `update-manifest.json` 以 `root:sqlteacher`、`0640` 原子替换到 `/opt/sqlteacher/shared/update-manifest.json`。该文件仅包含签名信封，发布私钥不得上传服务器。

官方课程知识库（v3.4.3 OKB-4）为可选分发：用 `packaging/build-knowledge-bundle.ps1` 生成 `official-db-concepts-<version>.zip` 与同目录的 `<bundleId>-manifest.json`（含 `bundleId/version/sizeBytes/sha256`），把 zip 与 manifest 以 `root:sqlteacher`、`0640` 放到 `/opt/sqlteacher/shared/`，并让上面两个环境变量分别指向它们。`GET /api/v1/app/knowledge-bundle-manifest` 下发 manifest JSON，`GET /api/v1/app/knowledge-bundle` 流式下发 zip，两者均**无需认证**（公开教学内容）；桌面端下载后自行做 sha256 与大小校验再导入。未配置这两个变量时端点返回 `KNOWLEDGE_BUNDLE_UNAVAILABLE`（404），桌面端提示“云端暂未提供”，不影响随包知识库与本地功能。知识库内容属教材衍生资料，上传前须确认已获分发授权。

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
certbot --nginx -d api.sqlteacher.tech
nginx -t && systemctl reload nginx
```

若没有域名，保持 Nginx 配置未启用且 API 仅监听 `127.0.0.1`。不能以裸 IP 暴露登录、同步或令牌接口，也不得将裸 IP HTTP 配置为桌面端云服务地址。

## 知识检索服务（Qdrant + fastembed）

云端课程知识向量检索由两个补充服务承担，单元文件均在 `packaging/cloud/`：

- `sqlteacher-qdrant.service`：Qdrant 向量数据库，仅监听本机 `6333`；配置模板 `qdrant.yaml`，环境样例 `qdrant.env.example` 复制为 `/etc/qdrant/qdrant.env`（权限 `0640 root:qdrant`），API Key 使用至少 32 字节随机值。
- `sqlteacher-embedding.service`：fastembed 嵌入服务（`fastembed-server.py`，依赖 `requirements-fastembed.txt`），经 `SQLTEACHER_EMBEDDING_URL` 供服务端调用。

备份由 `sqlteacher-qdrant-backup.timer` 定时执行 `backup-qdrant.sh`。桌面/服务端连接变量（`SQLTEACHER_QDRANT_URL`、`SQLTEACHER_QDRANT_COLLECTION`、`SQLTEACHER_QDRANT_VECTOR_SIZE`、`SQLTEACHER_QDRANT_API_KEY`）见 `qdrant.env.example`。部署与验证实例见 [Qdrant 部署运维记录](../operations/2026-08-01-qdrant-deployment.md)。

## 发布后检查

```bash
systemctl status sqlteacher-cloud --no-pager
curl --fail https://api.sqlteacher.tech/health
ss -lntp | grep 18080
```

验证注册、登录、越权拒绝、班级隔离、令牌撤销和服务重启后恢复。每次发布前备份 `/opt/sqlteacher/data/cloud.db`，并在隔离目录完成一次恢复演练。

## 备份、恢复与证书续期

将 `backup-cloud.sh`、`restore-cloud.sh` 安装到 `/opt/sqlteacher/current/bin/`。备份脚本使用 SQLite 在线备份并执行完整性检查，默认保留 30 天：

```bash
/opt/sqlteacher/current/bin/backup-cloud.sh
certbot renew --dry-run
```

将每日备份安装为 systemd 定时任务。定时器默认每天 03:15 执行，并加入最多 30 分钟的随机延迟；`Persistent=true` 会在服务器错过执行时间后补跑：

```bash
install -m 0644 packaging/cloud/sqlteacher-backup.service /etc/systemd/system/sqlteacher-backup.service
install -m 0644 packaging/cloud/sqlteacher-backup.timer /etc/systemd/system/sqlteacher-backup.timer
systemctl daemon-reload
systemctl enable --now sqlteacher-backup.timer
systemctl start sqlteacher-backup.service
systemctl status sqlteacher-backup.service --no-pager
systemctl list-timers sqlteacher-backup.timer --no-pager
```

备份失败时 `sqlteacher-backup.service` 会进入失败状态并写入 journal。告警系统应监控该 unit，以及 `certbot.service` 的失败状态；告警接收地址或令牌只放在服务器受限配置或云监控中，不写入仓库。

安装每小时运维探针。探针会验证本机与公网 HTTPS 健康端点、Nginx 配置、Certbot 与备份定时器、证书至少剩余 30 天，以及最近 36 小时内存在通过完整性检查的 SQLite 备份：

```bash
install -o root -g root -m 0755 packaging/cloud/check-cloud-operations.sh /opt/sqlteacher/current/bin/check-cloud-operations.sh
install -m 0644 packaging/cloud/sqlteacher-operations-check.service /etc/systemd/system/sqlteacher-operations-check.service
install -m 0644 packaging/cloud/sqlteacher-operations-check.timer /etc/systemd/system/sqlteacher-operations-check.timer
systemctl daemon-reload
systemctl enable --now sqlteacher-operations-check.timer
systemctl start sqlteacher-operations-check.service
systemctl status sqlteacher-operations-check.service --no-pager
```

将云监控或外部告警订阅到以下任一失败信号：

- `sqlteacher-operations-check.service` 非零退出；
- `sqlteacher-backup.service` 非零退出；
- `certbot.service` 非零退出。

探针默认阈值可通过 systemd unit 的环境变量覆盖：`SQLTEACHER_MIN_CERTIFICATE_SECONDS` 和 `SQLTEACHER_MAX_BACKUP_AGE_SECONDS`。

恢复前必须选定 `/opt/sqlteacher/backups/` 下的备份文件。脚本会停止服务、保留恢复前副本、校验并恢复数据库，再启动服务及检查本机健康端点：

```bash
/opt/sqlteacher/current/bin/restore-cloud.sh /opt/sqlteacher/backups/cloud-<UTC时间>.db
```
