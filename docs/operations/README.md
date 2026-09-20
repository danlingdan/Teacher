# 运维记录索引

本目录记录生产环境实际操作和门禁结果。长期部署方法见 [云端服务部署指南](../guide/12-cloud-service-deployment.md)；这里的日期型文件用于追溯当次操作，不应直接当作最新操作手册。

- [v3.8.0-pre.1 云端服务端部署记录](2026-09-21-v3.8.0-pre.1-cloud-deployment.md)（未发布预览：服务端 3.4.3→3.8.0-pre.1 补齐 v3.5–v3.8 跨度，云端迁移 10/11、验证码化账号闭环、教师升级码；SMTP 未配置暂走文件 outbox）
- [v3.7.0 发布与更新清单部署记录](2026-09-20-v3.7.0-release-deployment.md)（CI 全流程发布：LEG-15 回执绑定 tag 父提交，CI 签名清单原子替换并公网验证 3.7.0）
- [v3.6.0 发布与更新清单部署记录](2026-09-18-v3.6.0-release-deployment.md)（用户指示本地构建发布、CI 跳过；本地签名清单原子替换并公网验证 3.6.0）
- [v3.5.4 发布与更新清单部署记录](2026-09-18-v3.5.4-release-deployment.md)（GitHub 上传通道故障改本地签名发布；清单原子替换并公网验证 3.5.4）
- [更新下载中继（/gh/ 反代）部署记录](2026-09-18-update-download-relay.md)（api.sqlteacher.tech 兼任 GitHub Releases 中继，客户端 GitHub 直链失败自动回退；公网全量下载与 SHA-256 验证通过）
- [v3.5.3 发布与更新清单部署记录](2026-09-17-v3.5.3-release-deployment.md)（双官方知识库包随安装包分发，CI 签名清单原子替换并公网验证）
- [v3.5.2 发布与更新清单部署记录](2026-09-16-v3.5.2-release-deployment.md)（服务端 jar 无变更，CI 签名清单原子替换并公网验证；存量客户端需手动下载）
- [v3.5.1 发布与更新清单部署记录](2026-09-16-v3.5.1-release-deployment.md)（服务端 jar 无变更，CI 签名清单原子替换并公网验证）
- [v3.4.4 云端知识库 1.0.1 更新记录](2026-09-16-v3.4.4-knowledge-bundle-1.0.1-cloud-update.md)（服务端 jar 无变更，公网字节级验证通过）
- [v3.4.3 云端部署记录（官方知识库分发端点上线）](2026-09-15-v3.4.3-cloud-deployment.md)
- [v3.4.2 云端部署与更新清单发布记录](2026-09-15-v3.4.2-cloud-deployment.md)
- [v3.4.0 云端部署与更新清单发布记录](2026-09-15-v3.4.0-cloud-deployment.md)
- [v3.3.4 云端部署与更新清单发布记录](2026-09-14-v3.3.4-cloud-deployment.md)
- [v3.3.3 云端部署与更新清单发布记录](2026-09-13-v3.3.3-cloud-deployment.md)
- [v3.2.0 云端部署与更新清单发布记录](2026-09-09-v3.2.0-cloud-deployment.md)
- [v3.1.0 云端部署与更新清单发布记录](2026-09-08-v3.1.0-cloud-deployment.md)
- [v3.0.0 云端部署与更新清单发布记录](2026-09-05-v3.0.0-cloud-deployment.md)
- [v2.3.0 云端部署记录](2026-08-11-v2.3.0-cloud-deployment.md)
- [v2.2.0 云端部署记录](2026-08-09-v2.2.0-cloud-deployment.md)
- [v2.0.0 云端部署记录](2026-08-09-v2.0.0-cloud-deployment.md)
- [v2.0.0-alpha.7 云端部署记录](2026-08-09-v2-alpha7-cloud-deployment.md)
- [v1.11 生产云服务部署记录](2026-08-02-v111-cloud-deployment.md)
- [v1.8.5 生产云服务部署记录](2026-08-01-v185-cloud-deployment.md)
- [Qdrant 生产部署记录](2026-08-01-qdrant-deployment.md)
- [证书与运维门禁记录](2026-07-28-certificate-and-operations-gate.md)

生产环境操作仍应遵循“先只读检查，再做最小变更，逐步验证”的顺序。

返回 [文档中心](../README.md)。
