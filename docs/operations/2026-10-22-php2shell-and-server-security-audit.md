# 2026-10-22 ECS 安全排查报告（PHP2Shell 专项 + 全面体检）

- 目标主机：阿里云 ECS `8.130.47.235`（`iZ0jlf20w2ns8vm9hqgwu2Z`，Ubuntu 24.04.4 LTS，内核 6.8.0-90）
- 排查方式：只读排查（未做任何修改、未重启任何服务）
- 排查时间：2026-10-22 16:0x，运行时长 210 天
- 触发原因：PHP 官方 2026-10-21 修复并披露 PHP2Shell（CVE-2026-109618 / CVE-2026-109619，CVSS 9.8）

---

## 一、PHP2Shell 专项结论：不受影响

| 检查项 | 结果 |
| --- | --- |
| `php` / `php-fpm` / `php-cgi` / `frankenphp` 可执行文件 | 全盘查找无结果（仅存在 `/etc/apparmor.d/abstractions/php` 这个 AppArmor 抽象模板，是 Ubuntu 自带文件） |
| dpkg / rpm 安装包 | 无任何 `php*` 包 |
| `/etc/php` 配置目录 | 不存在 |
| 运行中的 PHP 相关进程 | 无 |
| Docker / 容器内 PHP | 未安装 Docker |
| Web 服务形态 | 仅 Nginx 1.24.0 做反向代理与静态站点，无 PHP SAPI |

**说明**：本机不存在任何 PHP 运行环境，CVE-2026-109618/109619 无攻击面。

**但有一个衍生问题**：`sqlteacher.tech` 站点使用 `try_files $uri $uri/ /index.html` 回退，导致扫描器请求 `/wp-login.php`、`/xmlrpc.php` 等返回 **HTTP 200 + SPA 页面**。日志中今日 24 条、昨日 474 条 `.php` 探测全部命中 200，会让自动化扫描器误判本机是 WordPress 站点，持续加码扫描。建议对已知扫描路径直接 404。

---

## 二、严重问题（风险高于 PHP2Shell，建议优先处理）

### P0-1　公网可直接调用的未鉴权 MySQL 执行接口

- 地址：`http://8.130.47.235:3001/api/query`（进程 `node server/index.js`，目录 `/root/sqlteacher`）
- 监听：`*:3001`（全网卡），实测外网可达，`GET /api/health` 返回 `{"status":"ok","message":"Database connected successfully"}`
- 代码位置：`/root/sqlteacher/server/index.js:1356`
  ```js
  const { sql, params = [], dbConfig } = req.body;
  const [results] = await pool.execute(sql, params);   // 无任何鉴权、无风险分析
  ```
- 影响：任何人只要扫到 3001 端口，即可对默认连接池执行任意 SQL；还可通过 `dbConfig` 指定任意 MySQL 主机做跳板。默认池凭据来自 `/root/sqlteacher/.env`（`MYSQL_USER/MYSQL_PASSWORD`）。
- 该进程由 `pm2-root.service` 托管，**以 root 身份运行**，自 2026-04-01 起存活至今（约 7 个月）。

### P0-2　公网可访问的 Vite 开发服务器

- `*:5173`：`node /root/sqlteacher/client/node_modules/.bin/vite --host`，root 运行，2026-04-01 启动
- 实测 `GET /` 返回开发版 HTML；`/@fs/etc/hostname`、`/@fs/root/sqlteacher/.env` 返回 403（`server.fs.strict` 生效，**未**造成任意文件读取）
- 但 `client` 目录下全部源码可被无鉴权拉取，且 Vite dev server 自身存在历史 RCE/文件穿越类 CVE，属于典型"不该暴露在公网"的服务

### P0-3　公网可访问的业务数据看板

- `*:3000`：`node src/server.js`，目录 `/root/Web`，root 运行，2026-03-31 启动
- `express.static(PUBLIC_DIR)` + 直接 `XLSX.readFile()` 读取 `供应明细.xlsx`、`明细.xlsx`，属业务数据；另有 `/root/Web/.git`（完整提交历史）

### P1-1　网络边界实际是敞开的

- `ufw`：**inactive**；`iptables`：`INPUT/FORWARD/OUTPUT` 全 `ACCEPT`
- 完全依赖阿里云安全组，而 3000/3001/5173 从公网实测均可直连 → 安全组放行了这三个端口
- 对比正确暴露的服务：80/443（Nginx）、22（SSH）；正确限制在环回的服务：3306/33060（MySQL）、6333/6334（Qdrant）、11434（FastEmbed）、18080（Java API）、33023（备份客户端）

### P1-2　SSH 允许 root 密码登录且无防护

- `PermitRootLogin yes`，密码认证开启；`authorized_keys` 为 0 字节空文件（未使用密钥登录）
- 未安装 fail2ban（`inactive`，`fail2ban-client` 不存在）
- `/var/log/auth.log` 中 `Invalid user|Failed password` 共 **6804** 条；今日按来源 IP 统计最高 365 次（115.29.185.119），另有 106.53.10.20、101.96.200.119、101.96.217.61 等
- 爆破字典包含 `shikai`、`ruoyu`、`wanghehua`、`jpy`、`fyk`、`lipenglin`、`online` 等中文姓名类用户名，属针对性字典爆破
- 结论：目前未被攻破，但 root + 密码 + 无封禁 = 长期赌概率

### P2-1　`/gh/` 是开放的对外代理

- `location ^~ /gh/ { proxy_pass https://github.com/; }`，实测 `GET https://api.sqlteacher.tech/gh/` 返回 200
- 被限定到 github.com 域，危害有限，但可被当作下载跳板、消耗出网带宽、放大 SSRF 面

### P2-2　遗留 `.env` 中的明文密钥

`/root/sqlteacher/.env`（root:root，644）包含 `QWEN_API_KEY`、`BAILIAN_API_KEY`、`VITE_DEEPSEEK_API_KEY`、MySQL 口令等明文凭据。其中 `VITE_*` 前缀会被 Vite 打入前端产物，等于把 DeepSeek Key 发到浏览器侧——若该前端曾被构建分发，Key 应视同已泄露。

### P2-3　其他

- 待更新包 48 个（`apt list --upgradable` 未标注 security 通道，需人工确认）
- 生产运行版本为 `3.8.0-pre.2`（`/opt/sqlteacher/current` 软链），而 `docs/operations` 最新部署记录为 3.10.0 —— 版本漂移，建议确认 `current` 指向是否符合预期
- 证书正常：`api.sqlteacher.tech` 至 2026-12-19，`sqlteacher.tech` 至 2026-12-21，certbot.timer 正常
- 磁盘 61%（12G/20G），正常

---

## 三、入侵迹象排查：未发现失陷

| 检查项 | 结果 |
| --- | --- |
| UID 0 账户 | 仅 `root` 一个 |
| 可登录 shell 账户 | 仅 `root`（`sqlteacher` 为服务账户，nologin） |
| `authorized_keys` | 空文件（0 字节），无植入公钥 |
| `/etc/sudoers.d` | 仅 Ubuntu 自带 README |
| `/etc/passwd`、`/etc/shadow` 修改时间 | 2026-08-01（服务账户创建时间，符合预期） |
| 成功 SSH 登录 | 仅 `218.198.193.249`（本次审计来源），无陌生 IP |
| `/etc/ld.so.preload` | 空；无 `LD_PRELOAD` 劫持 |
| root crontab / `/etc/cron.d` | 干净 |
| systemd 单元 | 仅 `sqlteacher-cloud/embedding/qdrant/backup/operations-check`、`certbot`、`pm2-root`，均可解释 |
| `/tmp`、`/dev/shm` | 无可疑可执行文件（仅 pip 缓存与阿里云辅助锁文件） |
| `/usr/local/bin`、`/usr/local/sbin` 近 60 天新增 | 无 |
| 全盘 `.php` 文件（排除 node_modules） | **0 个**，无 webshell |
| Nginx 日志 | 仅扫描探测（`/.env`、`.git/config`、`wp-login.php`），全部 404 或返回 SPA，无成功利用痕迹 |
| 出站连接 | 仅 AliYunDun、hbrclient、阿里云内网 + 本次 SSH，无矿池/C2 |
| 负载 | 0.00，无异常计算占用 |
| AppArmor | 已加载，125 profiles / 30 enforce |

---

## 四、后续处置建议

按性价比排序。第 1 项已于 2026-10-22 当日执行完毕（见第六节），其余仍待确认。

1. ~~**立即下线三个遗留服务**~~（已执行）
   ~~`pm2 stop` 掉 `sqlteacher`（3001/5173）、停掉 `/root/Web`（3000），并从 `pm2-root` 的 dump 中删除，避免重启后复活。确认无用后归档 `/root/sqlteacher`、`/root/Web` 并轮换其中的 Qwen / 百炼 / DeepSeek Key 与 MySQL 口令。~~
2. **安全组收敛**：只保留 80/443/22（22 建议加源 IP 白名单），删除 3000/3001/5173 的放行。
3. **SSH 加固**：`PermitRootLogin prohibit-password`、关闭 `PasswordAuthentication`、部署密钥登录、安装并启用 fail2ban（`sshd` jail 3 次/10 分钟）。
4. **Nginx 扫描面收敛**：对 `.php`、`.env`、`.git` 等路径 `return 404`，避免 200 回退误导扫描器；`/gh/` 代理建议加 `limit_except GET` 与来源限制，或改用专用下载路径。
5. **补丁**：确认 48 个待更新包中是否有内核/OpenSSL/Nginx 相关项，按运维窗口升级。
6. **版本对齐**：核对 `/opt/sqlteacher/current` 是否应指向 3.10.0。

---

## 五、处置记录：遗留服务已下线（2026-10-22 16:15–16:20）

### 5.1 删除前置校验

删除任何文件之前先建立可还原的双份备份，并逐项核对"是否真的无用"：

| 校验项 | 结果 |
| --- | --- |
| `/root/Web` Git 远端 | `https://github.com/danlingdan/Web.git`，本机 HEAD `e645df6` 与远端 HEAD **一致**，无未推送提交、无未跟踪文件 |
| `/root/sqlteacher` Git 远端 | `https://github.com/danlingdan/sqlteacher.git`（**私有仓库**，服务器侧无法读取凭据，故远端不可校验），本地 17 个提交，`git log --branches --not --remotes` 为空（无未推送内容） |
| 非 Git 跟踪文件 | 发现 `server/kb/seed_pdfs/` 下 **14 份教材 PDF**（数据库系统概论等）未被 Git 跟踪，仅存于服务器磁盘 → 必须进备份 |
| `/etc` 中的引用 | 无任何配置引用这两个目录 |
| 启动方式 | 均挂在 `pm2-root.service`（`sqlteacher` id 24、`web` id 14），非 systemd/cron 独立单元 |

### 5.2 备份

服务器侧 `/root/legacy-backup-2026-10-22/`（254M，含 `SHA256SUMS.txt`）：

```
sqlteacher-src.tar.gz    166M   源码 + .git + 14 份未跟踪 PDF（排除 node_modules/.venv）
sqlteacher-git.bundle     83M   全部 17 个提交与 refs（git bundle verify: complete history）
web-full.tar.gz          5.3M   /root/Web 全量（含 .git、xlsx）
sqlteacher.env.bak       742B   /root/sqlteacher/.env（本地唯一改动，含明文密钥）
pm2-dump.pm2.bak / pm2-logs/
```

本地侧 `D:\Code\Teacher\.workbuddy\audit\backup\`（255M），四个文件 SHA256 **全部 MATCH**。
备份内容已在本地解包验证：`git log` 17 条、HEAD `dcc963a`、bundle `complete history`、14 份 PDF 齐全。

### 5.3 执行结果

```
pm2 stop/delete sqlteacher web  →  pm2 list 为空
pm2 save --force                →  /root/.pm2/dump.pm2 = []
端口 3000/3001/5173             →  无监听（外网实测 http=000）
进程                            →  无 vite / esbuild / node 残留
目录                            →  /root/Web、/root/sqlteacher 已删除（经隔离区复核后 rm）
附带清理                        →  pm2 日志 2.2M（已归档）、/tmp 中 60 个 8 月遗留 pip 临时目录
磁盘                            →  62% → 58%（回收约 820M）
```

生产未受影响，外网复核：`https://api.sqlteacher.tech/health` → 200（`knowledgeIndex: ready`，`apiVersion: 1.11`），`https://sqlteacher.tech/` → 200，`sqlteacher-cloud / qdrant / embedding / nginx / mysql` 均为 active。

### 5.4 仍未处理的遗留项

- **密钥轮换（建议尽快）**：`/root/sqlteacher/.env` 中的 `QWEN_API_KEY`、`BAILIAN_API_KEY`、`VITE_DEEPSEEK_API_KEY`、MySQL 口令，在公网暴露的 root 进程里明文存放约 7 个月，应视为已泄露。其中 `VITE_*` 会被 Vite 打进前端产物，风险更高。
- **MySQL 遗留库**：文件已删，但 `sqlteacher` 数据库中该原型的数据仍在，是否 `DROP` 待定（生产走 `/opt/sqlteacher/data` 的 SQLite，不依赖它）。
- **安全组**：`3000/3001/5173` 的放行规则应删除（服务已停，规则成为死规则但仍是缩小面的必要动作）。
- **pm2-root.service**：仍 enabled，但应用列表为空，可考虑 `disable`；`/root/.nvm` 与 `/root/.npm`（96M）现无人使用。
- **服务器侧备份**：确认无需回滚后删除 `/root/legacy-backup-2026-10-22/`。

---

## 六、附录：本次使用的关键只读命令

```bash
# PHP 面
which php php-fpm php-cgi frankenphp; dpkg -l | grep -i php; ls /etc/php
find / -xdev -type f \( -name php -o -name 'php-fpm*' -o -name php-cgi -o -name frankenphp \)
find / -xdev -name '*.php' -not -path '*/node_modules/*'

# 暴露面
ss -tulnp; ufw status verbose; iptables -S; ss -tnp state established

# 账户与持久化
awk -F: '$3==0' /etc/passwd; last -n 20 -w; lastb -n 15 -w; cat /root/.ssh/authorized_keys
crontab -l; ls /etc/cron.d; systemctl list-timers --all; cat /etc/ld.so.preload
ls -la /tmp /dev/shm; find /usr/local/bin /usr/local/sbin -mtime -60 -type f

# 日志
grep 'Accepted' /var/log/auth.log; zgrep -ciE '\.php' /var/log/nginx/access.log*
```

---

## 七、补丁与更新状态（2026-10-22 16:2x 核查，未安装任何更新）

### 7.1 官方口径

```
apt-check: 51 updates can be applied immediately.
           1 of these updates is a standard security update.
           3 additional security updates can be applied with ESM Apps (未启用)
```

- `apt list --upgradable`：48 个（`apt-get -s upgrade`），`dist-upgrade` 为 49 upgraded + 2 newly installed
- 那 1 个标准安全更新是 **libdrm-amdgpu1**（GPU DRM 库，无显卡的服务器基本无关）
- 其余 47 个全部来自 `noble-updates`（SRU 缺陷修复），不含 `-security` 口袋
- 包源：`mirrors.cloud.aliyuncs.com`，元数据 2026-10-21 更新，时效正常

### 7.2 结论：真正的缺口不是这 51 个包

| 缺口 | 现状 | 影响 |
| --- | --- | --- |
| **内核** | 运行 `6.8.0-90-generic`，归档已有 `6.8.0-147`（差 57 个版本）。`linux-image-generic` / `linux-generic` / `linux-generic-hwe-24.04` 的 **Installed 均为 (none)** | 只剩具体版本内核包在机器上，**没有 meta 包就不会再收到任何内核更新**，属于静默停更 |
| **CPU 微码** | `apt-mark showhold` 显示 `intel-microcode` 被 hold；已装 `3.20240312.1build1`，候选 `3.20260210.0ubuntu0.24.04.1`（属 `noble-security`） | 这是唯一被 hold 的安全口袋更新，含 2 年的 CPU 漏洞缓解 |
| **待重启** | `/var/run/reboot-required` 自 **2026-09-13** 起挂着（`libc6 2.39-0ubuntu8.8 → 8.9`）；`lsof` 统计 173 个已删除文件仍被占用，其中 mysqld 156 个、pm2 daemon 11 个 | 5 周多里进程一直跑旧版 libc 代码 |
| **cloud-init** | 同为 hold，停在 `23.2.2-8`，候选 `26.2-0ubuntu1~24.04.1`（跨约 3 年大版本） | 仅开机执行，当前无运行风险；但版本与新 netplan 组合需谨慎 |
| **ESM Apps** | 未启用（`pro status` 中 esm-apps 为 available 未 enabled） | 少 3 个 Universe/Multiverse 安全更新；Ubuntu Pro 个人版免费 |

### 7.3 已是最新的关键组件

| 组件 | 版本 | 状态 |
| --- | --- | --- |
| nginx | `1.24.0-2ubuntu7.18` | 已在待更新清单之外，最新 |
| openssl / libssl3t64 | `3.0.13-0ubuntu3.16` | 最新 |
| mysql-server | `8.0.46-0ubuntu0.24.04.4` | 最新 |
| openssh-server | `1:9.6p1-3ubuntu13.20` | 最新 |
| systemd | `255.4-1ubuntu8.18` | 最新 |
| libc6 | `2.39-0ubuntu8.9`（磁盘），内存中仍为 8.8 | 待重启 |

`unattended-upgrades` 工作正常：2026-10-22 06:34 自动安装了 `ca-certificates git git-man liblzma5 libprotobuf-lite32t64 python3-jwt tzdata tzdata-legacy xz-utils` 共 9 个，并明确记录了 `cloud-init` 与 `intel-microcode` 因 hold 而跳过。snap 无待刷新；磁盘 58%。

### 7.4 升级风险与建议顺序（未执行，待确认）

风险点：待更新清单含 `netplan.io 1.1.2-8ubuntu1~24.04.3`、`apparmor`、`snapd`、`krb5`。网络配置由 cloud-init 生成的 `/etc/netplan/50-cloud-init.yaml`（14 行，指纹 `33b10b95…`）驱动，而 cloud-init 被 hold 在旧版——**升级 netplan 后再重启存在网络配置漂移风险**，需先备份并确保阿里云 VNC 控制台可用。

建议按序执行：

1. 备份 `/etc/netplan`、`/etc/apt`、`dpkg --get-selections`、当前包版本快照
2. `apt-get update && apt-get upgrade -y`（先不动内核、不动 hold 的包）
3. 装内核 meta 包并升到 6.8.0-147：`apt-get install linux-generic`，随后 `apt-get upgrade`
4. 单独评估 `intel-microcode`：解除 hold、安装、验证
5. 重启（libc6 + 内核同时生效），重启前后对比 `netplan get` 与站点/接口可用性
6. 可选：`pro attach` 启用 ESM Apps 拿剩余 3 个安全更新；`systemctl disable pm2-root` 并清理 `/root/.nvm`

---

## 八、补丁执行记录（2026-10-22 16:29–16:35，已装包，**未重启**）

### 8.1 前置核查

| 项目 | 结果 |
| --- | --- |
| 引导方式 | UEFI：`shimx64.efi → grubx64.efi → /boot/efi/EFI/ubuntu/grub.cfg`（仅 `search.fs_uuid` + `configfile $prefix/grub.cfg` 跳转）→ `/boot/grub/grub.cfg` |
| grub 包 | `grub-efi-amd64-signed` + `grub-efi-amd64-bin` 与遗留的 `grub-pc` / `grub-pc-bin` 共存；**本次不动 grub 包**，只让其重新生成 `/boot/grub/grub.cfg`，不触碰 EFI 分区 |
| CPU | `GenuineIntel` Xeon Platinum → 应升级 `intel-microcode`（`amd64-microcode` 无关） |
| 依赖陷阱 | `linux-image-generic` 的 **Depends 含 `linux-firmware`、`intel-microcode`、`amd64-microcode`**，直接装会带入约 2GB 无线/显卡固件；改用 `--no-install-recommends` 避免再拉 `bpftrace`/`upower`/`libimobiledevice` 等 |

快照目录 `/root/upgrade-snapshot-2026-10-22/`：`netplan.bak/`、`apt.bak/`、`grub-default.bak`、`grub.cfg.before`、`dpkg-selections.txt`、`dpkg-list-before.txt`、`apt-holds.txt`、`netplan-config-before.txt`、`key-pkgs-before.txt`。

### 8.2 执行内容

升级通过 `setsid` 脱离 SSH 会话运行，避免连接中断打断 dpkg 事务；全程日志 `/root/upgrade-2026-10-22.log`；`NEEDRESTART_MODE=l` 只列出待重启服务、不自动重启业务进程；`--force-confold` 保留本地配置。

| 阶段 | 内容 | 结果 |
| --- | --- | --- |
| 1 | `apt-get update` | rc=0 |
| 2 | `apt-get upgrade`（48 个 -updates） | rc=0 |
| 3 | `apt-mark unhold intel-microcode` | 成功 |
| 4 | `apt-get install --no-install-recommends linux-image-generic linux-headers-generic` | rc=0，装入 6.8.0-147 + meta + 固件 |
| 5 | 显式 `install intel-microcode` | 3.20240312 → **3.20260210** |
| 6 | `apt-get dist-upgrade`（补依赖型） | 补 `libdrm-amdgpu1`、`fwupd 2.0` + `libfwupd3` |

### 8.3 核验结果

```
内核镜像      linux-image-6.8.0-147-generic 6.8.0-147.147  已装（运行中仍为 90）
内核 meta     linux-image-generic / linux-headers-generic  6.8.0-147.147  已装（未来可自动接收内核更新）
grub 默认项   menuentry 'Ubuntu' → vmlinuz-6.8.0-147-generic + initrd.img-6.8.0-147-generic
grub 风格     仍含 efi_gop（EFI 风格未被改坏）；147 条目正常生成
initramfs     含 microcode 条目（early-load 生效）
待重启包     apparmor / libc6 / linux-base / linux-image-6.8.0-147-generic
netplan      md5 与升级前一致（33b10b95…），netplan 运行态 diff 无变化
关键组件     nginx 7.18、openssl 3.0.13-3.16、mysql 8.0.46、openssh 13.20、systemd 255.4、libc6 8.9 —— 均未回退
服务         nginx / mysql / sqlteacher-cloud / qdrant / embedding / cron / rsyslog 全部 active
端口         仅 22/80/443 对外，其余环回；遗留 3000/3001/5173 仍为 000
dpkg --audit 无半安装或配置失败的包
磁盘         58% → 68%（linux-firmware + 新内核约 2GB）
```

外网复核：`https://api.sqlteacher.tech/health` → 200，`knowledgeIndex: ready`，`apiVersion: 1.11`；`https://sqlteacher.tech/` → 200。

### 8.4 剩余 8 个可升级项（未处理，属正常）

- `cloud-init` 23.2.2 → 26.2：**仍被 hold**（被人为锁定，且 phased 40%）
- `libglib2.0-*`（4 个）、`tmux`、`ubuntu-pro-client`（2 个）：`apt-cache policy` 显示 **phased 40%**，属 Ubuntu 分阶段灰度，会自动滚动到位，不需要强制

### 8.5 待办：重启

包已全部装好，但 `libc6`、`apparmor`、新内核、CPU 微码都需要**重启才生效**。当前已连续运行 210 天，首次重启建议单独安排窗口：

1. 重启前确认阿里云 VNC（远程连接）可用，作为 SSH 失联时的兜底
2. `systemctl reboot`，观察 3–5 分钟
3. 起来后核验：`uname -r` 应为 `6.8.0-147-generic`；`grep microcode /proc/cpuinfo` 变为新版本；`systemctl --failed` 为空；`ip -br a` 与 `netplan get` 与预期一致；`https://api.sqlteacher.tech/health` 为 200
4. 若网络未起：VNC 进系统，检查 `/etc/netplan/50-cloud-init.yaml`（快照在 `/root/upgrade-snapshot-2026-10-22/netplan.bak/`），必要时 `netplan apply`

回滚手段：`/boot/grub/grub.cfg` 与 `/etc/netplan`、`/etc/apt` 均有快照；旧内核 `6.8.0-90` 仍在 `/boot`，可在 grub 菜单选 "Advanced options" 启动。

### 8.6 重启执行结果（2026-10-22 16:38:41 重启完成）

重启前经过闸门脚本校验（无 apt/dpkg 事务、dpkg 状态干净、GRUB 默认项与 `/boot` 内核文件一一对应、服务与网络基线已记录），通过后以 `systemd-run --on-active=3` 安排延迟重启，让 SSH 连接干净退出。

| 观测项 | 结果 |
| --- | --- |
| 内核 | `6.8.0-147-generic`（与 grub 默认项一致） |
| 启动耗时 | kernel 1.607s + userspace 13.558s = **15.17s** |
| 失败单元 | `systemctl --failed` 为 **0**；`is-system-running` = `running` |
| 服务 | nginx / mysql / sqlteacher-cloud / qdrant / embedding / cron / rsyslog / ssh 全部 active |
| 网络 | `eth0 172.19.66.86/20`，默认路由 `172.19.79.253`，**与重启前完全一致**（netplan 未漂移） |
| 端口 | 仍为 22/80/443 对外 + 环回 3306/33060/6333/6334/11434/18080；hbrclient 换到环回 44331 属正常 |
| 待重启标记 | `reboot-required` 已清除 |
| 回滚入口 | grub 中 `6.8.0-90-generic` 仍保留 4 处条目，可经 "Advanced options" 回退 |
| 外网 | `/health` 200、`sqlteacher.tech` 200、遗留 3000/3001/5173 仍为 000 |

### 8.7 一个需要如实说明的发现：虚拟机内微码无法更新

重启后 `dmesg` 明确记录：

```
MDS: Vulnerable: Clear CPU buffers attempted, no microcode
MMIO Stale Data: Vulnerable: Clear CPU buffers attempted, no microcode
```

`/proc/cpuinfo` 的 `microcode` 仍为 `0x1`，未随包更新而变化。这说明：

- `intel-microcode 3.20260210` 包本身已装好、early-load 机制也已生效（initramfs 含微码条目）
- 但在**阿里云 ECS 这类虚拟化环境中，CPU 微码由宿主机管理，Guest 无法刷写**；因此这一步对宿主机侧的 MDS / MMIO Stale Data 缓解**没有实际作用**，属"装了但刷不进去"
- 真实的缓解责任在宿主机（阿里云平台侧）。如需确认，可向阿里云核实实例所在宿主机的微码版本，或选择其官方公告中已修复的实例规格

另外，本机内核命令行沿用阿里云默认的 `noibrs`，即关闭 IBRS（Spectre-v2 的硬件缓解之一）以换取性能。这是平台默认取值，本次未改动；若安全优先级高于这点性能损失，可评估移除该参数。

在此之前修好的 `libc6`（8.8→8.9）与 `apparmor` 已随重启真正生效，这两项是有实际收益的。
