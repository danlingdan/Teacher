# 本地学习 IDE 与代码 Runner 配置

> 适用于 `2.0.0-alpha.4` 及后续版本。

SQLTeacher 的 CODE 活动提供两条明确分离的运行路径：

- **本地运行**是 IDE 主路径。学生程序继承当前账户可用的文件、环境变量、工具链和网络权限，学生对自己编写、导入和运行的非 SQL 代码负责。
- **安全评价**是可选课程评价路径。它在 WSL2 的一次性隔离工作区中运行固定测试，默认无网络、无宿主文件和凭据访问；隔离条件不足时拒绝执行，不退回本地直跑。

SQL 与 AI 仍遵守项目现有安全规范。本指南不放宽 SQL 确认、模型信任、教师权限或学生隐私边界。

## 本地 IDE 工具链

| 语言 | 本地运行工具链 | 最低准备 |
| --- | --- | --- |
| Java | Windows JDK | JDK 21；支持 `java.home`、`JAVA_HOME`、`JDK_HOME`、PATH 和常见厂商/IDE 安装目录，缺失时可安装 Temurin 21 |
| Python | Windows Python 或 WSL Ubuntu | 支持 PATH、Python Launcher、用户/系统安装目录和 pyenv-win，缺失时可自动安装 Python 3.13 |
| C/C++ | MSVC、GCC/MinGW/MSYS2/Cygwin 或 Clang | 支持 PATH、Visual Studio Installer/`vswhere`、VS 开发者环境及常见工具链目录，也可安装 Visual Studio 2026 Build Tools |

“打开终端”会把当前源码导出到 `local-code-workspaces/` 后打开对应工具链。程序在该终端中的文件、网络、依赖安装和命令行为由学生负责。

内置代码活动编辑器为 Java、Python、C 和 C++ 提供语言对应的关键字、字符串、注释、数字和预处理指令高亮。按 `Ctrl+Space` 可补全该语言的基础关键字、常用标准标识符，以及当前源码中已经声明或使用的标识符；补全完全在本地确定性运行，不会把源码发送给 AI 或网络服务。

## 自动下载安装

打开“设置 → 开发环境”后，SQLTeacher 会在后台探测兼容 JDK、Python、Ollama、
MSVC/GCC/Clang 工具链和 WSL Ubuntu。检测与实际代码 Runner 共用同一套发现逻辑，不要求工具必须由 SQLTeacher 安装，也不限定单一厂商或固定年份。缺少组件时可点击“自动安装”：

1. 页面先展示固定的软件来源、许可证、管理员权限和重启影响；
2. 用户确认后，JDK、Python、Ollama 与 Visual Studio 2026 Build Tools 通过 Windows Package Manager 的固定包 ID 安装；
3. WSL 通过 Windows 自带的 `wsl --install -d Ubuntu --no-launch` 安装；
4. 安装可取消，完成后自动复检；若 Windows 功能或 PATH 尚未刷新，会显示需要重启或初始化；
5. SQLTeacher 不静默提权、不自动重启，也不卸载用户已有的软件。

该功能要求系统具备 WinGet；WSL 安装要求 Windows 提供 `wsl.exe`。WinGet 不可用、离线、
安装器退出失败或用户取消时，页面保留失败状态并允许重试。Ollama 安装完成后仍需在 AI 助手中
下载或选择具体模型，模型许可不等同于 Ollama 软件许可。

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
