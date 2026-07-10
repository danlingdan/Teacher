package com.sqlteacher.desktop.mock;

import com.sqlteacher.application.ai.AiStatus;
import com.sqlteacher.application.database.DatabaseInitializationResult;
import com.sqlteacher.application.execution.SqlExecutionRequest;
import com.sqlteacher.application.execution.SqlExecutionResult;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.desktop.viewmodel.AiAssistantViewModel;
import com.sqlteacher.desktop.viewmodel.AiStatusViewModel;
import com.sqlteacher.desktop.viewmodel.DatabaseStatusViewModel;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import com.sqlteacher.desktop.viewmodel.HomeStatusViewModel;
import com.sqlteacher.desktop.viewmodel.SqlExecutionViewModel;
import com.sqlteacher.desktop.viewmodel.SqlResultRowViewModel;
import com.sqlteacher.desktop.viewmodel.UiStatusLevel;
import com.sqlteacher.infrastructure.config.SqlTeacherProperties;
import com.sqlteacher.infrastructure.environment.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test between the desktop mock services and the repackaged backend DTOs.
 *
 * <p>The test has no JavaFX dependency; it only validates data-model structure and the
 * DTO to ViewModel field mapping / type conversion. It can therefore run standalone with
 * {@code mvn -Dtest=MockServiceContractTest test}.
 */
class MockServiceContractTest {

    // ----- SqlExecutionService contract -----

    @Test
    void sqlExecutionNormalMatchesBackendDtoAndMapsToViewModel() {
        SqlExecutionMockService mock = new SqlExecutionMockService(MockScenario.NORMAL);
        SqlExecutionRequest request = mock.demoRequest("SELECT id, name, grade FROM student ORDER BY grade DESC");

        SqlExecutionResult result = mock.execute(request);

        // DTO structure matches backend contract.
        assertEquals(DesktopConnections.DEMO, request.connectionId());
        assertTrue(result.success());
        assertNotNull(result.columns());
        assertNotNull(result.rows());
        assertEquals(List.of("id", "name", "grade"), result.columns());
        assertEquals(3, result.rows().size());
        assertEquals(0, result.affectedRows());
        assertFalse(result.truncated());
        assertNotNull(result.duration());
        Map<String, Object> firstRow = result.rows().get(0);
        assertEquals(1, firstRow.get("id"));
        assertEquals("Alice", firstRow.get("name"));

        // ViewModel mapping / type conversion.
        SqlExecutionViewModel viewModel = SqlExecutionViewModel.from(request, result);
        assertEquals(DesktopConnections.DEMO, viewModel.connectionId());
        assertEquals(UiStatusLevel.SUCCESS, viewModel.statusLevel());
        assertTrue(viewModel.success());
        assertEquals(3, viewModel.rowCount());
        assertEquals(3, viewModel.rows().size());
        assertEquals(result.affectedRows(), viewModel.affectedRows());
        assertEquals(result.truncated(), viewModel.truncated());
        assertEquals(result.duration().toMillis(), viewModel.executionMillis());
        SqlResultRowViewModel firstMapped = viewModel.rows().get(0);
        assertEquals(List.of("1", "Alice", "92"), firstMapped.cells());
    }

    @Test
    void sqlExecutionEmptyReturnsSuccessWithZeroRows() {
        SqlExecutionMockService mock = new SqlExecutionMockService(MockScenario.EMPTY);
        SqlExecutionRequest request = mock.demoRequest("SELECT * FROM student WHERE 1 = 0");

        SqlExecutionResult result = mock.execute(request);
        SqlExecutionViewModel viewModel = SqlExecutionViewModel.from(request, result);

        assertTrue(result.success());
        assertTrue(result.rows().isEmpty());
        assertEquals(UiStatusLevel.SUCCESS, viewModel.statusLevel());
        assertEquals(0, viewModel.rowCount());
        assertTrue(viewModel.rows().isEmpty());
        assertFalse(viewModel.columns().isEmpty());
    }

    @Test
    void sqlExecutionErrorReturnsFailedResultMappedToErrorLevel() {
        SqlExecutionMockService mock = new SqlExecutionMockService(MockScenario.ERROR);
        SqlExecutionRequest request = mock.demoRequest("SELCT * FROM student");

        SqlExecutionResult result = mock.execute(request);
        SqlExecutionViewModel viewModel = SqlExecutionViewModel.from(request, result);

        assertFalse(result.success());
        assertFalse(result.message().isBlank());
        assertEquals(UiStatusLevel.ERROR, viewModel.statusLevel());
        assertFalse(viewModel.success());
        assertEquals(0, viewModel.rowCount());
    }

    // ----- Nl2SqlService contract -----

    @Test
    void nl2SqlNormalMatchesBackendDtoAndMapsToViewModel() {
        Nl2SqlMockService mock = new Nl2SqlMockService(MockScenario.NORMAL);
        AiStatusViewModel aiStatus = AiStatusViewModel.from(new AiStatusMockService(MockScenario.NORMAL).checkStatus());
        Nl2SqlRequest request = mock.demoRequest("查询成绩最高的学生");

        Nl2SqlPlan plan = mock.generate(request);
        AiAssistantViewModel viewModel = AiAssistantViewModel.from(request, plan, aiStatus);

        assertEquals(DesktopConnections.DEMO, request.connectionId());
        assertFalse(plan.sqlDraft().isBlank());
        assertFalse(plan.intent().isBlank());
        assertFalse(plan.explanation().isBlank());
        assertFalse(plan.model().isBlank());
        assertFalse(plan.promptVersion().isBlank());

        assertEquals(DesktopConnections.DEMO, viewModel.connectionId());
        assertEquals("查询成绩最高的学生", viewModel.naturalLanguage());
        assertEquals(plan.sqlDraft(), viewModel.sqlDraft());
        assertEquals(plan.intent(), viewModel.intent());
        assertEquals(plan.explanation(), viewModel.explanation());
        assertEquals(plan.model(), viewModel.model());
        assertEquals(plan.promptVersion(), viewModel.promptVersion());
        assertTrue(viewModel.draftAvailable());
        assertSame(aiStatus, viewModel.aiStatus());
    }

    @Test
    void nl2SqlEmptyProducesDraftUnavailableViewModel() {
        Nl2SqlMockService mock = new Nl2SqlMockService(MockScenario.EMPTY);
        Nl2SqlRequest request = mock.demoRequest("无法识别的问题");

        Nl2SqlPlan plan = mock.generate(request);
        AiAssistantViewModel viewModel = AiAssistantViewModel.from(request, plan, AiStatusViewModel.from(
            new AiStatusMockService(MockScenario.EMPTY).checkStatus()));

        assertTrue(plan.intent().isBlank());
        assertFalse(viewModel.draftAvailable());
    }

    @Test
    void nl2SqlErrorThrowsMockBackendException() {
        Nl2SqlMockService mock = new Nl2SqlMockService(MockScenario.ERROR);
        Nl2SqlRequest request = mock.demoRequest("查询学生");

        assertThrows(MockBackendException.class, () -> mock.generate(request));
    }

    // ----- AiStatusService contract -----

    @Test
    void aiStatusScenariosMapToExpectedUiLevels() {
        AiStatus normal = new AiStatusMockService(MockScenario.NORMAL).checkStatus();
        AiStatus empty = new AiStatusMockService(MockScenario.EMPTY).checkStatus();
        AiStatus error = new AiStatusMockService(MockScenario.ERROR).checkStatus();

        assertEquals(VerificationStatus.PASS, normal.status());
        assertEquals(2, normal.modelCount());
        assertTrue(AiStatusViewModel.from(normal).available());
        assertEquals(UiStatusLevel.SUCCESS, AiStatusViewModel.from(normal).statusLevel());

        assertEquals(VerificationStatus.PASS, empty.status());
        assertEquals(0, empty.modelCount());
        assertEquals(UiStatusLevel.SUCCESS, AiStatusViewModel.from(empty).statusLevel());

        assertEquals(VerificationStatus.WARNING, error.status());
        assertFalse(AiStatusViewModel.from(error).available());
        assertEquals(UiStatusLevel.WARNING, AiStatusViewModel.from(error).statusLevel());
    }

    @Test
    void aiStatusFailLevelMapsToError() {
        AiStatus hardFailure = new AiStatus(VerificationStatus.FAIL, "ollama", "http://localhost:11434", 0, "fatal");
        AiStatusViewModel viewModel = AiStatusViewModel.from(hardFailure);

        assertEquals(UiStatusLevel.ERROR, viewModel.statusLevel());
        assertFalse(viewModel.available());
    }

    // ----- DatabaseInitializationService + AppConfigurationService -> HomeStatusViewModel -----

    @Test
    void homeStatusComposesDatabaseAndAiViewModels() {
        AppConfigurationMockService configMock = new AppConfigurationMockService(MockScenario.NORMAL);
        DatabaseInitializationMockService databaseMock = new DatabaseInitializationMockService(MockScenario.NORMAL);
        AiStatusMockService aiMock = new AiStatusMockService(MockScenario.NORMAL);

        SqlTeacherProperties properties = configMock.current();
        DatabaseInitializationResult databaseResult = databaseMock.initialize();
        AiStatus aiStatus = aiMock.checkStatus();

        HomeStatusViewModel viewModel = HomeStatusViewModel.from(
            properties.appName(),
            properties.dataDirectory().toString(),
            databaseResult,
            aiStatus
        );

        assertEquals("SQLTeacher", viewModel.appName());
        assertEquals(DesktopConnections.DEMO, viewModel.connectionId());
        assertEquals("app-data", viewModel.dataDirectory());

        DatabaseStatusViewModel database = viewModel.database();
        assertEquals(UiStatusLevel.SUCCESS, database.statusLevel());
        assertTrue(database.appDatabaseCreated());
        assertTrue(database.appDatabasePath().endsWith("app.db"));
        assertFalse(database.summary().isBlank());

        assertEquals(UiStatusLevel.SUCCESS, viewModel.ai().statusLevel());
        assertTrue(viewModel.ai().available());
    }

    @Test
    void databaseInitializationErrorThrowsMockBackendException() {
        DatabaseInitializationMockService mock = new DatabaseInitializationMockService(MockScenario.ERROR);
        assertThrows(MockBackendException.class, mock::initialize);
    }

    @Test
    void appConfigurationErrorThrowsMockBackendException() {
        AppConfigurationMockService mock = new AppConfigurationMockService(MockScenario.ERROR);
        assertThrows(MockBackendException.class, mock::current);
    }

    // ----- AsyncMockInvoker (JavaFX-free async utility) -----

    @Test
    void asyncInvokerReturnsBackendResultOffCallingThread() throws Exception {
        try (AsyncMockInvoker invoker = new AsyncMockInvoker()) {
            SqlExecutionMockService mock = new SqlExecutionMockService(MockScenario.NORMAL);
            SqlExecutionRequest request = mock.demoRequest("SELECT 1");

            CompletableFuture<SqlExecutionViewModel> future =
                invoker.invokeAsync(() -> SqlExecutionViewModel.from(request, mock.execute(request)));

            SqlExecutionViewModel viewModel = future.get();
            assertEquals(3, viewModel.rowCount());
            assertEquals(UiStatusLevel.SUCCESS, viewModel.statusLevel());
        }
    }

    @Test
    void asyncInvokerDeliversErrorThroughUiExecutor() throws Exception {
        try (AsyncMockInvoker invoker = new AsyncMockInvoker()) {
            Nl2SqlMockService mock = new Nl2SqlMockService(MockScenario.ERROR);
            Nl2SqlRequest request = mock.demoRequest("查询学生");
            Executor sameThreadExecutor = Runnable::run;

            CompletableFuture<Throwable> delivered = new CompletableFuture<>();
            invoker.invoke(
                () -> mock.generate(request),
                sameThreadExecutor,
                success -> delivered.complete(null),
                delivered::complete
            );

            Throwable error = delivered.get();
            assertNotNull(error);
            assertInstanceOf(MockBackendException.class, error);
        }
    }

    @Test
    void asyncInvokerFutureCompletesExceptionallyWithUnwrappedCause() {
        try (AsyncMockInvoker invoker = new AsyncMockInvoker()) {
            Nl2SqlMockService mock = new Nl2SqlMockService(MockScenario.ERROR);
            Nl2SqlRequest request = mock.demoRequest("查询学生");

            CompletableFuture<Nl2SqlPlan> future = invoker.invokeAsync(() -> mock.generate(request));

            ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(MockBackendException.class, thrown.getCause());
        }
    }
}
