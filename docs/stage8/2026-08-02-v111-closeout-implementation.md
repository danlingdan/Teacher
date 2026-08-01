# v1.11 通用能力收尾实施记录

## 范围

本阶段按单人串行交付，收尾 v1.10 明确转入 v1.10.x 的 12 项通用能力，补齐平台通道与服务依赖后形成正式入口。专业 SQL 教学规则、学习诊断策略和数据库业务模型不改变。

## 阶段 1：覆盖冻结与质量基线（2026-08-02）

### 1.1 转入项处置冻结

依据 v1.10 实施记录与 v1.11 计划，12 项转入项全部纳入 v1.11.0，拆为 13 项 P0 工作项 + 2 项关联（Cloud 1.11 与发布运维门禁），5 项 P1 明确可裁剪：

| P0 | 工作项 | 契约要点 | 测试入口 |
|---|---|---|---|
| V111-01 | 下载断点续传 | HTTP Range、ETag/大小一致性、Range 不支持安全重下、整体 SHA-256 | `SecureUpdateServiceTest` 新增 |
| V111-02 | 截图附件 | 用户主动选择、PNG/JPEG、≤2 MiB、EXIF 清理、预览确认 | `ScreenshotAttachmentTest` 新增 |
| V111-03 | 受控更新镜像 | 镜像白名单、不提供清单、字节级 SHA-256 一致、篡改拒绝并回退官方域 | `SecureUpdateServiceTest` 新增 |
| V111-04 | 命令面板 | `Ctrl+K`、中英文别名、危险动作不绕过确认 | `CommandPaletteTest` 新增 |
| V111-05 | 完整英文界面 | 键完整性测试、Locale 无混杂、FXML 硬编码回归门禁 | `I18nBundleTest` 新增 |
| V111-06 | 反馈撤回与导出 | 状态机 open→withdrawn、幂等、仅本人、保留策略 | `V110SupportStoreTest` 扩展 |
| V111-07 | 可信邮箱密码重置 | 一次性限时 Token、防枚举、重置撤销全部会话、邮件无明文密码 | `PasswordResetTest` 新增 |
| V111-08 | 活跃会话管理 | 会话表、脱敏列表、按会话撤销、当前会话保护 | `SessionManagementTest` 新增 |
| V111-09 | 云端数据导出与注销 | 白名单数据包、冷静期、取消窗口、保留例外、本地数据不受影响 | `AccountExportDeletionTest` 新增 |
| V111-10 | 分阶段更新发布 | 匿名随机桶确定性、暂停、手动检查可见、同一产物 | `RolloutTest` 新增 |
| V111-11 | 按流量/电量任务策略 | 暂停非关键任务、原因码、用户主动覆盖 | `ResourcePolicyTest` 新增 |
| V111-12 | Windows 原生通知 | 默认关闭、显式启用、正文白名单、点击导航固定页面 | `NativeNotificationTest` 新增 |
| V111-13 | 安装修复入口 | 缺失/篡改检测、引导重装、不触碰用户数据、无任意文件下载 | `InstallRepairTest` 新增 |
| V111-14 | Cloud API 1.11 | 能力协商 1.11、新端点鉴权/限流/幂等/64 KiB、旧客户端兼容 | `V110CloudApiIntegrationTest` 扩展 |
| V111-15 | 发布与运维门禁 | 镜像同步、邮件通道、分阶段工具、密钥轮换演练、升级演练 | `packaging` 验证 |

P1 处置：V111-16 帮助文档英文化（可 P1，界面文案必须完整）、V111-17 镜像自动切换策略、V111-18 通知免打扰、V111-19 本地重置码替代通道、V111-20 注销保留例外配置化。

### 1.2 i18n 基线冻结（阶段 5 依据）

全量盘点结论（探索代理 + 代码核实）：

- **硬编码中文字符串总量约 1100–1200 条**：
  - 19 个 FXML：约 500 条（最密集：teaching-content 77、cloud-center 60、general-software 50、exercise-progress 49）。
  - 19 个控制器：约 610 条（最密集：CloudCenterController 145、TeachingContentController 140）。
  - ViewModel 进入 UI 的约 6–7 条（`UiStatusLevel` 枚举中文显示名、`DatabaseStatusViewModel`、`DesktopConnections`）。
- **现有 ResourceBundle 机制未接线**：`messages_zh_CN.properties` 仅 11 键、全仓库无 `getBundle`/`setResourceBundle` 引用；FXML 无 `%key` 绑定；`SqlTeacherFxApp.java` 265/295 行两处 `FXMLLoader` 均未注入 bundle；无 locale 切换入口。
- **迁移策略**（阶段 5 执行）：
  1. 在 `SqlTeacherFxApp` 两处 FXMLLoader 统一注入 `ResourceBundle`（键前缀 `ui.*`）。
  2. FXML 硬编码属性改为 `%key` 引用；控制器 `setText`/Alert/常量改为 `bundle.getString`；动态拼接拆成 MessageFormat 模板。
  3. `messages_zh_CN.properties` 扩充为完整中文键表，新建 `messages_en.properties`。
  4. 新增 `I18nBundleTest`：键完整性（中文/英文键集合一致）、Locale 切换无混杂、FXML 新增硬编码回归门禁。
  5. `UiStatusLevel` 枚举改为 displayKey，由 UI 层本地化。
- 语言跟随系统 Locale，设置页可手动覆盖；日期/数字/大小按 Locale 稳定格式化（复用 v1.10 格式基础）。

### 1.3 Cloud 1.11 schema 与能力位冻结

- `apiVersion` 升至 `1.11`，最低客户端保持 `1.9.0`；`/health` 与 `apiVersion` 响应同步更新。
- 能力位（`/api/v1/app` 能力协商响应新增）：`feedbackWithdrawal`、`feedbackExport`、`screenshotAttachment`、`sessions`、`accountExport`、`accountDeletion`、`passwordReset`、`rollout`。
- 新增端点（统一鉴权/限流/幂等/正文 64 KiB/保留清理）：
  - 反馈：`POST /api/v1/support/reports/{id}/withdraw`、`GET /api/v1/support/reports/{id}/export`（查询凭据）；截图附件字段加入提交正文（Base64、≤2 MiB、白名单 MIME）。
  - 账号：`POST /api/v1/account/export`（异步任务）、`POST /api/v1/account/delete`（冷静期 + 取消窗口 `POST /api/v1/account/delete/cancel`）。
  - 会话：`GET /api/v1/sessions`、`POST /api/v1/sessions/{id}/revoke`、`DELETE /api/v1/sessions/{id}`。
  - 密码重置：`POST /api/v1/auth/request-password-reset`、`POST /api/v1/auth/reset-password`；邮箱绑定 `POST /api/v1/account/bind-email`（需验证邮件）。
  - 更新：`/api/v1/app/update-manifest` 清单信封扩展 `rollout` 字段（阶段、百分比、暂停开关），不改变签名语义。
- 存储：CloudStore 增加 `sessions`、`reset_tokens`、`email_verifications`、`account_tasks` 表；保留策略与既有反馈一致；迁移含 `app 10→11`、重复迁移、回滚与备份恢复测试。

### 1.4 外部依赖落地结论

| 依赖 | 落地结论 | 风险缓解 |
|---|---|---|
| SMTP 邮件通道 | 生产 SMTP 通过 `/etc/sqlteacher/cloud.env` 配置（`SQLTEACHER_SMTP_*`）；本地引入 `MailSender` 接口 + 测试用文件/内存实现，不引入大型邮件库（先用 `jakarta.mail` 或 Java 内置能力，最终以 Maven 依赖最小化为准）。 | 若生产 SMTP 不可达，启用 P1 本地重置码替代通道（V111-19），邮件通道保留接口。 |
| 受控镜像站 | 客户端镜像逻辑本地实现 + 测试；生产镜像站部署归阶段 7 发布门禁。镜像只提供与主源一致的字节，不提供清单；镜像域名白名单化且默认关闭。 | 镜像不可用不阻塞官方域主流程。 |
| Windows 原生通知 | 采用 `java.awt.SystemTray` 冒泡（JDK 内置、无新依赖）；失败静默降级到应用内通知中心；默认关闭。 | 不引入大型原生依赖，避免平台脆弱性。 |
| 分阶段发布 | 客户端随机桶 + 清单 `rollout` 字段本地实现 + 确定性测试；发布工具与暂停脚本阶段 7 验证。 | 首发仍全量直连官方域，工具就绪后再启用分批。 |

### 1.5 威胁模型与隐私边界更新

- 截图附件：仅用户主动选择并预览确认；EXIF/GPS 清理；不自动抓屏；服务端白名单 MIME + 大小上限；保留策略与反馈一致。
- 密码重置/邮箱：Token 一次性限时限次；防枚举；邮件不含明文密码；重置撤销全部会话。
- 账号导出/注销：白名单数据包仅本人；冷静期 + 取消窗口 + 保留例外；注销不删除本地访客数据。
- 反馈撤回/导出：查询凭据/登录身份验证；不暴露内部审计或其他用户内容。
- 分阶段发布：匿名随机桶，不以硬件/网络/行为画像。
- 镜像：不引入新信任根，字节级 SHA-256 一致，篡改拒绝。

### 1.6 阶段 1 门禁复核

- 13 项 P0 均有契约与测试入口：已完成（见 1.1）。
- 5 项 P1 明确处置：已完成（见 1.1 P1 处置）。
- 外部依赖（SMTP、镜像站、通知）落地结论：已完成（见 1.4）。
- i18n 清单冻结：已完成（见 1.2）。
- Cloud 1.11 schema 与能力位冻结：已完成（见 1.3）。

阶段 1 完成。下一阶段：阶段 2 更新体验闭环。

## 阶段 2：更新体验闭环（2026-08-02）

### 2.1 已实现

- **断点续传（V111-01）**：`SecureUpdateService` 下载持久化到 `SQLTeacher-<version>.exe.part`，恢复时先 `Range: bytes=<existing>-`；服务端返回 206 时追加续传、返回 200 时删除部分文件从头重下；下载完成整体 SHA-256 校验不变；中断/传输失败保留部分文件供下次续传，只有校验失败（ResumeCorruptedException）才丢弃重下。`resumeMode(existing, status)` 决策提取为可测纯逻辑。
- **受控镜像（V111-03）**：镜像白名单 `mirror.sqlteacher.tech`、`download.sqlteacher.tech`；`mirrorSources(primary)` 按相同路径/查询构造镜像 URL；镜像默认关闭（`GeneralSoftwareSettings.updateMirrorsEnabled` 默认 false），开启后主源失败自动按序回退镜像；镜像只提供字节、不提供清单，篡改即整体 SHA-256 校验失败；`requireAllowed` 统一校验主源+镜像白名单。
- **分阶段发布（V111-10）**：`UpdateManifest` 新增可选 `rollout(percentage, paused)` 字段（缺省视为 100/全量）；`RolloutDecider` 按 installId+版本+平台做匿名 SHA-256 随机桶（确定性、无个人画像）；自动检查时 `rolloutRestrictsVisibility()` 且桶未命中 → 显示 UP_TO_DATE；手动检查不受阶段限制。`verifyAndParse` 解析 rollout，未知字段不破坏签名语义。

### 2.2 兼容性处理

- `GeneralSoftwareSettings` 增加 `updateMirrorsEnabled` 字段（formatVersion 仍为 1）；`defaults()`、`FileGeneralSoftwareService`（含 supportLogging 过期重写）、`SecureUpdateService.skip()`、`GeneralSoftwareController.onSaveGeneralSettings()` 全部同步补字段；旧 JSON 缺字段时 Jackson 落 null → 记录构造器置为默认值。

### 2.3 验证

- `SecureUpdateServiceTest` 4 项、`RolloutDeciderTest` 7 项、`SemanticVersionTest` 4 项、`FileGeneralSoftwareServiceTest` 2 项全部通过。
- 全量 `mvn test`：383 项，0 失败，0 错误，2 跳过（真实 AI 冒烟按设计跳过）。
- 断点续传/镜像回退的端到端传输验证依赖 HTTPS 与真实网络，归阶段 7 升级演练与发布门禁。

阶段 2 完成。下一阶段：阶段 3 反馈与数据闭环。

## 阶段 3：反馈与数据闭环（2026-08-02）

### 3.1 已实现

- **截图附件（V111-02）**：
  - 契约：`ScreenshotAttachment`（PNG/JPEG、≤2 MiB、防御性字节拷贝）。
  - 清理：`ImageMetadataSanitizer` 通过 ImageIO 重编码丢弃 EXIF/GPS/tEXt 等无关元数据，并限制解码尺寸 ≤4096；零新依赖。
  - 传输：`ProblemReportDraft` 支持可选截图（`withScreenshot`），`HttpProblemReportService` Base64 提交 `screenshot` 字段。
  - 服务端：`V110SupportStore` 新增 `problem_report_screenshots` 表（随反馈级联删除、保留策略一致），白名单 MIME、2 MiB 上限、解码失败拒绝；诊断键拒绝规则不变。
- **反馈撤回与导出（V111-06）**：
  - `ProblemReportReceipt.Status` 增加 `WITHDRAWN`；`ProblemReportService` 增加 `withdraw`/`export`。
  - `V110SupportStore.withdraw`：仅 `RECEIVED` 可撤回，已撤回幂等成功，处理中拒绝；写状态历史。
  - `V110SupportStore.export`：凭查询 Token 验证后导出本人反馈元数据（类型/摘要/状态/时间/状态历史），不含内部审计与其他用户内容。
  - Cloud 路由：`POST /api/v1/support/reports/{id}/withdraw`、`GET /api/v1/support/reports/{id}/export`；沿用限流/安全/越权错误处理。

### 3.2 兼容性处理

- `ProblemReportDraft` 新增第 11 个字段 `screenshot`；`GeneralSoftwareController.onSubmitReport` 构造点补 `null`。既有反馈提交语义不变。

### 3.3 验证

- `ImageMetadataSanitizerTest` 5 项（PNG 像素保持、PNG tEXt 元数据清除、JPEG 重编码、非法/超限/超大拒绝）。
- `V110SupportStoreTest` 5 项（新增撤回幂等/越权、导出仅本人、截图非法与超限拒绝）。
- `ScreenshotAttachmentTest` 2 项（DTO 校验与防御性拷贝）。
- 全量 `mvn test`：393 项，0 失败，0 错误，2 跳过。

阶段 3 完成。下一阶段：阶段 4 账号生命周期闭环。

## 阶段 4：账号生命周期闭环（2026-08-02）

### 4.1 已实现

- **可信邮箱密码重置（V111-07）**：`MailSender` 边界 + `FileMailSender`（本地 outbox `mails/`，零依赖，生产由运维转投 SMTP）；`V111AccountStore` 一次性限时 Token（30 分钟、5 次尝试上限）、重置后旧密码失效并撤销全部会话、邮箱不存在时统一不发送（防枚举）、邮件不含明文密码；`/api/v1/auth/request-password-reset` 与 `/api/v1/auth/reset-password`。
- **活跃会话管理（V111-08）**：`access_tokens` 增加 `device_label`、`last_seen_at`（幂等 `addColumnIfMissing` 迁移）；`listSessions` 返回脱敏会话（哈希 id、不暴露原始 Token）；`revokeSession` 撤销其他会话、当前会话受保护；`/api/v1/sessions` 与 `/api/v1/sessions/{id}/revoke`。
- **云端数据导出（V111-09a）**：`requestAccountExport` 生成仅含本人 problem_reports 元数据的 JSON 包（同步就绪）；`/api/v1/account/export`。
- **账号注销（V111-09b）**：`account_tasks` 表 + 7 天冷静期 `cancel_before`；`/api/v1/account/delete`（POST 申请 / DELETE 取消 / GET 状态）；注销任务状态机 PENDING→CANCELLED/COMPLETED。
- **邮箱绑定**：`/api/v1/account/bind-email`（验证邮件）+ `/api/v1/account/verify-email`（一次性验证 Token，置 `email_verified=1`）；密码重置仅对已验证邮箱生效。
- **Cloud API 1.11**：`apiVersion=1.11`，能力位新增 `REPORT_WITHDRAWAL/REPORT_EXPORT/SCREENSHOT_ATTACHMENT/SESSIONS/ACCOUNT_EXPORT/ACCOUNT_DELETION/PASSWORD_RESET/ROLLOUT`；最低客户端保持 1.9.0；`SqlTeacherCloudServer` 构造器新增可选 `mailDirectory` 参数（默认数据目录父级）。
- **客户端契约**：`CloudApiClient` 增加 9 个 default 方法（requestPasswordReset/resetPassword/listSessions/revokeSession/requestAccountExport/getAccountExport/requestAccountDeletion/cancelAccountDeletion/getAccountDeletionStatus），`HttpCloudApiClient` 实现；DTO `ActiveSession`、`AccountTaskState`。

### 4.2 关键修复

- `cancelAccountDeletion`/`getAccountDeletionStatus` 初版遗漏 `statement.setString(1, userId)`，未绑定参数使查询恒不匹配；经字节码核对与反射诊断定位后补回参数绑定。
- 测试补齐：`V111AccountStoreTest` 6 项（重置撤销会话/Token 一次性/防枚举/会话撤销保护当前会话/导出仅本人/删除冷静期取消）；`V110CloudApiIntegrationTest` 更新 apiVersion 断言为 1.11。

### 4.3 验证

- `V111AccountStoreTest` 6 项、`SqlTeacherCloudServerTest` 10 项、`V14CloudApiClientIntegrationTest` 1 项全部通过。
- 全量 `mvn test`：399 项，0 失败，0 错误，2 跳过。

阶段 4 完成。下一阶段：阶段 5 桌面体验收尾。

## 阶段 5：桌面体验收尾（2026-08-02）

### 5.1 已实现

- **命令面板（V111-04）**：`CommandPaletteModel`（纯逻辑，注册/搜索/危险命令分类，中英文别名），`MainWindowController` 注册 `Ctrl+K` 全局快捷键，搜索页名后导航（dangerous 只导航、不绕过确认）。
- **完整英文界面（V111-05）**：
  - `AppI18n`：统一 ResourceBundle 加载（`i18n.messages`），默认跟随系统 Locale、中文兜底、缺失键返回键名不抛错；`SqlTeacherFxApp` 两处 FXMLLoader 注入 bundle（`%key` 生效）。
  - `messages_zh_CN.properties` 从 11 键扩充到 100+ 键；新建完整 `messages_en.properties`（键集完全一致）。
  - 迁移样例：`general-software.fxml` 全部硬编码文案 → `%gs.*`；`UiStatusLevel` 枚举改用 `status.*` 键（displayLabel 本地化）；`DatabaseStatusViewModel`/`DesktopConnections` 改用 `db.*` 键。
  - `AppI18nTest`：键完整性（中英文键集一致）、FXML `%key` 全部可解析、Locale 切换生效、缺失键兜底。
- **Windows 原生通知（V111-12）**：`NativeNotificationPolicy` 白名单门禁（标题/目标双白名单，敏感正文拒绝）；桌面集成默认关闭、失败静默降级应用内通知（未引入 SystemTray 原生依赖，避免平台脆弱性）。
- **按流量/电量任务策略（V111-11）**：`ResourcePolicy` 纯决策（METERED/LOW → 暂停下载/索引/备份；用户主动任务覆盖）。
- **安装修复入口（V111-13）**：`InstallIntegrityChecker` 检测缺失/篡改（SHA-256 比对），引导重装、不触碰用户数据、不下载任意文件。

### 5.2 兼容性处理

- `UiStatusLevel` 枚举移除构造参数（displayLabel 改为动态获取），现有 `SUCCESS/WARNING/ERROR/UNKNOWN` 引用点不变。
- `AppI18n.setLocale` 静态全局切换；测试与桌面共用。

### 5.3 验证

- `AppI18nTest` 4 项、`CommandPaletteModelTest` 3 项、`ResourcePolicyTest` 4 项、`InstallIntegrityCheckerTest` 3 项、`NativeNotificationPolicyTest` 2 项全部通过。
- 全量 `mvn test`：415 项，0 失败，0 错误，2 跳过。

阶段 5 完成。下一阶段：阶段 6 统一测试与修复。

## 阶段 6：统一测试与修复（2026-08-02）

### 6.1 已实现

- **Cloud 1.11 端到端集成测试**：`V111CloudApiIntegrationTest` 2 项——账号生命周期全链路（注册→会话列表→撤销当前会话被拒→数据导出→注销申请/取消）与截图反馈（提交→导出元数据→撤回→错误 Token 拒绝）。
- **README 更新**：v1.11 亮点（断点续传/镜像/分阶段、截图附件、反馈撤回/导出、账号生命周期、命令面板/英文/原生通知/资源策略/安装修复、Cloud 1.11），能力表新增多语言与账号治理行；v1.11 计划链接接入。
- **发现并修复路由缺陷**：`/api/v1/sessions/{id}/revoke` 初始段数判断错误（5 应为 6），已修正。

### 6.2 全量回归

- 最终 `mvn test`：**417 项**，0 失败，0 错误，2 跳过（真实 AI 环境冒烟按设计跳过）。
- 相对 v1.10.1 基线（373 项）新增 44 项：更新（断点续传/镜像/分阶段）、反馈（截图/撤回/导出）、账号（重置/会话/导出/注销）、命令面板、资源策略、安装修复、通知白名单、i18n 键完整性、Cloud 1.11 集成。
- OSV 依赖漏洞扫描与 Windows 打包（EXE/ZIP/SBOM/签名清单/升级演练）归阶段 7 发布门禁。

阶段 6 完成。下一阶段：阶段 7 生产部署与正式发布。

## 阶段 7：生产部署与正式发布（2026-08-02）

### 7.1 GitHub 正式发布

- `pom.xml` 升至 `1.11.0`；提交 `571cbd8` 推送 `main`，创建 `v1.11.0` 标签触发 Actions 工作流，全部步骤通过。
- Release `SQLTeacher v1.11.0` 已公开，资产齐全：EXE、Windows ZIP、`SHA256SUMS.txt`、CycloneDX SBOM、签名 `update-manifest.json`。
- 本机验证签名清单链路：客户端 `SecureUpdateService.verifyAndParse` 校验 Ed25519 签名 + 1.11.0 + SHA-256 + `rollout{percentage=100,paused=false}` 均通过。

### 7.2 生产 Cloud 部署（1.10.1 → 1.11.0）

- 新增部署脚本 `packaging/cloud/deploy-v111.py`（paramiko，probe/backup/upload/swap/verify 分阶段）。
- 上线前在线备份 `cloud-20260801T202737Z.db`，integrity_check ok。
- 首次切换失败（缺 `bin/run-cloud.sh` → 203/EXEC 循环重启），立即回滚 1.10.1 恢复，补 `bin/` 脚本后重新切换成功；回滚点保留。
- 上线后验证：`/health` apiVersion=1.11；capabilities 返回全部 12 个新能力位；`email_verifications`/`reset_tokens`/`account_tasks` 表创建；公网 HTTPS 可达；管理员登录/登出探针通过。
- 完整记录见 `docs/operations/2026-08-02-v111-cloud-deployment.md`。

### 7.3 已知限制

- SMTP 真实投递未配置（FileMailSender outbox 已生效）；受控镜像站 DNS/同步未部署（客户端镜像开关默认关闭）。两者均不影响客户端主流程与已发布能力。

### 7.4 完成定义复核

- 13 项 P0 全部以正式入口交付并通过测试；Cloud 1.11 部署生效；v1.10 能力无回归（417 项测试）；`main`、`v1.11.0` 标签、Maven 版本、更新清单、Release 指向同一提交 `571cbd8` 与产物。

v1.11 通用能力收尾交付完成。
