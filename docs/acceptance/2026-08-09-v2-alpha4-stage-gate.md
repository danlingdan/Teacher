# SQLTeacher v2.0.0-alpha.4 阶段门禁

> 验证日期：2026-08-09
> 分支：`main`
> 版本：`2.0.0-alpha.4`
> 发布状态：本地门禁已完成；目标为 `v2.0.0-alpha.4` GitHub prerelease

## 1. 功能与 Runner 门禁

- CODE 规格、源码 artifact、Java/Python/C/C++ 内置活动和 schema 13 已贯通课程地图、实验工作区、评价、证据与诊断；
- 本地 IDE Runner 使用本机 JDK、联网 WSL Python 和 MSVC，支持标准输入、有界控制台、超时和取消；
- 安全评价使用 WSL2 systemd cgroup、user/PID/mount/network namespace、一次性 tmpfs/chroot、最小环境和只读工具链；
- Ubuntu/WSL2/systemd/隔离原语与工具链探测失败时拒绝执行，不退回宿主直跑；
- 原因码覆盖编译、运行、时间、内存、工作区/inode、进程、输出、取消和工具链/隔离不可用；
- 恶意与退化夹具覆盖宿主文件、网络、凭据、无限循环、输出洪水、内存、子进程、文件耗尽、并发与取消恢复；
- Java、Python、C、C++ 均在真实隔离 Runner 中编译运行通过。

本机环境：Windows build `26200`、WSL `2.7.10.0`、Ubuntu `26.04 LTS`、Linux kernel `6.18.33.2-microsoft-standard-WSL2`。安全 Java 工具链使用 Ubuntu 签名包 `openjdk-21-jdk-headless 21.0.11+10-1~26.04.2`；安装发生在本机 WSL，不进入 Git 或发布 ZIP。

## 2. 自动化测试

聚焦命令：

```powershell
mvn -q test "-Dtest=WslSandboxCodeRunnerTest,CodeActivityEvaluatorTest,SqliteSchemaMigratorTest,DesktopFxmlResourceTest,DesktopVisualResourceTest,WindowsPackagingContractTest,SemanticVersionTest"
```

结果：通过。

完整命令：

```powershell
mvn test
```

结果：451 项测试，0 failure，0 error，2 项按既有条件跳过。跳过项均为 `LiveAiProviderSmokeTest`，原因是未设置显式的 `sqlteacher.live.ai` 联网测试开关。

## 3. JavaFX 实机走查

使用真实 JavaFX 窗口和访客数据完成：

1. 课程地图显示 3 门课程、26 个活动和四种代码活动；
2. Python 本地运行读取 `2 3` 并输出 `5`，控制台显示退出码、状态和耗时；
3. Python 安全评价的两个固定测试全部通过；
4. 本地运行后立即取消，界面显示进程树和临时工作区已清理；
5. 运行中立即切换课程地图，旧结果没有回写新页面；
6. 窗口在项目允许的最小 `1260 × 900` 尺寸可通过滚动访问编辑器、控制台和运行按钮；
7. 重启后中英文登录页、课程地图和 CODE 工作区标签均可用，走查结束后恢复简体中文偏好；
8. 走查发现并修复首页仍显示 Alpha.2 和产品仍称“数据库教学工作台”的陈旧文案。

## 4. Windows 打包门禁

```powershell
./packaging/package-stage1.ps1
```

| 项目 | 结果 |
| --- | --- |
| Windows package version | `2.0.1004` |
| EXE | `SQLTeacher-2.0.0-alpha.4.exe` |
| ZIP | `SQLTeacher-2.0.0-alpha.4-windows-x64.zip` |
| app-image | `target/installer/SQLTeacher` |
| SHA-256 | EXE 与 ZIP 复算均匹配 `SHA256SUMS.txt` |
| SBOM | CycloneDX，44 个组件 |
| ZIP | 343 个条目；0 个 `.env`、`.secrets`、`app-data`、数据库、日志、密钥或项目 `target` 条目 |
| 陈旧版本化资产 | 0 |
| Cloud URL | launcher 包含 `https://api.sqlteacher.tech` |
| app-image | 真实启动成功，访客首页显示 Alpha.4，关闭后无遗留 SQLTeacher 进程 |

本地门禁不执行 EXE 安装；标签与公开 prerelease 在本地门禁通过后由正式发布流程完成。

## 5. 边界结论

- 本地 IDE 运行有意允许当前学生账户可用的本机文件与网络访问，非 SQL 代码风险由学生承担；
- SQLTeacher 不把 DPAPI 保存的 Cloud Token、数据库密码或 AI Key 主动注入学生进程；
- 安全评价是可选环境依赖，不随 Windows 安装包捆绑 Linux JDK；缺失时提供安装指南并稳定拒绝；
- SQL、AI、教师权限、隐私和 Cloud 契约没有因本地 IDE 定位而放宽。

实现记录见 [Stage 12](../history/stages/stage12/2026-08-09-v2-alpha4-local-ide-runner-implementation.md)。
