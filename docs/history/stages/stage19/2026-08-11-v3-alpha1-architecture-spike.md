# v3.0 Alpha.1 架构与性能验证实施记录

> 实施日期：2026-08-11
>
> 代码版本：`3.0.0-alpha.1`
>
> 对应计划：[v3.0 UI 迁移与性能重构计划](../../../plans/2026-08-11-v3-ui-migration-and-performance-plan.md)
>
> 状态：技术路线纵向切片已运行，完整 Alpha.1 安装兼容门禁仍在收集

## 已实现纵向切片

- Maven 编译目标、CI 和 Windows JavaFX 打包基线升级到 Java 25；2.3 的 Java 21 全量测试在修改前保存对照，Java 25 上重新完成同一测试集。
- `LocalAppHost` 通过 UTF-8 逐行 JSON IPC 暴露白名单用例，合同版本为 `3.0-alpha.1`；请求具有 ID、最大载荷、并发上限、结构化错误、事件和取消。
- Java 核心按需初始化 Spring 与 SQLite。健康检查不等待核心装配；首页首次请求读取现有确定性学习队列，不在 Web UI 中复制诊断规则。
- Tauri 2/Rust 只承担窗口、资源和 Sidecar 生命周期。Rust 再次校验方法白名单和合同版本，超时后返回结构化失败，窗口关闭时先请求正常关闭再强制回收。
- React 壳建立统一设计令牌与“今天、课程与知识、练习与实验”三个技术验证入口。浏览器预览明确拒绝伪造本地数据。
- 知识样本覆盖 frontmatter、嵌套 Callout、行内/块公式、Wiki 链接、嵌入、Mermaid 和 Dataview 降级。原始 HTML 与外部资源默认禁用，KaTeX 和 Mermaid 均使用受限配置。
- Monaco 完全使用本地资源，按路由懒加载，只注册 SQL 和 Java 语言以及确定性 SQL 补全示例。
- `packaging/build-v3-sidecar.ps1` 生成 Java 25 jlink 运行时与 Sidecar 资源；Tauri NSIS 使用 WebView2 下载引导模式生成真实安装包。

## 验证证据

| 验证 | 结果 |
| --- | --- |
| 修改前 Java 21 `mvn test` | 504 项，0 失败，0 错误，2 跳过；43.610 s |
| Java 25 最终 `mvn test` | 509 项，0 失败，0 错误，2 跳过；43.520 s |
| Sidecar 聚焦测试 | `LocalAppProtocolServerTest,DefaultLocalAppApiTest` 通过 |
| TypeScript 测试 | 2 个测试文件、4 项测试全部通过 |
| 前端依赖审计 | `npm audit --audit-level=moderate`，0 个已知漏洞 |
| React/Vite 构建 | 通过；首页、知识与 Monaco 分包 |
| Rust | `cargo fmt` 与 `cargo check` 通过 |
| Sidecar 真实调用 | 健康、guest 首页摘要、知识样本和关闭请求均返回结构化成功 |
| Tauri/NSIS | `SQLTeacher 3 Alpha_3.0.0-alpha.1_x64-setup.exe` 成功生成 |
| 桌面走查 | 首页真实摘要、知识兼容样本、SQL/Java Monaco 高亮均在 WebView2 中运行 |
| 子进程回收 | 关闭 Tauri 窗口后无 `LocalAppHost` Java 进程残留 |

本机首次自动化原型数据由 `ui-web/scripts/measure-alpha1.ps1` 生成到忽略的
`target/v3-alpha1-performance.json`：

| 指标 | 结果 | 初始预算 |
| --- | ---: | ---: |
| Sidecar 启动到健康 | 326.43 ms | 冷启动分段观测 |
| 无 I/O IPC p95 | 0.51 ms | 不高于 20 ms |
| 首次真实首页摘要 | 2,591.49 ms | 计入冷启动不高于 4.0 s |
| 知识样本 IPC | 0.83 ms | 页面渲染另行采集 |
| Java 工作集 | 196.20 MB | 总空闲工作集预警线 350 MB |
| 正常退出 | 322.44 ms | 不高于 2.0 s |
| NSIS 安装包 | 202.14 MB；SHA-256 `930237AA14DB9D430C4720CAC8FF469008C072B9AE4881E75AF221CC2332ED2E` | 不超过 2.3 约 203 MB 的 120% |

以上为当前开发机单次技术尖峰结果，不替代计划要求的 3 次预热、10 次正式测量、p50/p95、标准差和同机 2.3 完整对照。IPC 脚本已经执行对应的 3 次预热和 10 次正式测量；启动、渲染、内存仍需扩充采样。

## 安全边界

- WebView 不获得 JDBC、Spring Bean、任意进程或任意文件能力；Tauri Capability 只启用 `core:default`。
- SQL 执行未迁入 Web/Rust，本切片没有新增 SQL 执行 IPC，因此现有风险分析、确认门、超时、结果上限和审计路径不变。
- Markdown 不执行原始 HTML、Dataview、外部资源或第三方插件；Mermaid 使用 strict 安全级别并限制文本规模。
- Java 日志写入 stderr 和独立滚动文件，stdout 只允许 IPC JSON。
- npm 通过 override 使用不受已知 DOMPurify 公告影响的版本，依赖审计为 0。

## 尚未关闭的 Alpha.1 门禁

以下证据仍需在发布候选冻结前完成，当前实现不得标记为 Alpha.1 正式通过：

1. 在干净 Windows 10/11 虚拟机完成 NSIS 安装、2.3 覆盖升级、卸载和失败回退。
2. 分别验证 WebView2 `downloadBootstrapper`、`offlineInstaller` 与固定运行时；当前包只证明在线引导配置可构建。
3. 使用固定参考机完成 2.3 与 3.0 的冷/温启动各 10 次、知识渲染、Monaco 1 MB、输入 Long Task、总工作集与 2 小时稳态报告。
4. 发布构建改用并固定 Temurin 25 补丁版本；本地兼容测试当前使用 Oracle JDK 25.0.3，CI 已配置 Temurin 25。
5. 生成 Tauri/npm/Cargo 与 Java 合并 SBOM、许可证清单和 Sidecar 资源哈希；现有正式发布工作流仍只覆盖 JavaFX 2.x 资产。

在上述门禁关闭前，2.3 JavaFX 仍是正式默认入口，3.0 Alpha 不进入稳定更新通道或生产部署。
