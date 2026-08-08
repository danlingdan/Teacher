# v2.0 alpha.4 代码 Runner 威胁模型与实施 RFC

> 日期：2026-08-09
> 状态：实施中，尚未通过 alpha.4 完整退出门禁
> 当前代码版本：`2.0.0-alpha.3`；完成全部门禁后才切换 alpha.4 版本和发布材料

## 1. 目标与非目标

alpha.4 将 SQLTeacher 2.0 明确定义为**本地优先的计算机专业学习 IDE**，为 `CODE` 活动建立
Java、Python、C、C++ 规格、源码 artifact、编辑器、标准输入、控制台、工具链运行与确定性评价。
运行路径分为：

1. **本地 IDE 运行（主路径）**：直接使用学生的 JDK、WSL Python、MSVC 和终端，继承当前账户的
   文件、环境变量、网络和设备权限；学生对自己运行的代码负责。
2. **安全评价（可选路径）**：课程需要受限、可复现证据时显式调用隔离 Runner。

任何路径都不允许课程内容、AI、教师身份或导入脚本静默执行代码。Java `SecurityManager` 不作为
隔离手段。本地 IDE 运行明确不是安全沙箱；安全评价也不宣称能够对抗 WSL/Linux 内核漏洞或工具链漏洞。

## 2. 两种运行路径的责任边界

本地 IDE 模式有意放宽隔离：学生程序可以访问公网、局域网、用户目录、项目文件、环境变量和本地服务。
SQLTeacher 负责让权限状态持续可见、提供取消和有界控制台，且不由 AI/课程内容自动点击运行；学生负责审查
并运行自己的代码。应用仍不主动把 DPAPI 保存的 Cloud Token、数据库密码或 AI Key 注入学生进程。

安全评价用于教师指定测试、恶意代码或可复现证据，继续执行以下 enforcement：

| 威胁 | 安全评价 enforcement | 当前验证 |
| --- | --- | --- |
| 读取宿主文件/应用数据 | 一次性 tmpfs 根、只读工具链 bind、`chroot`；不挂载 `/mnt` | Python 读取 `/mnt/c/Windows/win.ini` 失败 |
| 凭据和代理继承 | Windows 子进程最小环境；学生进程 `env -i`，只保留固定 PATH/locale/tmp | USERPROFILE、APPDATA、Token 和代理变量夹具不可见 |
| 公网/局域网访问 | 每次运行建立独立 network namespace，默认无接口 | 连接 `1.1.1.1:53` 夹具失败 |
| 无限循环 | systemd `RuntimeMaxSec` + `timeout` + CPU RLIMIT | 无限循环稳定返回时间限制 |
| 内存膨胀 | systemd cgroup `MemoryMax`/`MemorySwapMax=0` + 地址空间 RLIMIT | 连续分配夹具被终止，后续运行恢复 |
| 输出洪水 | Java 双流并发有界读取，超限即停止 unit 和进程树 | 4 KiB 输出上限夹具通过 |
| fork bomb/遗留子进程 | systemd `TasksMax`、PID namespace、`KillMode=control-group` | 子进程耗尽和取消夹具后可立即再次运行 |
| 工作区耗尽 | tmpfs `size`/`nr_inodes`、文件大小和打开句柄限制 | 单次工作区不落入应用数据或课程目录 |
| 路径/命令注入 | 语言枚举、固定命令和参数列表；源码/输入只经文件传递 | 不拼接学生源码为 shell 命令 |
| 错误泄露绝对路径 | 输出限长并替换宿主临时路径和用户名 | 编译/运行结果不返回 Windows 临时目录 |

安全评价在 WSL2、systemd 用户 cgroup、user/PID/mount/network namespace 或指定工具链缺失时
**默认拒绝**，不会退回宿主直跑。当前机器已验证 Python 3.14、GCC/G++ 15.2；WSL 尚无 Linux
JDK，因此 Java 安全评价显示稳定的工具链不可用原因码。

## 3. 本地 IDE 模式

本地运行是所有 CODE 活动的常规能力，不再要求课程开关或逐次阻断式确认：

- 工作区持续显示“本地 IDE、文件与网络权限、学生负责”的状态，不以弹窗打断每次运行；
- “本地运行”直接编译/执行并显示标准输出、标准错误、退出码、状态和耗时；
- Java 使用本地 JDK 21，C/C++ 使用 MSVC x64 Developer Shell，Python 使用联网 WSL；
- 支持标准输入、输出上限、墙钟超时和取消；这些是 IDE 稳定性保护，不是安全隔离声明；
- “打开终端”把源码导出到应用数据目录的 `local-code-workspaces/`，进入对应本地工具链；
- MSVC、Cygwin、本地 JDK/WSL 的行为和网络访问由学生负责；
- 普通本地运行不自动写入课程评价；需要提交课程证据时，学生显式选择“安全评价”。

这样，SQLTeacher 同时承担代码编辑/运行 IDE 与学习评价平台职责，而不是把真实开发环境放在产品之外。

## 4. 当前实现切片

- sealed 活动模型增加 CODE 规格、源码 artifact、语言、测试和资源策略；
- `CodeActivityEvaluator` 只调用注入的 `CodeRunner`，按稳定原因码保存确定性测试结果；
- WSL Runner 使用 systemd cgroup 包住一次性 namespace/chroot 工作区；
- UI 使用 RichTextFX 编辑器、标准输入和控制台；本地运行与安全评价都在后台执行并支持取消；
- schema 13 增加 Java/Python/C/C++ 四个“两数求和”内置活动；
- 本地 JDK 21、WSL Python 和 MSVC C/C++ 的真实编译运行已接线；另可打开对应本地终端。

## 5. 未完成门禁

在以下事项完成前，本 RFC 保持“实施中”，不建立 alpha.4 发布说明或完成记录：

- 为安全 Runner 提供可审计、可校验、许可完整的 Linux JDK 21 工具链，而不是依赖当前 WSL 手工安装；
- 冻结 WSL 发行版/版本探测与 Windows 10/11 无 WSL 时的安装引导；
- 增加编译错误、内存限制原因分类、文件/inode 耗尽和并发 Runner 固定夹具；
- 对取消、快速切活动、本地运行/终端、低分辨率和中英文 UI 做实际 JavaFX 走查；
- 完整 `mvn test`、相邻 schema 升级、打包、app-image 启动和敏感文件扫描通过。

完成证据将单独写入 acceptance 和 Stage 12；本文件只冻结设计与当前验证边界。
