# v2.0 alpha.4 本地学习 IDE 与代码 Runner 实施记录

> 实施日期：2026-08-09
> 版本：`2.0.0-alpha.4`

## 已完成

- 将产品定位和主要桌面文案统一为“本地优先、可验证的计算机专业学习 IDE”；
- 增加 CODE 活动规格、源码 artifact、执行限制、测试用例和稳定 Runner 结果；
- schema 13 发布 Java、Python、C、C++ 四个真实编程活动；
- RichTextFX 工作区提供代码编辑、标准输入、控制台、本地运行、安全评价、终端和取消；
- Windows 本地 Runner 接入 JDK 21、联网 WSL Python 和 MSVC C/C++，继承当前账户环境；
- WSL 安全 Runner 以 systemd cgroup 和一次性 namespace/chroot 工作区隔离评价；
- 增加 Ubuntu/WSL2/systemd/工具链能力探测、Java 只读工具链配置和资源原因分类；
- 完成四语言真实执行、恶意夹具、并发恢复、JavaFX 中英文/低分辨率/取消/快速切页和 Windows 打包门禁。

## 决策边界

根据 Alpha4 期间的明确产品决策，本地运行不再作为受限沙箱：学生程序可以访问其账户已有的文件、环境、网络和本地工具链，学生对非 SQL 代码负责。课程需要可信自动评价时显式使用安全评价。

安全评价仍 fail closed，不在条件不足时切换到本地运行；SQL、AI、权限与隐私 enforcement 不变。Linux 工具链是可探测的可选 WSL 环境依赖，不捆绑进 Windows 安装包。

## 主要实现位置

- `application/runner/`：Runner 请求、结果、能力、取消与本地 IDE 标记；
- `infrastructure/runner/WindowsLocalIdeCodeRunner.java`：Windows/WSL/MSVC 本地执行；
- `infrastructure/runner/WslSandboxCodeRunner.java`：能力探测、cgroup 调度和结果分类；
- `resources/runner/wsl-sandbox-runner.sh`：namespace、tmpfs/chroot、工具链和资源 enforcement；
- `desktop/component/ActivityInteractionPane.java`：代码 IDE 工作区；
- `infrastructure/database/SqliteSchemaMigrator.java`：schema 13 与四语言活动。

完整验证证据见 [alpha.4 阶段门禁](../../../acceptance/2026-08-09-v2-alpha4-stage-gate.md)。
