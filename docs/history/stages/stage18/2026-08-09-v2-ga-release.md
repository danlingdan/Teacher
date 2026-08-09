# v2.0.0 GA 发布记录

> 日期：2026-08-09
> 目标版本：`2.0.0`

GA 直接使用 RC.1 冻结的源码契约，只修改稳定版元数据、正式发布说明和发布记录。发布流程要求在 GA 提交后重新执行完整测试、性能验证与 Windows 打包，并确认 JAR `Build-Commit`、`v2.0.0` 标签和 Release 源提交一致。

GitHub Release 必须发布为 Latest 且不是 prerelease；Actions 生成的 Ed25519 签名清单必须为 `stable` 通道。生产部署必须先备份并验证 Cloud 数据库，再将版本化目录原子切换为 `2.0.0`，保持 Nginx HTTPS 和 `127.0.0.1:18080` 回环绑定。

实际发布和生产证据记录在 [v2.0.0 门禁](../../../acceptance/2026-08-09-v2.0.0-stage-gate.md)及后续生产运维记录中。
