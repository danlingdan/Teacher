# 2026-10-01 云服务中断约 11.5 小时：certbot 例行任务 + 瞬时 DNS 失败 + 自检安全网失效

- **现象**：2026-10-01 06:39:31 起 `api.sqlteacher.tech` 80/443 拒绝连接，公网 API/更新清单/下载
  全部不可达；Java 云服务本身全程健康（loopback 18080 正常监听）。18:01 恢复，公网 health 200、
  更新清单 3.9.0 正常。与 2026-10-01 凌晨的 v3.9.0 发布无关（清单轮换在 05:15 完成且当时验证通过）。
- **触发与根因（nginx 层）**：06:39:21 `apt-daily-upgrade.timer` 启动；10 秒后 06:39:31
  `certbot.timer` 的晨间例行运行（nginx 插件，双证书均在有效期内、无需续期）按惯例 stop nginx
  再校验启动。重启时瞬时 DNS 失败——`[emerg] host not found in upstream "github.com" in
  /etc/nginx/sites-enabled/sqlteacher-api:38`（v3.5.4 下载中继的 `proxy_pass` 写了域名，nginx
  在配置加载时必须解析成功）——`nginx -t` 失败，nginx 从此未再启动（NRestarts=0，单元未配置
  自动重启）。DNS 瞬断与 apt-daily-upgrade 同时段高度相关（升级过程重启 systemd-resolved 的
  常见副作用），日志无法实证。历史 8/20、8/21、9/1、9/13、9/15 的同类"stop→start"对均成功，
  本次是首次撞上 DNS 瞬断。
- **安全网失效（自检层，独立缺陷）**：`sqlteacher-operations-check.service` 的
  `ReadWritePaths=/run/nginx.pid` 在 nginx 停止期间因 pid 文件不存在导致挂载命名空间建立失败
  （status=226/NAMESPACE），当天 13:14/14:18/15:18/17:14 各次自检全部未能运行——监控恰好在
  故障期间全盲。nginx 恢复后手动触发自检通过（含证书/备份/Qdrant 快照检查）。
- **恢复动作**：DNS 已恢复正常且 `nginx -t` 通过后 `systemctl start nginx`（最小恢复，无配置
  变更）；验证 80/443 监听、loopback health 301→公网 health 200、公网清单 3.9.0、
  sqlteacher-operations-check 手动运行通过。未动证书、未动 Java 服务、未动应用数据。
- **改进项（待排期，未实施）**：
  1. 下载中继改为运行时解析：`resolver 223.5.5.5 valid=300s ipv6=off;` + 变量 `proxy_pass
     https://$gh/...`，消除 nginx 启动对 github.com DNS 的硬依赖；
  2. `sqlteacher-operations-check.service` 的 `ReadWritePaths=/run/nginx.pid` 加 `-` 前缀
     （`ReadWritePaths=-/run/nginx.pid`），pid 缺失不再令自检单元自身失败；
  3. nginx 单元加 `Restart=on-failure`（drop-in），启动失败自动重试；
  4. 自检脚本增加"公网 HTTPS /health + nginx active"断言，失败时尝试 `systemctl start nginx`
     并醒目报错（当前脚本只覆盖证书/备份/快照/配置测试，不覆盖 nginx 存活）。

返回 [运维记录索引](README.md) · [文档中心](../README.md)。
