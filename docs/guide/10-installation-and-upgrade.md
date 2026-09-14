# SQLTeacher 3 Windows 安装、升级与卸载

> 当前代码基线：`3.0.0`；公开稳定版本仍以 GitHub Releases 的 latest 标记为准。

## 安装

SQLTeacher 3 只提供 Tauri/React 桌面端。以管理员身份运行对应版本的 `SQLTeacher-X.Y.Z.exe` 完成 Windows 安装，或解压
`SQLTeacher-X.Y.Z-windows-x64.zip` 后运行 `SQLTeacher.exe`。安装包和便携包都包含 Java 25 sidecar runtime，
不需要系统预装 JDK；Windows WebView2 由 NSIS 引导检查并按配置安装。
正式构建的 Tauri 主进程和 Java sidecar 均不创建控制台窗口。

只从项目 GitHub Release 下载文件，并使用同一 Release 的 `SHA256SUMS.txt` 核对 EXE 和 ZIP。用户数据保存在
`%LOCALAPPDATA%\SQLTeacher`；NSIS 程序安装在 `%ProgramFiles%\SQLTeacher`，两者严格分离。

## 许可协议页与法律文件

v3.4.2 起安装器在正常（交互式）安装时，会在选择安装位置之前多出一页可滚动的许可协议页，内容为
Apache License 2.0 全文（项目根 `LICENSE`），同意后才能继续安装；passive 与静默安装自动跳过该页，
既有升级脚本与无人值守流程不受影响。安装器的文件属性（LegalCopyright）标注
`Copyright 2026 河南科技大学`。

安装目录携带 `legal\` 子目录（`%ProgramFiles%\SQLTeacher\legal\`），内含三份法律文件：

- `LICENSE.txt`：Apache License 2.0 全文；
- `THIRD-PARTY-LICENSES.txt`：第三方组件许可清单；
- `PRIVACY.md`：隐私说明（应用内“关于”面板与帮助主题“隐私”展示的即同一文本）。

便携 ZIP 同样携带 `legal\` 目录，除上述三份文件外还包含 `sqlteacher-sbom.json` 与
`sqlteacher-ui-sbom.json` 两份 CycloneDX SBOM 依赖清单。卸载会随安装目录一并移除 `legal\`。

## 升级和数据兼容

关闭旧版后以管理员身份运行新版安装器。安装器会按旧版统一使用的产品名 `SQLTeacher` 和发布者
`SQLTeacher Project` 识别 v1.0 至 v2.3 的 jpackage/WiX 安装，先调用 Windows Installer 完成旧程序卸载，
再安装 Tauri 桌面端。旧的 `%LOCALAPPDATA%\SQLTeacher-App` 程序目录、JavaFX runtime 和旧快捷方式由旧版
卸载器移除；用户数据目录不在卸载目标内。

已安装的 3.0 Alpha/Beta 使用过当前用户 NSIS。正式版安装器会额外检查 HKCU 中产品名为
`SQLTeacher 3 Alpha`、`SQLTeacher 3 Beta` 或 `SQLTeacher`，且发布者为 `sqlteacher` 或
`SQLTeacher Project` 的登记项，并只调用其中登记的卸载器；它不会自行递归删除目录。
旧卸载器返回失败或卸载项仍存在时，正式版安装立即停止。

应用首次启动时继续使用 Java 核心的数据库初始化与追加式 schema 迁移：

1. 检查历史数据目录和当前 schema 版本。
2. 在需要迁移时先创建升级前备份。
3. 在事务中执行迁移并校验版本；失败时恢复备份并停止启动。
4. Tauri/React 只通过版本化 IPC 访问迁移结果，不直接读写 SQLite 文件。

从 2.3 升级不会启动或安装 JavaFX；原有用户数据、账号、安全配置和确定性学习事件由 Java sidecar 兼容读取。
若旧版卸载被用户取消或 Windows Installer 返回失败，新版安装也会停止，不会在保留旧程序的情况下继续覆盖安装。

## 卸载与数据保留

可从 Windows“已安装的应用”卸载 SQLTeacher。卸载只移除 `%ProgramFiles%\SQLTeacher` 下的应用、sidecar runtime
和快捷方式，不删除
`%LOCALAPPDATA%\SQLTeacher`。若用户明确要彻底清除数据，应先备份，再手动删除该目录；该操作不可恢复。

## 从源码运行

需要 JDK 25、Maven 3.9+、Node.js 24 与 Rust stable：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
.\packaging\build-v3-sidecar.ps1 -JavaHome $env:JAVA_HOME
Set-Location ui-web
npm ci
npm run tauri dev
```

## 构建候选包

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
.\packaging\package-v3.ps1 -JavaHome $env:JAVA_HOME
```

脚本只生成 Tauri NSIS EXE、包含 Java 25 runtime 的 Windows x64 ZIP、`SHA256SUMS.txt`、Java CycloneDX SBOM
和 UI CycloneDX SBOM。推送与 `pom.xml` 完全一致的标签后，发布工作流重新执行全量 Java、前端和 Rust 门禁，
再创建签名更新清单与 GitHub Release。
