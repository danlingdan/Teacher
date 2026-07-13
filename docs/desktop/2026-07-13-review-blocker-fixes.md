# 2026-07-13 桌面模块评审阻塞修复（P1/P2）

对齐远端最新 develop 契约基线后，仅在 desktop 桌面模块内修复 4 项评审阻塞问题；
只调用 application 层已暴露接口，未改动 application / infrastructure 底层业务源码。

## 改动清单

### P1.1 契约兼容 · AppConfigurationMockService + AiStatusMockService
- `src/test/java/com/sqlteacher/desktop/mock/AppConfigurationMockService.java`
  - 删除已废弃的 `infrastructure.config.{SqlTeacherProperties, DatabaseProperties, AiModelProperties}`。
  - 改用 `application.config.{SqlTeacherConfiguration, DatabaseConfiguration, AiConfiguration}`，`current()` 返回 `SqlTeacherConfiguration`。
  - EMPTY 场景：新契约紧凑构造禁止空 `appName`，原「空 appName」写法非法，改为返回仅含默认值的合法配置。
- `src/test/java/com/sqlteacher/desktop/mock/AiStatusMockService.java`
  - AI 传参从 `infrastructure.environment.VerificationStatus` 改为 `application.ai.AiAvailability`：
    PASS→`AVAILABLE`（normal / emptyModels），WARNING→`UNAVAILABLE`（unavailable）。

### P1.2 JavaFX 线程阻塞 · SqlPracticeController.onExecuteSql()
- `src/main/java/com/sqlteacher/desktop/controller/SqlPracticeController.java`
  - 新增单线程守护 `ExecutorService`；SQL 执行放入 `javafx.concurrent.Task` 后台线程。
  - 点击后 FX 线程先做空白校验（空 SQL 立即错误态返回，不启动任务），随即禁用按钮并展示 loading 占位。
  - `setOnSucceeded` / `setOnFailed` 通过 `Platform.runLater` 切回 FX 线程刷新表格 / 错误提示并恢复按钮，杜绝耗时操作冻结窗口。
  - 保留原三态渲染与异常语义（后端异常经 Task 传播到 `setOnFailed`；`success=false` DTO 仍走 `renderExecution`）。

### P1.3 AI 枚举映射 · UiStatusLevel.fromStatusName()
- `src/main/java/com/sqlteacher/desktop/viewmodel/UiStatusLevel.java`
  - 移除旧 `PASS / WARNING / FAIL` 匹配。
  - 适配 `AiStatus.status()` 的 `AiAvailability`：`AVAILABLE`→SUCCESS、`UNAVAILABLE`→WARNING、其它 / null→UNKNOWN。
  - 保证 AI 可用状态不再显示为「未知」。同步修正 `AiStatusViewModel` 的过时 javadoc。

### P2.4 SQL Mock 安全校验 · SqlExecutionMockService.execute()
- `src/main/java/com/sqlteacher/desktop/mock/SqlExecutionMockService.java`
  - 构造注入 `application.risk.SqlRiskAnalysisService`（默认复用 `application.mock.MockSqlRiskAnalysisService`）。
  - 返回场景数据前先做前置安全闸：
    1. 不可执行（空 SQL / 多语句 / DROP、TRUNCATE 等非 SELECT 高危语句）→ 返回失败 DTO（message 汇总风险原因）；
    2. 需二次确认且请求未确认（`riskConfirmed=false`）→ 返回「需二次确认」失败 DTO；
    3. 通过安全闸后按场景返回原有 normal / empty / failed。
  - 保留兼容构造 `new SqlExecutionMockService(MockScenario.NORMAL)`（供离线启动器注入）。

## 测试
- `src/test/java/com/sqlteacher/desktop/mock/MockServiceContractTest.java`
  - 契约类型 / 枚举更新：`SqlTeacherConfiguration`、`AiAvailability`。
  - 重写原 `aiStatusFailLevelMapsToError` → `aiStatusUnavailableMapsToWarningNotUnknown`（`AiAvailability` 无 FAIL，验证 UNAVAILABLE→WARNING 且不落 UNKNOWN）。
  - ERROR 场景 SQL 改为合法 SELECT，确保通过安全闸后走场景失败 DTO。
  - 新增 P2.4 用例：拦截空 SQL、多语句 SQL、DROP/TRUNCATE 高危语句；注入自定义风险分析桩验证二次确认分支（未确认拦截 / 已确认放行）；合法 SELECT 放行。

## 验证
- 命令：`mvn clean test`（离线）。
- 结果：`Tests run: 40, Failures: 0, Errors: 0, Skipped: 0` · `BUILD SUCCESS`。
- 其中 `MockServiceContractTest` 19 项全部通过（原 14 + 新增 5）。
- 环境：JDK 21.0.11，Maven 3.9.15。桌面 UI 线程化改造未做 UI 自动化验证（无图形环境），已通过服务层 / 契约测试覆盖非 UI 逻辑。
