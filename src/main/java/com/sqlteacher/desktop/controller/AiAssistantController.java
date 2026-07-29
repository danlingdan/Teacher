package com.sqlteacher.desktop.controller;

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

    private static final String GENERATING_MESSAGE = "AI 正在生成 SQL…";
    private static final String REQUEST_FAILED_MESSAGE = "AI 请求未完成，请检查 Provider 配置后重试";
    private static final String EMPTY_INPUT_MESSAGE = "请输入自然语言提问";
    private static final String LOCAL_SOURCE = "本地 Ollama";
    private static final String NETWORK_SOURCE = "网络 AI";

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
            providerStatusLabel.setText("当前使用网络 AI；API Key 已由 Windows DPAPI 加密保存在本机当前账户");
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
            providerStatusLabel.setText("当前使用本地 Ollama");
            updateProviderPanels();
            refreshModels();
            return;
        }
        updateProviderPanels();
        providerStatusLabel.setText(networkAiSettingsService.current().isPresent()
            ? "当前使用网络 AI；API Key 已由 Windows DPAPI 加密保存"
            : "请输入 HTTPS 接口、模型名称和 API Key 后启用网络 AI");
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
            providerStatusLabel.setText("请填写接口、模型；新 Profile 还必须填写 API Key");
            return;
        }
        char[] key = keyText == null ? new char[0] : keyText.toCharArray();
        try {
            String id = editing ? selectedId : "provider-" + UUID.randomUUID();
            String name = networkProfileNameField.getText();
            if (name == null || name.isBlank()) name = "网络 Provider";
            profileService.save(new AiProviderProfileDraft(id, name, AiProviderKind.OPENAI_COMPATIBLE,
                URI.create(endpoint.trim()), model.trim(), true), key);
            profileService.activate(id);
            refreshProfileSelector();
            selectProfile(id);
            providerStatusLabel.setText("Provider 已加密保存并启用；所有 SQL 草稿仍会经过本地安全检查");
            networkApiKeyField.clear();
        } catch (RuntimeException error) {
            providerStatusLabel.setText(error.getMessage() == null ? "网络 AI 配置无效" : error.getMessage());
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
        providerStatusLabel.setText("已切换回本地 Ollama");
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
            providerStatusLabel.setText("已启用 Profile：" + profile.displayName());
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
        providerStatusLabel.setText("已删除 Profile 及其 DPAPI 凭据");
        updateControlAvailability();
    }

    @FXML
    private void onTestNetworkAi() {
        String endpoint = networkEndpointField.getText();
        String model = networkModel();
        if (endpoint == null || endpoint.isBlank() || model == null || model.isBlank()) {
            providerStatusLabel.setText("请先填写接口和模型名称");
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
            name == null || name.isBlank() ? "网络 Provider" : name,
            AiProviderKind.OPENAI_COMPATIBLE, URI.create(endpoint.strip()), model.strip(), true);
        providerStatusLabel.setText("正在测试连接并发现模型…");
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
        task.setOnFailed(event -> providerStatusLabel.setText("连接测试失败，请检查地址、证书和网络"));
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
        runModelOperation(() -> aiModelSelectionService.select(selected), "正在保存模型选择…");
    }

    @FXML
    private void onGenerateSql() {
        prepareGeneration(false);
    }

    @FXML
    private void onReviseSql() { prepareGeneration(true); }

    private void prepareGeneration(boolean revision) {
        String question = questionInput == null ? null : questionInput.getText();
        if (question == null || question.isBlank()) { showAlert(Alert.AlertType.WARNING, "提示", EMPTY_INPUT_MESSAGE); return; }
        if (!hasActiveProvider()) {
            showAlert(Alert.AlertType.WARNING, "未配置 AI 模型",
                isNetworkSelected() ? "请先填写并启用网络 AI" : "请先启动 Ollama、安装模型并刷新模型列表");
            return;
        }
        String instruction = revisionInstructionField == null ? "" : revisionInstructionField.getText();
        if (revision && (currentResult == null || !currentResult.draftAvailable() || instruction == null || instruction.isBlank())) {
            showAlert(Alert.AlertType.WARNING, "无法修订", "请先生成 SQL 草稿并填写修订要求");
            return;
        }
        generationInProgress = true;
        updateControlAvailability();
        GlobalLoading.show("正在整理最小必要上下文…");
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
                providerStatusLabel.setText("已取消网络 AI 调用，输入内容保持不变");
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
        providerStatusLabel.setText("AI 请求已取消，输入和上一版草稿均已保留");
    }

    @FXML
    private void onFavoriteDraft() {
        if (currentResult == null || !currentResult.draftAvailable()) return;
        historyService.recent().stream().findFirst().ifPresent(entry ->
            historyService.favorite(entry.id(), true, currentResult.plan().sqlDraft()));
        updateHistoryStatus();
        historyStatusLabel.setText(historyStatusLabel.getText() + "；当前草稿已收藏到本机");
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
                showAlert(Alert.AlertType.WARNING, "提示", "没有可复制的 SQL");
            }
        } catch (Exception e) {
            log.error("Failed to copy SQL to practice page", e);
            showAlert(Alert.AlertType.ERROR, "错误", "复制失败，请重试");
        }
    }

    private void copyAcceptedDraft(String sql) {
        if (!canCopyDraft(currentResult, sql)) {
            showAlert(Alert.AlertType.WARNING, "安全检查未通过", "该 SQL 草案被本地安全规则禁止，不能复制到练习页");
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
            offlineTitle.setText("AI 请求未完成");
        }
        if (offlineHint != null) {
            offlineHint.setText(message == null || message.isBlank() ? REQUEST_FAILED_MESSAGE : message);
            Parent parent = offlineHint.getParent();
            if (parent != null) {
                parent.setVisible(true);
                parent.setManaged(true);
            } else {
                log.warn("offlineHint parent is null, cannot show offline hint");
                showAlert(Alert.AlertType.WARNING, "AI 请求未完成", REQUEST_FAILED_MESSAGE);
            }
        } else {
            log.error("offlineHint is null, cannot show offline hint");
            showAlert(Alert.AlertType.WARNING, "AI 请求未完成", REQUEST_FAILED_MESSAGE);
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
        runModelOperation(aiModelSelectionService::refresh, "正在检测本地 Ollama 模型…");
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
                modelStatusLabel.setText("模型检测失败，请确认 Ollama 已启动");
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
                    ? "当前模型：" + selection.selectedModel()
                    : "未检测到已安装模型");
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
        String redactions = preview.redactions().isEmpty() ? "无额外删减" : String.join("；", preview.redactions());
        privacyPreviewLabel.setText("发送预览：" + preview.characterCount() + " 字符；类别 "
            + preview.categories() + "；来源 " + String.join("、", preview.sources()) + "；" + redactions);
    }

    private String networkModel() {
        if (networkModelSelector == null) return "";
        String editorText = networkModelSelector.isEditable() ? networkModelSelector.getEditor().getText() : null;
        return editorText != null && !editorText.isBlank() ? editorText : networkModelSelector.getValue();
    }

    private boolean confirmNetworkContext(AiContextPreview preview) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认发送最小必要上下文");
        alert.setHeaderText("将向当前网络 Provider 发送 " + preview.characterCount() + " 个上下文字符（另含固定安全指令）");
        alert.setContentText("数据类别：" + preview.categories() + "\n来源：" + String.join("、", preview.sources())
            + "\n不会发送 API Key、学生账号或数据库样本行。是否继续？");
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    private void updateHistoryStatus() {
        if (historyStatusLabel == null) return;
        long favorites = historyService.recent().stream().filter(AiTaskHistoryEntry::favorite).count();
        historyStatusLabel.setText("今日设备调用 " + historyService.requestsToday() + " 次；最近记录 "
            + historyService.recent().size() + " 条；收藏 " + favorites + " 条（默认仅保存脱敏元数据）");
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
