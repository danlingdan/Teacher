# SQLTeacher v1.0 Windows 安装、升级与卸载

## 安装

运行 `SQLTeacher-1.1.0.exe`，选择当前用户安装目录并完成安装。默认程序目录为 `%LOCALAPPDATA%\SQLTeacher-App`，与 `%LOCALAPPDATA%\SQLTeacher` 用户数据目录严格分离。安装器包含 Java 21 运行时，可创建开始菜单和桌面快捷方式。安装包尚未代码签名，Windows 可能显示来源提示；请只使用项目 GitHub Release 发布的文件并核对 SHA-256。

## 升级

关闭旧版后运行新版安装器。固定的 Upgrade UUID 用于识别同一产品。应用首次启动时会：

1. 将旧工作目录 `app-data` 迁移至 `%LOCALAPPDATA%\SQLTeacher`（仅当新目录没有 `app.db`）。
2. 检查数据库 schema 版本。
3. 在需要迁移时先创建自动备份。
4. 事务执行迁移；失败时尝试恢复升级前备份并停止启动。

升级前仍建议在“设置与数据”手动备份。

## 卸载与数据保留

可从 Windows“已安装的应用”卸载 SQLTeacher。卸载程序只移除应用文件、快捷方式和运行时，不删除 `%LOCALAPPDATA%\SQLTeacher`。若用户明确要彻底清除数据，应先备份，再手动删除该目录；此操作不可恢复。

## 构建发布包

在 JDK 21、Maven 3.9+ 环境运行：

```powershell
.\packaging\package-stage1.ps1
```

脚本生成 EXE、app-image 和 ZIP。首次构建会下载并校验 WiX 3.14.1；`-SkipInstaller` 仅生成 app-image 和 ZIP。
