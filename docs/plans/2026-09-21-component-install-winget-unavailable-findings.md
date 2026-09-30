# 2026-09-21 组件一键安装 winget 缺失问题诊断

- 制定日期：2026-09-21
- 触发反馈：用户（Windows，SQLTeacher 3.7.0；即[工具链断链诊断](2026-09-21-toolchain-junction-crash-findings.md)同一用户，断链删除后应用已恢复）自行安装 Ollama 后组件页仍显示 MISSING，点击"安装或修复"报"组件安装失败 / Local application operation failed"
- 影响版本：组件一键安装自 v3.3 引入起依赖 winget；本缺陷在所有 winget 缺失的机器上存在，与 3.7.0/3.8.0 版本无关
- 状态：已诊断，修复未实施（已挂[积压清单](backlog.md)）；用户侧解决路径已提供

## 现象与根因

用户机器日志中 `settings.component.install` 反复以同一异常失败：

```text
java.lang.IllegalStateException: WINGET_UNAVAILABLE
    at WindowsManagedComponentService.installCommand(WindowsManagedComponentService.java:186)
```

两个叠加的缺陷：

1. **一键安装硬依赖 winget，缺失时必败且 UI 不设防**。JDK/Ollama/MSVC 的"安装或修复"全部经 `winget install --id ...` 执行，winget 探测仅查 `%LOCALAPPDATA%\Microsoft\WindowsApps\winget.exe` 与 PATH（`WindowsManagedComponentService.java:246`）。winget 缺失（老版 Windows 10、LTSC 等无"应用安装程序"的机器）时安装必然失败。卡片状态其实已经知道这件事——`status()` 会输出 `INSTALLER_UNAVAILABLE`（`WindowsManagedComponentService.java:173`），但按钮仍可点击，点击即抛异常。
2. **错误码未透传，真实原因被兜底吞掉**。`IllegalStateException` 不属于协议层任何结构化异常分支，落入兜底 `catch (Exception)` 统一映射为 `LOCAL_APP_FAILURE`："Local application operation failed"（`LocalAppProtocolServer.java:154`），用户看不到 `WINGET_UNAVAILABLE` 的真实含义。

## 影响范围

- winget 缺失的机器上，JDK、Ollama、MSVC 三张卡片的"安装或修复"全部不可用（WSL 卡走 `wsl.exe --install`，仅受 `WSL_COMMAND_UNAVAILABLE` 影响，`WindowsManagedComponentService.java:182`）；
- 组件**检测**本身不受影响（Python READY、Cloud 正常均属实），用户看到的 MISSING 是检测结论，与安装失败是两回事。

## 关联发现：组件识别与重启语义（同一次支持过程确认）

排查用户"Ollama 已安装却显示 MISSING"时确认的事实，供后续支持与修复参考：

- Ollama 探测只有两条：sidecar 进程 PATH 上的 `ollama.exe`，或 `%LOCALAPPDATA%\Programs\Ollama\ollama.exe`（`WindowsManagedComponentService.java:213`）；
- PATH 是 sidecar 进程**启动时刻的快照**：先开 SQLTeacher 后装 Ollama，必须让应用拿到新 PATH 才能识别；
- 应用为单实例：关闭窗口或再次双击图标通常只是唤起既有实例，不重启进程——必须托盘右键退出（或任务管理器确认无残留进程）后再启动，"手动检测"才有意义；
- 沟通坑：PowerShell 里 `where` 是 `Where-Object` 别名，验证 PATH 需用 `where.exe ollama`。

用户实际卡在"没有做真正意义上的重启"。该条属于使用指引范畴，产品上可考虑在组件页提示"安装新组件后需重启应用再检测"。

## 用户侧解决路径（已提供）

- 组件本体：绕开应用内一键安装，手动装官方安装包（Ollama 官网、Eclipse Temurin JDK 21+、VS Build Tools）；
- 或先恢复 winget：Microsoft Store 安装/更新"应用安装程序"（App Installer）；
- 装完新组件后：托盘完全退出 SQLTeacher 再启动，再点"手动检测"。

## 修复方案（待实施）

1. `WINGET_UNAVAILABLE`、`WSL_COMMAND_UNAVAILABLE` 等安装前置异常改抛结构化错误码（`SqlTeacherException`），前端据此提示"本机缺少 winget（应用安装程序），请手动安装组件或先安装应用安装程序"，替换笼统的 "Local application operation failed"；
2. 卡片状态为 `INSTALLER_UNAVAILABLE` 时禁用"安装或修复"按钮，就地展示手动安装指引；
3. 可选：winget 缺失时提供 App Installer 获取引导；Ollama 探测增加注册表卸载信息兜底非默认安装路径；组件页提示"新装组件后需重启应用再检测"。

## 证据清单

- 用户日志两份（`sidecar(1).log`、`sqlteacher-sidecar(1).log`，2026-09-20/21 多次 `WINGET_UNAVAILABLE` 堆栈；微信转发，不入库）；
- 组件页截图（JDK/Ollama/MSVC MISSING + INSTALLER_UNAVAILABLE 字样 + 红色"组件安装失败"横幅）；
- 代码定位见上文行号，可复核。

返回[迭代计划索引](README.md) · [文档中心](../README.md)。
