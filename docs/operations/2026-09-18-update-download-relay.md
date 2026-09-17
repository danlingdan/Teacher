# 2026-09-18 更新下载中继（/gh/ 反代）部署记录

## 背景与目标

v3.5.2 发布记录的积压项：GitHub 直链在国内部分网络被重置（`Remote host terminated the
handshake`），而 v1.11.3 规划的镜像站（`mirror.sqlteacher.tech` / `download.sqlteacher.tech`）
的 DNS 与文件同步至今未部署（本机 nslookup 仍为 NXDOMAIN）。本次在不新增域名、证书与 DNS
的前提下，让既有 `api.sqlteacher.tech` 兼任 GitHub Releases 的下载中继：客户端在 GitHub 直链
失败后自动回退到 `https://api.sqlteacher.tech/gh/<github 原路径>`。

## 变更内容（最小增量）

- 服务器：`/etc/nginx/sites-available/sqlteacher-api` 的 443 server 块新增两个 location：
  - `location ^~ /gh/` → `proxy_pass https://github.com/;`（`proxy_ssl_server_name on`、
    `Host github.com`、`proxy_read/send_timeout 600s`），并把资产 302 的 Location 重写为
    `/gh-asset/…`（`proxy_redirect` 覆盖 `release-assets` 与 `objects` 两个 CDN 主机），
    使整条下载链路（含断点续传的 Range 请求）都经由本机；
  - `location ^~ /gh-asset/` → `proxy_pass https://release-assets.githubusercontent.com/;`。
  - 缓冲：两处均设 `proxy_buffers 16 8k; proxy_buffer_size 16k; proxy_busy_buffers_size 16k;`
    ——GitHub 302 的响应头（带长签名串的 S3 Location）超出默认 8k/4k 头缓冲，第一次部署曾
    因此 502（error log: `upstream sent too big header`），扩大后恢复。
  - 未改动 Java API 上游（`127.0.0.1:18080`）、80 端口跳转、ACME location 与其他站点；
    仅 `systemctl reload nginx`，未重启 `sqlteacher-cloud`。
- 回滚：部署前备份
  `/etc/nginx/sites-available/sqlteacher-api.bak-v354-relay-20260918-020933`，恢复后
  `nginx -t && systemctl reload nginx` 即可。
- 客户端（v3.5.4）：`SecureUpdateService` 源顺序 = GitHub 直链 → 云端中继（始终启用）→
  镜像站（设置开关，默认关）；`/gh/` 中继 URL 由清单 installerUrl 推导（host 换为清单源主机）；
  请求头部超时 20 分钟收紧为 2 分钟；新增 30 秒零进度停滞检测（放弃当前源，保留 .part 断点）。
  云端主机加入下载主机白名单（与清单端点同一信任源）。

## 验证（均通过）

- `sudo nginx -t` 通过后 reload；`curl https://api.sqlteacher.tech/health` 返回 `status: ok`，
  `/api/v1/app/update-manifest` 返回 200——Java API 零影响。
- 本机（国内网络，GitHub 直连曾失败的环境）：
  - `GET /gh/danlingdan/Teacher/releases/download/v3.5.3/SQLTeacher-3.5.3.exe`（Range
    0-1023）→ 302，Location 正确重写为 `/gh-asset/github-production-release-asset/…`；
  - 跟随后最终 206 / 1024B；10MB 分段实测吞吐 ≈1.68 MB/s（278MB 全量约 3 分钟）；
  - **全量 278,649,497 字节经中继下载，SHA-256 与云端签名清单完全一致**
    （`f3563134…8c06`）。

## 已知限制

- 中继流量消耗 ECS 公网出带宽（实测 ≈13 Mbps，按量计费口径以实例账单为准）；仅作为 GitHub
  直链失败后的回退路径，常规成功下载不经过中继。
- 客户端对"收到响应头后读取永久挂死"的极端情形依赖 TCP 层错误兜底；30 秒停滞检测覆盖
  "仍有返回但零字节"与"间歇性涓流"两类情形。
- `mirror.sqlteacher.tech` / `download.sqlteacher.tech` 的 DNS 仍未部署；客户端对其的回退
  逻辑保留且不影响本次中继功能。
