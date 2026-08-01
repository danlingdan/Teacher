package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

import com.sqlteacher.application.ai.*;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.nl2sql.Nl2SqlPlan;
import com.sqlteacher.application.nl2sql.Nl2SqlRequest;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyResult;
import com.sqlteacher.application.nl2sql.Nl2SqlSafetyService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlRiskLevel;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import com.sqlteacher.desktop.viewmodel.DesktopConnections;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI 助手页面控制器：接收用户自然语言提问，调用应用层 NL2SQL 服务生成 SQL 查询语句并展示解释。
 *
 * <p><b>依赖注入</b>：通过构造函数注入 {@link Nl2SqlSafetyService} 和 {@link Consumer} 回调。
 *
 * <p><b>线程模型</b>：所有耗时操作通过 {@link Task} 提交到桌面共享后台线程池，
 * Task 的成功 / 失败事件在 FX 线程更新 UI。
 * 同时调用 {@link GlobalLoading} 显示 / 隐藏全局 Loading 遮罩。
 *
 * <p><b>离线降级</b>：当 NL2SQL 服务调用失败时，显示离线提示文案，禁用生成按钮。
 */
public final class AiAssistantController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantController.class);

    private static final String GENERATING_MESSAGE = AppI18n.get("AiAssistantController.1");
    private static final String REQUEST_FAILED_MESSAGE = AppI18n.get("AiAssistantController.2");
    private static final String EMPTY_INPUT_MESSAGE = AppI18n.get("AiAssistantController.3");
    private static final String LOCAL_SOURCE = AppI18n.get("AiAssistantController.4");
    private static final String NETWORK_SOURCE = AppI18n.get("AiAssistantController.5");

    private final Nl2SqlSafetyService nl2SqlSafetyService;
    private final AiModelSelectionService aiModelSelectionService;
    private final NetworkAiSettingsService networkAiSettingsService;
    private final AiProviderProfileService profileService;
    private final AiProviderProbeService probeService;
    private final AiTaskHistoryService historyService;
    private final Consumer<String> fillSqlCallback;
    private final Runnable switchPageCallback;
    private final SqlRiskAnalysisService sqlRiskAnalysisService;
    private final ConnectionManagementService connectionManagementService;
    private Nl2SqlSafetyResult currentResult;
    private boolean applyingModelSelection;
    private boolean modelOperationInProgress;
    private boolean generationInProgress;
    private boolean applyingProviderSelection;
    private boolean applyingProfileSelection;
    private Task<?> activeAiTask;

    @FXML
    private TextArea questionInput;

    @FXML
    private ComboBox<String> modelSelector;

    @FXML private ComboBox<String> providerSelector;
    @FXML private VBox localProviderPane;
    @FXML private VBox networkProviderPane;
    @FXML private TextField networkEndpointField;
    @FXML private ComboBox<String> networkModelSelector;
    @FXML private TextField networkProfileNameField;
    @FXML private ComboBox<String> networkProfileSelector;
    @FXML private PasswordField networkApiKeyField;
    @FXML private Label providerStatusLabel;
    @FXML private Label privacyPreviewLabel;
    @FXML private Label historyStatusLabel;
    @FXML private TextField revisionInstructionField;
    @FXML private Button reviseButton;
    @FXML private Button favoriteDraftButton;
    @FXML private Button cancelAiButton;

    @FXML
    private Button refreshModelsButton;

    @FXML
    private Label modelStatusLabel;

    @FXML
    private Button generateButton;

    @FXML
    private TextArea sqlPreviewArea;

    @FXML
    private Label sqlPlaceholder;

    @FXML
    private Button copyToPracticeButton;

    @FXML
    private TextArea aiExplanationArea;

    @FXML
    private Label explanationPlaceholder;

    @FXML
    private Label offlineTitle;

    @FXML
    private Label offlineHint;

    public AiAssistantController(
        Nl2SqlSafetyService nl2SqlSafetyService,
        AiModelSelectionService aiModelSelectionService,
        NetworkAiSettingsService networkAiSettingsService,
        AiProviderProfileService profileService,
        AiProviderProbeService probeService,
        AiTaskHistoryService historyService,
        SqlRiskAnalysisService sqlRiskAnalysisService,
        ConnectionManagementService connectionManagementService,
        Consumer<String> fillSqlCallback,
        Runnable switchPageCallback
    ) {
        this.nl2SqlSafetyService = Objects.requireNonNull(
            nl2SqlSafetyService,
            "nl2SqlSafetyService must not be null"
        );
        this.aiModelSelectionService = Objects.requireNonNull(
            aiModelSelectionService,
            "aiModelSelectionService must not be null"
        );
        this.sqlRiskAnalysisService = Objects.requireNonNull(
            sqlRiskAnalysisService,
            "sqlRiskAnalysisService must not be null"
        );
        this.networkAiSettingsService = Objects.requireNonNull(
            networkAiSettingsService,
            "networkAiSettingsService must not be null"
        );
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.probeService = Objects.requireNonNull(probeService, "probeService must not be null");
        this.historyService = Objects.requireNonNull(historyService, "historyService must not be null");
        this.connectionManagementService = Objects.requireNonNull(
            connectionManagementService,
            "connectionManagementService must not be null"
        );
        this.fillSqlCallback = Objects.requireNonNull(fillSqlCallback, "fillSqlCallback must not be null");
        this.switchPageCallback = Objects.requireNonNull(switchPageCallback, "switchPageCallback must not be null");
    }

    @FXML
    private void initialize() {
        log.info("AiAssistantController initialize() called");
        if (copyToPracticeButton == null) {
            log.error("copyToPracticeButton is null, FXML binding failed");
        } else {
            copyToPracticeButton.setDisable(true);
        }
        if (generateButton == null) {
            log.error("generateButton is null, FXML binding failed");
        }
        if (questionInput == null) {
            log.error("questionInput is null, FXML binding failed");
        }
        providerSelector.getItems().setAll(LOCAL_SOURCE, NETWORK_SOURCE);
        refreshProfileSelector();
        applyingProviderSelection = true;
        try {
            boolean networkConfigured = profileService.activeProfile().isPresent();
            providerSelector.setValue(networkConfigured ? NETWORK_SOURCE : LOCAL_SOURCE);
            profileService.activeProfile().ifPresent(this::applyProfile);
        } finally {
            applyingProviderSelection = false;
        }
        updateProviderPanels();
        if (isNetworkSelected()) {
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.6"));
            updateControlAvailability();
        } else {
            refreshModels();
        }
        updateHistoryStatus();
    }

    @FXML
    private void onProviderSelected() {
        if (applyingProviderSelection) return;
        if (LOCAL_SOURCE.equals(providerSelector.getValue())) {
            profileService.deactivate();
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.7"));
            updateProviderPanels();
            refreshModels();
            return;
        }
        updateProviderPanels();
        providerStatusLabel.setText(networkAiSettingsService.current().isPresent()
            ? AppI18n.get("AiAssistantController.8")
            : AppI18n.get("AiAssistantController.9"));
        updateControlAvailability();
    }

    @FXML
    private void onConfigureNetworkAi() {
        String endpoint = networkEndpointField.getText();
        String model = networkModel();
        String keyText = networkApiKeyField.getText();
        String selectedId = selectedProfileId();
        boolean editing = selectedId != null;
        if (endpoint == null || endpoint.isBlank() || model == null || model.isBlank()
            || (!editing && (keyText == null || keyText.isBlank()))) {
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.10"));
            return;
        }
        char[] key = keyText == null ? new char[0] : keyText.toCharArray();
        try {
            String id = editing ? selectedId : "provider-" + UUID.randomUUID();
            String name = networkProfileNameField.getText();
            if (name == null || name.isBlank()) name = AppI18n.get("AiAssistantController.11");
            profileService.save(new AiProviderProfileDraft(id, name, AiProviderKind.OPENAI_COMPATIBLE,
                URI.create(endpoint.trim()), model.trim(), true), key);
            profileService.activate(id);
            refreshProfileSelector();
            selectProfile(id);
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.12"));
            networkApiKeyField.clear();
        } catch (RuntimeException error) {
            providerStatusLabel.setText(error.getMessage() == null ? AppI18n.get("AiAssistantController.13") : error.getMessage());
        } finally {
            Arrays.fill(key, '\0');
            updateControlAvailability();
        }
    }

    @FXML
    private void onUseLocalAi() {
        profileService.deactivate();
        applyingProviderSelection = true;
        try {
            providerSelector.setValue(LOCAL_SOURCE);
        } finally {
            applyingProviderSelection = false;
        }
        updateProviderPanels();
        providerStatusLabel.setText(AppI18n.get("AiAssistantController.14"));
        refreshModels();
    }

    @FXML
    private void onNetworkProfileSelected() {
        if (applyingProfileSelection) return;
        String id = selectedProfileId();
        if (id == null) return;
        profileService.profiles().stream().filter(profile -> profile.id().equals(id)).findFirst().ifPresent(profile -> {
            applyProfile(profile);
            profileService.activate(profile.id());
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.15") + profile.displayName());
            updateControlAvailability();
        });
    }

    @FXML
    private void onDeleteNetworkProfile() {
        String id = selectedProfileId();
        if (id == null) return;
        profileService.remove(id);
        refreshProfileSelector();
        clearNetworkFields();
        providerStatusLabel.setText(AppI18n.get("AiAssistantController.16"));
        updateControlAvailability();
    }

    @FXML
    private void onTestNetworkAi() {
        String endpoint = networkEndpointField.getText();
        String model = networkModel();
        if (endpoint == null || endpoint.isBlank() || model == null || model.isBlank()) {
            providerStatusLabel.setText(AppI18n.get("AiAssistantController.17"));
            return;
        }
        char[] entered = networkApiKeyField.getText() == null ? new char[0] : networkApiKeyField.getText().toCharArray();
        char[] credential = entered;
        if (credential.length == 0 && selectedProfileId() != null) {
            Optional<OpenAiCompatibleConfiguration> stored = profileService.configuration(selectedProfileId());
            if (stored.isPresent()) {
                OpenAiCompatibleConfiguration configuration = stored.get();
                credential = configuration.apiKey();
                configuration.destroy();
            }
        }
        char[] probeKey = credential;
        String name = networkProfileNameField.getText();
        AiProviderProfileDraft draft = new AiProviderProfileDraft(
            selectedProfileId() == null ? "probe" : selectedProfileId(),
            name == null || name.isBlank() ? AppI18n.get("AiAssistantController.18") : name,
            AiProviderKind.OPENAI_COMPATIBLE, URI.create(endpoint.strip()), model.strip(), true);
        providerStatusLabel.setText(AppI18n.get("AiAssistantController.19"));
        Task<AiProviderProbeResult> task = new Task<>() {
            @Override protected AiProviderProbeResult call() { return probeService.probe(draft, probeKey); }
        };
        task.setOnSucceeded(event -> {
            AiProviderProbeResult result = task.getValue();
            providerStatusLabel.setText(result.message());
            if (result.success() && !result.models().isEmpty()) {
                networkModelSelector.getItems().setAll(result.models());
                networkModelSelector.setValue(result.models().contains(model.strip()) ? model.strip() : result.models().get(0));
            }
        });
        task.setOnFailed(event -> providerStatusLabel.setText(AppI18n.get("AiAssistantController.20")));
        DesktopExecutors.background().execute(task);
    }

    @FXML
    private void onRefreshModels() {
        refreshModels();
    }

    @FXML
    private void onModelSelected() {
        if (applyingModelSelection || modelOperationInProgress || modelSelector == null) {
            return;
        }
        String selected = modelSelector.getValue();
        if (selected == null || selected.isBlank()) {
            return;
        }
        runModelOperation(() -> aiModelSelectionService.select(selected), AppI18n.get("AiAssistantController.21"));
    }

    @FXML
    private void onGenerateSql() {
        prepareGeneration(false);
    }

    @FXML
    private void onReviseSql() { prepareGeneration(true); }

    private void prepareGeneration(boolean revision) {
        String question = questionInput == null ? null : questionInput.getText();
        if (question == null || question.isBlank()) { showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.22"), EMPTY_INPUT_MESSAGE); return; }
        if (!hasActiveProvider()) {
            showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.23"),
                isNetworkSelected() ? AppI18n.get("AiAssistantController.24") : AppI18n.get("AiAssistantController.25"));
            return;
        }
        String instruction = revisionInstructionField == null ? "" : revisionInstructionField.getText();
        if (revision && (currentResult == null || !currentResult.draftAvailable() || instruction == null || instruction.isBlank())) {
            showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.26"), AppI18n.get("AiAssistantController.27"));
            return;
        }
        generationInProgress = true;
        updateControlAvailability();
        GlobalLoading.show(AppI18n.get("AiAssistantController.28"));
        Task<PreparedGeneration> previewTask = new Task<>() {
            @Override protected PreparedGeneration call() {
                DatabaseConnectionProfile profile = DesktopConnections.currentProfile(connectionManagementService);
                Nl2SqlRequest request = new Nl2SqlRequest(question, profile.id(), profile.dialect());
                AiContextPreview preview = revision
                    ? nl2SqlSafetyService.previewRevision(request, currentResult.plan(), instruction)
                    : nl2SqlSafetyService.preview(request);
                return new PreparedGeneration(request, preview, revision, instruction, revision ? currentResult.plan() : null);
            }
        };
        activeAiTask = previewTask;
        previewTask.setOnSucceeded(event -> {
            activeAiTask = null;
            GlobalLoading.hide();
            PreparedGeneration prepared = previewTask.getValue();
            showPreview(prepared.preview());
            if (isNetworkSelected() && !confirmNetworkContext(prepared.preview())) {
                generationInProgress = false;
                updateControlAvailability();
                providerStatusLabel.setText(AppI18n.get("AiAssistantController.29"));
                return;
            }
            executeGeneration(prepared);
        });
        previewTask.setOnFailed(event -> { activeAiTask = null; finishGenerationFailure(previewTask.getException()); });
        previewTask.setOnCancelled(event -> finishCancelled());
        DesktopExecutors.background().execute(previewTask);
    }

    private void executeGeneration(PreparedGeneration prepared) {
        GlobalLoading.show(GENERATING_MESSAGE);
        hideOfflineHint();
        Task<Nl2SqlSafetyResult> task = new Task<>() {
            @Override protected Nl2SqlSafetyResult call() {
                return prepared.revision()
                    ? nl2SqlSafetyService.reviseAndAssess(prepared.request(), prepared.previous(), prepared.instruction())
                    : nl2SqlSafetyService.generateAndAssess(prepared.request());
            }
        };
        activeAiTask = task;
        task.setOnSucceeded(event -> {
            activeAiTask = null;
            try {
                Nl2SqlSafetyResult result = task.getValue();
                if (result == null) {
                    displayResult(failureResult(REQUEST_FAILED_MESSAGE));
                    showFailureHint(REQUEST_FAILED_MESSAGE);
                } else if (!result.draftAvailable()) {
                    displayResult(result);
                    showFailureHint(failureMessage(result));
                } else {
                    displayResult(result);
                }
                updateHistoryStatus();
            } catch (RuntimeException error) {
                log.error("Failed to display AI generation result", error);
                displayResult(failureResult(REQUEST_FAILED_MESSAGE));
                showFailureHint(REQUEST_FAILED_MESSAGE);
            } finally { generationInProgress = false; updateControlAvailability(); GlobalLoading.hide(); }
        });
        task.setOnFailed(event -> { activeAiTask = null; finishGenerationFailure(task.getException()); });
        task.setOnCancelled(event -> finishCancelled());
        DesktopExecutors.background().execute(task);
    }

    private void finishGenerationFailure(Throwable error) {
        log.error("AI generation task failed", error);
        displayResult(failureResult(REQUEST_FAILED_MESSAGE));
        showFailureHint(REQUEST_FAILED_MESSAGE);
        generationInProgress = false;
        updateControlAvailability();
        GlobalLoading.forceHide();
    }

    @FXML
    private void onCancelAiTask() {
        Task<?> task = activeAiTask;
        if (task != null) task.cancel(true);
    }

    private void finishCancelled() {
        activeAiTask = null;
        generationInProgress = false;
        updateControlAvailability();
        GlobalLoading.forceHide();
        providerStatusLabel.setText(AppI18n.get("AiAssistantController.30"));
    }

    @FXML
    private void onFavoriteDraft() {
        if (currentResult == null || !currentResult.draftAvailable()) return;
        historyService.recent().stream().findFirst().ifPresent(entry ->
            historyService.favorite(entry.id(), true, currentResult.plan().sqlDraft()));
        updateHistoryStatus();
        historyStatusLabel.setText(historyStatusLabel.getText() + AppI18n.get("AiAssistantController.31"));
    }

    @FXML
    private void onCopyToPractice() {
        log.info("onCopyToPractice() called");
        try {
            String sql = sqlPreviewArea != null ? sqlPreviewArea.getText() : null;
            if (sql != null && !sql.isBlank()) {
                copyAcceptedDraft(sql);
            } else {
                log.warn("No SQL to copy, sqlPreviewArea is empty");
                showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.32"), AppI18n.get("AiAssistantController.33"));
            }
        } catch (Exception e) {
            log.error("Failed to copy SQL to practice page", e);
            showAlert(Alert.AlertType.ERROR, AppI18n.get("AiAssistantController.34"), AppI18n.get("AiAssistantController.35"));
        }
    }

    private void copyAcceptedDraft(String sql) {
        if (!canCopyDraft(currentResult, sql)) {
            showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.36"), AppI18n.get("AiAssistantController.37"));
            return;
        }
        log.info("Copying accepted SQL draft to practice page, sqlLength={}", sql.length());
        fillSqlCallback.accept(sql);
        switchPageCallback.run();
    }

    static boolean canCopyDraft(Nl2SqlSafetyResult result, String displayedSql) {
        return result != null
            && result.accepted()
            && displayedSql != null
            && displayedSql.equals(result.plan().sqlDraft());
    }

    private void displayResult(Nl2SqlSafetyResult result) {
        currentResult = result;
        Nl2SqlPlan plan = result == null ? null : result.plan();

        if (plan == null) {
            log.warn("Plan is null, showing placeholders");
            setPlaceholderVisible(sqlPlaceholder, true);
            setPlaceholderVisible(explanationPlaceholder, true);
            copyToPracticeButton.setDisable(true);
            clearRiskHighlight();
            return;
        }

        boolean hasSql = plan.sqlDraft() != null && !plan.sqlDraft().isBlank();
        boolean hasExplanation = plan.explanation() != null && !plan.explanation().isBlank();

        log.debug("hasSql={}, hasExplanation={}", hasSql, hasExplanation);

        if (hasSql) {
            sqlPreviewArea.setText(plan.sqlDraft());
            setPlaceholderVisible(sqlPlaceholder, false);
            copyToPracticeButton.setDisable(!result.accepted());
            applyRiskHighlight(result.riskAnalysis());
            log.info("SQL draft displayed, length={}", plan.sqlDraft().length());
        } else {
            sqlPreviewArea.clear();
            setPlaceholderVisible(sqlPlaceholder, true);
            copyToPracticeButton.setDisable(true);
            clearRiskHighlight();
            log.warn("SQL draft is empty");
        }

        if (hasExplanation) {
            aiExplanationArea.setText(plan.explanation());
            setPlaceholderVisible(explanationPlaceholder, false);
            log.info("Explanation displayed, length={}", plan.explanation().length());
        } else {
            aiExplanationArea.clear();
            setPlaceholderVisible(explanationPlaceholder, true);
            log.warn("Explanation is empty");
        }
    }

    private void applyRiskHighlight(SqlRiskAnalysis analysis) {
        if (analysis == null) {
            clearRiskHighlight();
            return;
        }

        if (analysis.level() == SqlRiskLevel.HIGH || analysis.level() == SqlRiskLevel.FORBIDDEN
            || !analysis.executable() || analysis.multiStatement()) {
            if (!sqlPreviewArea.getStyleClass().contains("risk-highlight")) {
                sqlPreviewArea.getStyleClass().add("risk-highlight");
            }
        } else {
            clearRiskHighlight();
        }
    }

    private void clearRiskHighlight() {
        sqlPreviewArea.getStyleClass().remove("risk-highlight");
    }

    private Nl2SqlSafetyResult failureResult(String message) {
        Nl2SqlPlan plan = new Nl2SqlPlan("", "", message, "", "");
        return new Nl2SqlSafetyResult(plan, sqlRiskAnalysisService.analyze(""));
    }

    static String failureMessage(Nl2SqlSafetyResult result) {
        if (result == null || result.plan() == null || result.plan().explanation() == null
            || result.plan().explanation().isBlank()) {
            return REQUEST_FAILED_MESSAGE;
        }
        return result.plan().explanation().strip();
    }

    private void showFailureHint(String message) {
        log.info("Showing AI request failure hint");
        if (offlineTitle != null) {
            offlineTitle.setText(AppI18n.get("AiAssistantController.38"));
        }
        if (offlineHint != null) {
            offlineHint.setText(message == null || message.isBlank() ? REQUEST_FAILED_MESSAGE : message);
            Parent parent = offlineHint.getParent();
            if (parent != null) {
                parent.setVisible(true);
                parent.setManaged(true);
            } else {
                log.warn("offlineHint parent is null, cannot show offline hint");
                showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.39"), REQUEST_FAILED_MESSAGE);
            }
        } else {
            log.error("offlineHint is null, cannot show offline hint");
            showAlert(Alert.AlertType.WARNING, AppI18n.get("AiAssistantController.40"), REQUEST_FAILED_MESSAGE);
        }
    }

    private void hideOfflineHint() {
        if (offlineHint != null) {
            Parent parent = offlineHint.getParent();
            if (parent != null) {
                parent.setVisible(false);
                parent.setManaged(false);
            }
        }
    }

    private void refreshModels() {
        if (isNetworkSelected()) {
            updateControlAvailability();
            return;
        }
        runModelOperation(aiModelSelectionService::refresh, AppI18n.get("AiAssistantController.41"));
    }

    private void runModelOperation(
        java.util.concurrent.Callable<AiModelSelection> operation,
        String statusMessage
    ) {
        if (modelOperationInProgress) {
            return;
        }
        modelOperationInProgress = true;
        if (modelStatusLabel != null) {
            modelStatusLabel.setText(statusMessage);
        }
        updateControlAvailability();

        Task<AiModelSelection> task = new Task<>() {
            @Override
            protected AiModelSelection call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(event -> {
            modelOperationInProgress = false;
            applyModelSelection(task.getValue());
            updateControlAvailability();
        });
        task.setOnFailed(event -> {
            modelOperationInProgress = false;
            applyModelSelection(aiModelSelectionService.current());
            updateControlAvailability();
            log.warn("Failed to update Ollama model selection", task.getException());
            if (modelStatusLabel != null) {
                modelStatusLabel.setText(AppI18n.get("AiAssistantController.42"));
            }
        });
        DesktopExecutors.background().execute(task);
    }

    private void applyModelSelection(AiModelSelection selection) {
        if (selection == null || modelSelector == null) {
            return;
        }
        applyingModelSelection = true;
        try {
            modelSelector.getItems().setAll(selection.installedModels());
            modelSelector.setValue(selection.hasSelection() ? selection.selectedModel() : null);
            if (modelStatusLabel != null) {
                modelStatusLabel.setText(selection.hasSelection()
                    ? AppI18n.get("AiAssistantController.43") + selection.selectedModel()
                    : AppI18n.get("AiAssistantController.44"));
            }
        } finally {
            applyingModelSelection = false;
        }
    }

    private void updateControlAvailability() {
        boolean networkSelected = isNetworkSelected();
        boolean hasModel = hasActiveProvider();
        if (generateButton != null) {
            generateButton.setDisable(modelOperationInProgress || generationInProgress || !hasModel);
        }
        if (modelSelector != null) {
            modelSelector.setDisable(networkSelected || modelOperationInProgress || generationInProgress);
        }
        if (refreshModelsButton != null) {
            refreshModelsButton.setDisable(networkSelected || modelOperationInProgress || generationInProgress);
        }
        if (reviseButton != null) reviseButton.setDisable(generationInProgress || currentResult == null || !currentResult.draftAvailable());
        if (favoriteDraftButton != null) favoriteDraftButton.setDisable(currentResult == null || !currentResult.draftAvailable());
        if (cancelAiButton != null) cancelAiButton.setDisable(!generationInProgress);
    }

    private boolean hasActiveProvider() {
        return isNetworkSelected()
            ? networkAiSettingsService.current().isPresent()
            : aiModelSelectionService.current().hasSelection();
    }

    private boolean isNetworkSelected() {
        return providerSelector != null && NETWORK_SOURCE.equals(providerSelector.getValue());
    }

    private void updateProviderPanels() {
        boolean network = isNetworkSelected();
        localProviderPane.setVisible(!network);
        localProviderPane.setManaged(!network);
        networkProviderPane.setVisible(network);
        networkProviderPane.setManaged(network);
    }

    private void refreshProfileSelector() {
        if (networkProfileSelector == null) return;
        applyingProfileSelection = true;
        try {
            networkProfileSelector.getItems().setAll(profileService.profiles().stream()
                .map(profile -> profile.displayName() + " [" + profile.id() + "]").toList());
            profileService.activeProfile().ifPresent(profile -> selectProfile(profile.id()));
        } finally { applyingProfileSelection = false; }
    }

    private void selectProfile(String id) {
        if (networkProfileSelector == null) return;
        profileService.profiles().stream().filter(profile -> profile.id().equals(id)).findFirst().ifPresent(profile ->
            networkProfileSelector.setValue(profile.displayName() + " [" + profile.id() + "]"));
    }

    private String selectedProfileId() {
        if (networkProfileSelector == null || networkProfileSelector.getValue() == null) return null;
        String value = networkProfileSelector.getValue();
        int start = value.lastIndexOf('[');
        return start >= 0 && value.endsWith("]") ? value.substring(start + 1, value.length() - 1) : null;
    }

    private void applyProfile(AiProviderProfile profile) {
        networkProfileNameField.setText(profile.displayName());
        networkEndpointField.setText(profile.endpoint().toString());
        networkModelSelector.setValue(profile.model());
        selectProfile(profile.id());
    }

    private void clearNetworkFields() {
        networkProfileNameField.clear();
        networkEndpointField.clear();
        networkModelSelector.getItems().clear();
        networkModelSelector.setValue(null);
        networkApiKeyField.clear();
    }

    private void showPreview(AiContextPreview preview) {
        if (privacyPreviewLabel == null) return;
        String redactions = preview.redactions().isEmpty() ? AppI18n.get("AiAssistantController.45") : String.join(AppI18n.get("AiAssistantController.46"), preview.redactions());
        privacyPreviewLabel.setText(AppI18n.get("AiAssistantController.47") + preview.characterCount() + AppI18n.get("AiAssistantController.48")
            + preview.categories() + AppI18n.get("AiAssistantController.49") + String.join(AppI18n.get("AiAssistantController.50"), preview.sources()) + AppI18n.get("AiAssistantController.51") + redactions);
    }

    private String networkModel() {
        if (networkModelSelector == null) return "";
        String editorText = networkModelSelector.isEditable() ? networkModelSelector.getEditor().getText() : null;
        return editorText != null && !editorText.isBlank() ? editorText : networkModelSelector.getValue();
    }

    private boolean confirmNetworkContext(AiContextPreview preview) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(AppI18n.get("AiAssistantController.52"));
        alert.setHeaderText(AppI18n.get("AiAssistantController.53") + preview.characterCount() + AppI18n.get("AiAssistantController.54"));
        alert.setContentText(AppI18n.get("AiAssistantController.55") + preview.categories() + AppI18n.get("AiAssistantController.56") + String.join(AppI18n.get("AiAssistantController.57"), preview.sources())
            + AppI18n.get("AiAssistantController.58"));
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void updateHistoryStatus() {
        if (historyStatusLabel == null) return;
        long favorites = historyService.recent().stream().filter(AiTaskHistoryEntry::favorite).count();
        historyStatusLabel.setText(AppI18n.get("AiAssistantController.59") + historyService.requestsToday() + AppI18n.get("AiAssistantController.60")
            + historyService.recent().size() + AppI18n.get("AiAssistantController.61") + favorites + AppI18n.get("AiAssistantController.62"));
    }

    private static void setPlaceholderVisible(Label label, boolean visible) {
        if (label != null) {
            label.setVisible(visible);
            label.setManaged(visible);
        }
    }

    private static void showAlert(Alert.AlertType type, String title, String message) {
        try {
            Runnable show = () -> {
                Alert alert = new Alert(type);
                alert.setTitle(title);
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.showAndWait();
            };
            if (Platform.isFxApplicationThread()) {
                show.run();
            } else {
                Platform.runLater(show);
            }
        } catch (Exception e) {
            log.error("Failed to show alert", e);
        }
    }

    private record PreparedGeneration(Nl2SqlRequest request, AiContextPreview preview,
                                      boolean revision, String instruction, Nl2SqlPlan previous) { }
}
