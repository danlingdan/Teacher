# SQLTeacher 3 Alpha.7 UI

本目录是 v3.0 Tauri 2 + React + TypeScript 技术路线。业务规则仍由仓库根目录中的 Java 应用层、领域层和基础设施层拥有。

## 本地验证

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.3"
../packaging/build-v3-sidecar.ps1
npm install
npm test
npm run test:performance
npm run build
npm run tauri dev
```

生产型 Windows 构建：

```powershell
npm run tauri build -- --bundles nsis
```

打包态桌面 E2E（仅测试构建启用 WebDriver 插件）：

```powershell
npm run test:e2e:build
npm run test:e2e
```

Sidecar 性能原型：

```powershell
./scripts/measure-alpha1.ps1
```

生成的运行时、Rust target、前端 dist、node_modules 和性能 JSON 都是本地构建产物，不进入 Git。

## 边界

- React 只调用 `src-tauri/src/lib.rs` 暴露的单一白名单命令。
- Rust 负责 Java 子进程启停、超时、响应路由和退出回收，不拥有业务规则。
- Java `LocalAppHost` 再次校验 `3.0-v1` 合同版本、方法、载荷和并发上限；机器可读合同位于 `../contracts/ipc/v1/`。
- 浏览器预览不提供模拟学习数据；必须通过 Tauri 才能读取真实本地摘要。
