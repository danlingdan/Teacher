# 本地学习 IDE 与代码 Runner 配置

> 适用于 `2.0.0-alpha.4` 及后续版本。

SQLTeacher 的 CODE 活动提供两条明确分离的运行路径：

- **本地运行**是 IDE 主路径。学生程序继承当前账户可用的文件、环境变量、工具链和网络权限，学生对自己编写、导入和运行的非 SQL 代码负责。
- **安全评价**是可选课程评价路径。它在 WSL2 的一次性隔离工作区中运行固定测试，默认无网络、无宿主文件和凭据访问；隔离条件不足时拒绝执行，不退回本地直跑。

SQL 与 AI 仍遵守项目现有安全规范。本指南不放宽 SQL 确认、模型信任、教师权限或学生隐私边界。

## 本地 IDE 工具链

| 语言 | 本地运行工具链 | 最低准备 |
| --- | --- | --- |
| Java | Windows JDK | 安装 JDK 21，并确保 `java`、`javac` 在 PATH 中 |
| Python | WSL Ubuntu | `wsl --install -d Ubuntu`，并在 Ubuntu 中安装 `python3` |
| C/C++ | MSVC x64 Developer Shell | Visual Studio 或 Build Tools 的“使用 C++ 的桌面开发”工作负载 |

“打开终端”会把当前源码导出到 `local-code-workspaces/` 后打开对应工具链。程序在该终端中的文件、网络、依赖安装和命令行为由学生负责。

## 可选安全评价环境

安全评价要求 Windows 10/11、WSL2、Ubuntu、systemd、用户/PID/mount/network namespace，以及对应 Linux 工具链。推荐从管理员 PowerShell 安装 WSL：

```powershell
wsl --install -d Ubuntu
wsl --update
```

然后在 Ubuntu 中安装发行版签名的软件包：

```bash
sudo apt update
sudo apt install --no-install-recommends \
  openjdk-21-jdk-headless python3 gcc g++ util-linux systemd
```

默认发行版名是 `Ubuntu`。需要使用其他已安装的 Ubuntu 实例时，可在启动参数中指定：

```text
-Dsqlteacher.runner.wsl.distribution=Ubuntu-24.04
```

Runner 会探测 Ubuntu 24.04 或更新版本、WSL2 内核、systemd、`unshare`、`systemd-run`、`setpriv`、`prlimit` 和语言工具链。缺少条件时会显示稳定原因码，例如 `WSL_NOT_INSTALLED`、`WSL_DISTRIBUTION_UNAVAILABLE`、`WSL_UBUNTU_REQUIRED`、`WSL_UBUNTU_VERSION_UNSUPPORTED`、`WSL2_REQUIRED`、`WSL_ISOLATION_UNAVAILABLE` 或语言工具链不可用。

安全评价仍是纵深防御，不宣称能够抵御 WSL/Linux 内核或编译器自身漏洞；不可信代码不得在 SQLTeacher 主 JVM 内执行。

返回 [使用与开发指南](README.md)。
