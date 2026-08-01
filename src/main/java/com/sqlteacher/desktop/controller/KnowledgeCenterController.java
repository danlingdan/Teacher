package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseSummary;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeDetail;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import com.sqlteacher.application.knowledge.WebSearchProvider;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class KnowledgeCenterController {
    private final KnowledgeDocumentService documentService;
    private final CourseKnowledgeService knowledgeService;
    private final GroundedKnowledgeExplanationService explanationService;
    private final HybridKnowledgeRetrievalService retrievalService;
    private final KnowledgeIndexService indexService;
    private final KnowledgeReadStateService readStateService;
    private final WebSearchProvider webSearchProvider;
    private final SafeWebContentFetcher webContentFetcher;
    private final ExerciseCatalogService exerciseCatalogService;
    private final Consumer<String> openExercise;
    private final ApplicationExceptionMapper exceptionMapper;
    private final boolean authoringAllowed;

    @FXML private TextField courseField;
    @FXML private TextField sectionField;
    @FXML private TextField knowledgePointsField;
    @FXML private TextField queryField;
    @FXML private CheckBox includePrivateCheck;
    @FXML private Button importButton;
    @FXML private Button reviseButton;
    @FXML private Button publishButton;
    @FXML private Button privateButton;
    @FXML private Button inactivateButton;
    @FXML private Button deleteButton;
    @FXML private Label statusLabel;
    @FXML private Label indexStatusLabel;
    @FXML private TableView<CourseKnowledgeArticle> articleTable;
    @FXML private TableColumn<CourseKnowledgeArticle, String> articleTitleColumn;
    @FXML private TableColumn<CourseKnowledgeArticle, String> courseColumn;
    @FXML private TableColumn<CourseKnowledgeArticle, String> sectionColumn;
    @FXML private TableColumn<CourseKnowledgeArticle, String> visibilityColumn;
    @FXML private TableColumn<CourseKnowledgeArticle, String> revisionColumn;
    @FXML private TableView<KnowledgeSearchResult> resultTable;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultTitleColumn;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultSourceColumn;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultSnippetColumn;
    @FXML private TableView<ExerciseSummary> exerciseTable;
    @FXML private TableColumn<ExerciseSummary, String> exerciseTitleColumn;
    @FXML private TableColumn<ExerciseSummary, String> exercisePointColumn;
    @FXML private TextArea contentArea;
    @FXML private TextArea answerArea;

    public KnowledgeCenterController(
        KnowledgeDocumentService documentService,
        CourseKnowledgeService knowledgeService,
        GroundedKnowledgeExplanationService explanationService,
        HybridKnowledgeRetrievalService retrievalService,
        KnowledgeIndexService indexService,
        KnowledgeReadStateService readStateService,
        WebSearchProvider webSearchProvider,
        SafeWebContentFetcher webContentFetcher,
        ExerciseCatalogService exerciseCatalogService,
        Consumer<String> openExercise,
        boolean authoringAllowed,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.documentService = Objects.requireNonNull(documentService);
        this.knowledgeService = Objects.requireNonNull(knowledgeService);
        this.explanationService = Objects.requireNonNull(explanationService);
        this.retrievalService = Objects.requireNonNull(retrievalService);
        this.indexService = Objects.requireNonNull(indexService);
        this.readStateService = Objects.requireNonNull(readStateService);
        this.webSearchProvider = Objects.requireNonNull(webSearchProvider);
        this.webContentFetcher = Objects.requireNonNull(webContentFetcher);
        this.exerciseCatalogService = Objects.requireNonNull(exerciseCatalogService);
        this.openExercise = Objects.requireNonNull(openExercise);
        this.authoringAllowed = authoringAllowed;
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        articleTitleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        courseColumn.setCellValueFactory(cell -> text(cell.getValue().courseTitle()));
        sectionColumn.setCellValueFactory(cell -> text(cell.getValue().sectionTitle()));
        visibilityColumn.setCellValueFactory(cell -> text(visibilityLabel(cell.getValue().visibility())));
        revisionColumn.setCellValueFactory(cell -> text("v" + cell.getValue().currentRevision()));
        resultTitleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        resultSourceColumn.setCellValueFactory(cell -> text(cell.getValue().sourceName()));
        resultSnippetColumn.setCellValueFactory(cell -> text(cell.getValue().snippet()));
        exerciseTitleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        exercisePointColumn.setCellValueFactory(cell -> text(cell.getValue().knowledgePoint()));
        articleTable.getSelectionModel().selectedItemProperty().addListener((ignored, oldValue, selected) -> loadDetail(selected));
        queryField.setOnAction(event -> onSearch());
        List.of(importButton, reviseButton, publishButton, privateButton, inactivateButton, deleteButton)
            .forEach(button -> button.setDisable(!authoringAllowed));
        includePrivateCheck.setSelected(authoringAllowed);
        includePrivateCheck.setDisable(!authoringAllowed);
        refreshIndexStatus();
        refreshArticles("正在加载课程知识库…");
    }

    @FXML
    private void onImport() {
        String course = field(courseField);
        String section = field(sectionField);
        if (course.isBlank() || section.isBlank()) {
            showStatus("请先填写课程与章节，再导入资料。", true);
            return;
        }
        FileChooser chooser = knowledgeFileChooser("批量导入课程知识文档");
        List<File> files = chooser.showOpenMultipleDialog(importButton.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        List<String> points = knowledgePoints();
        GlobalLoading.show("正在建立课程知识版本与本地索引…");
        DesktopExecutors.background().execute(() -> {
            try {
                List<String> imported = new ArrayList<>();
                for (File file : files) {
                    imported.add(knowledgeService.importArticle(file.toPath(), course, section, points).title());
                }
                KnowledgeIndexService.IndexReport indexReport = indexService.rebuildPending();
                List<CourseKnowledgeArticle> articles = visibleArticles();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    articleTable.getItems().setAll(articles);
                    refreshIndexStatus();
                    showStatus("已导入 " + imported.size() + " 份资料，默认保持私有草稿。" + indexReport.message(), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onRevise() {
        CourseKnowledgeArticle selected = selectedArticle("请先选择要更新版本的知识条目。");
        if (selected == null) return;
        FileChooser chooser = knowledgeFileChooser("选择新版本文档");
        File file = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (file == null) return;
        runArticleChange("正在建立新版本…",
            () -> knowledgeService.reviseArticle(selected.id(), file.toPath(), knowledgePoints()),
            "已生成新版本；旧版本仍保留在历史记录中。");
    }

    @FXML private void onPublish() { changeVisibility(KnowledgeVisibility.PUBLISHED); }
    @FXML private void onMakePrivate() { changeVisibility(KnowledgeVisibility.PRIVATE); }
    @FXML private void onInactivate() { changeVisibility(KnowledgeVisibility.INACTIVE); }

    @FXML
    private void onDelete() {
        CourseKnowledgeArticle selected = selectedArticle("请先选择要删除的知识条目。");
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "条目、全部历史版本与检索片段都将从本机删除。", ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("删除课程知识");
        confirm.setHeaderText("确认删除《" + selected.title() + "》？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        GlobalLoading.show("正在删除知识条目…");
        DesktopExecutors.background().execute(() -> {
            try {
                documentService.deleteDocument(selected.documentId());
                List<CourseKnowledgeArticle> articles = visibleArticles();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    articleTable.getItems().setAll(articles);
                    clearDetail();
                    showStatus("知识条目与全部本地索引已删除。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onSearch() {
        String query = field(queryField);
        if (query.isBlank()) {
            showStatus("请输入课程知识问题或关键词。", true);
            return;
        }
        GlobalLoading.show("正在按课程、章节与知识点检索…");
        DesktopExecutors.background().execute(() -> {
            try {
                HybridKnowledgeRetrievalService.RetrievalResponse response = retrievalService.retrieve(query, currentFilter(), 20);
                List<KnowledgeSearchResult> results = response.results();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    resultTable.getItems().setAll(results);
                    showStatus(results.isEmpty() ? "未找到符合筛选条件的资料。" : "找到 " + results.size()
                        + " 个可追溯片段（" + response.mode() + "）。" + (response.degraded() ? response.message() : ""), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onWebSearch() {
        String query = field(queryField);
        if (query.isBlank()) { showStatus("请输入需要联网查找的问题。", true); return; }
        if (!webSearchProvider.enabled()) {
            showStatus("联网知识搜索默认关闭；请通过 SQLTEACHER_BRAVE_API_KEY 配置 Brave Search 后重启。", true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            "将把以下查询发送给 Brave Search：\n\n" + query, ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("确认联网知识搜索"); confirm.setHeaderText("联网结果不是课程知识库内容，请独立核验来源");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        GlobalLoading.show("正在安全获取联网知识来源…");
        DesktopExecutors.background().execute(() -> {
            try {
                List<WebSearchProvider.WebSearchResult> searchResults = webSearchProvider.search(query, 5);
                List<KnowledgeSearchResult> results = new ArrayList<>();
                for (int index = 0; index < searchResults.size(); index++) {
                    WebSearchProvider.WebSearchResult item = searchResults.get(index);
                    String snippet = item.snippet();
                    if (index < 3) {
                        try { snippet = summarize(webContentFetcher.fetch(item.uri()).text()); }
                        catch (RuntimeException ignored) { /* Search snippet remains visible and attributed. */ }
                    }
                    if (snippet.isBlank()) snippet = "仅提供来源链接，请打开后核验。";
                    results.add(new KnowledgeSearchResult("web:" + Integer.toUnsignedString(item.uri().hashCode()), item.title(),
                        item.uri().toString(), index, snippet, Math.max(0, searchResults.size() - index)));
                }
                Platform.runLater(() -> {
                    GlobalLoading.hide(); resultTable.getItems().setAll(results);
                    showStatus(results.isEmpty() ? "联网搜索未返回结果。" : "已获取 " + results.size() + " 个联网来源；未写入课程知识库。", false);
                });
            } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
        });
    }

    @FXML
    private void onRebuildIndex() {
        GlobalLoading.show("正在重建本地向量索引…");
        DesktopExecutors.background().execute(() -> {
            try {
                KnowledgeIndexService.IndexReport report = indexService.rebuildAll();
                Platform.runLater(() -> { GlobalLoading.hide(); refreshIndexStatus(); showStatus(report.message(), report.failedJobs() > 0); });
            } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
        });
    }

    @FXML
    private void onMarkRead() {
        CourseKnowledgeArticle selected = selectedArticle("请先选择要标记已读的知识条目。");
        if (selected == null) return;
        readStateService.save(selected.id(), selected.currentRevision(), 100);
        showStatus("已记录当前版本的阅读进度。", false);
    }

    @FXML
    private void onExplain() {
        String question = field(queryField);
        if (question.isBlank()) {
            showStatus("请先输入要解释的问题。", true);
            return;
        }
        try {
            AiContextPreview preview = explanationService.preview(question, currentFilter());
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将发送 " + preview.characterCount() + " 个字符；来源：\n" + String.join("\n", preview.sources()),
                ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle("确认 AI 上下文");
            confirm.setHeaderText("仅发送学生问题与本次检索到的课程知识片段");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        } catch (Throwable error) {
            fail(error);
            return;
        }
        GlobalLoading.show("正在生成带引用的课程解释…");
        DesktopExecutors.background().execute(() -> {
            try {
                GroundedKnowledgeAnswer answer = explanationService.explain(question, currentFilter());
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    answerArea.setText(formatAnswer(answer));
                    showStatus(answer.message(), !answer.aiGenerated());
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onOpenExercise() {
        ExerciseSummary selected = exerciseTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择关联练习。", true);
            return;
        }
        openExercise.accept(selected.id());
    }

    @FXML private void onRefresh() { refreshArticles("正在刷新课程知识…"); }

    public void focusKnowledgePoint(String knowledgePoint) {
        if (knowledgePoint == null || knowledgePoint.isBlank()) return;
        knowledgePointsField.setText(knowledgePoint.trim());
        queryField.setText(knowledgePoint.trim());
        onSearch();
    }

    private void loadDetail(CourseKnowledgeArticle selected) {
        if (selected == null) {
            clearDetail();
            return;
        }
        GlobalLoading.show("正在读取知识详情…");
        DesktopExecutors.background().execute(() -> {
            try {
                CourseKnowledgeDetail detail = knowledgeService.getArticle(selected.id());
                List<ExerciseSummary> exercises = exerciseCatalogService.listAvailableExercises().stream()
                    .filter(exercise -> selected.knowledgePoints().stream()
                        .anyMatch(point -> point.equalsIgnoreCase(exercise.knowledgePoint())))
                    .toList();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    courseField.setText(selected.courseTitle());
                    sectionField.setText(selected.sectionTitle());
                    knowledgePointsField.setText(String.join(", ", selected.knowledgePoints()));
                    contentArea.setText("当前版本 v" + selected.currentRevision() + "｜历史版本 " + detail.history().size()
                        + "\n\n" + detail.revision().content());
                    exerciseTable.getItems().setAll(exercises);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void changeVisibility(KnowledgeVisibility visibility) {
        CourseKnowledgeArticle selected = selectedArticle("请先选择知识条目。");
        if (selected == null) return;
        runArticleChange("正在更新发布状态…", () -> knowledgeService.changeVisibility(selected.id(), visibility),
            "发布状态已更新为：" + visibilityLabel(visibility) + "。");
    }

    private void runArticleChange(String loading, ArticleChange change, String successMessage) {
        GlobalLoading.show(loading);
        DesktopExecutors.background().execute(() -> {
            try {
                CourseKnowledgeArticle changed = change.run();
                KnowledgeIndexService.IndexReport indexReport = indexService.rebuildPending();
                List<CourseKnowledgeArticle> articles = visibleArticles();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    articleTable.getItems().setAll(articles);
                    articleTable.getSelectionModel().select(changed);
                    refreshIndexStatus();
                    showStatus(successMessage + indexReport.message(), indexReport.failedJobs() > 0);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void refreshArticles(String loadingText) {
        GlobalLoading.show(loadingText);
        DesktopExecutors.background().execute(() -> {
            try {
                List<CourseKnowledgeArticle> articles = visibleArticles();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    articleTable.getItems().setAll(articles);
                    showStatus(articles.isEmpty() ? "暂无课程知识，可批量导入 UTF-8 文本或 Markdown。"
                        : "已加载 " + articles.size() + " 个课程知识条目。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private CourseKnowledgeSearchFilter currentFilter() {
        return new CourseKnowledgeSearchFilter(field(courseField), field(sectionField), firstKnowledgePoint(),
            authoringAllowed && includePrivateCheck.isSelected());
    }

    private List<CourseKnowledgeArticle> visibleArticles() {
        return knowledgeService.listArticles().stream()
            .filter(article -> authoringAllowed || article.visibility() == KnowledgeVisibility.PUBLISHED)
            .toList();
    }

    private List<String> knowledgePoints() {
        return List.of(field(knowledgePointsField).split("[,，;；\\n]")).stream()
            .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
    }

    private String firstKnowledgePoint() {
        List<String> points = knowledgePoints();
        return points.isEmpty() ? "" : points.get(0);
    }

    private CourseKnowledgeArticle selectedArticle(String message) {
        CourseKnowledgeArticle selected = articleTable.getSelectionModel().getSelectedItem();
        if (selected == null) showStatus(message, true);
        return selected;
    }

    private static FileChooser knowledgeFileChooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("课程资料", "*.txt", "*.md", "*.markdown", "*.pdf", "*.docx"));
        return chooser;
    }

    private static String formatAnswer(GroundedKnowledgeAnswer answer) {
        StringBuilder text = new StringBuilder(answer.answer());
        if (!answer.citations().isEmpty()) text.append("\n\n引用：");
        for (GroundedKnowledgeAnswer.Citation citation : answer.citations()) {
            text.append("\n[").append(citation.number()).append("] ").append(citation.articleTitle())
                .append(" v").append(citation.revision()).append("，片段 ").append(citation.chunkIndex() + 1);
        }
        return text.toString();
    }

    private void clearDetail() {
        contentArea.clear();
        answerArea.clear();
        exerciseTable.getItems().clear();
        resultTable.getItems().clear();
    }

    private void fail(Throwable error) {
        GlobalLoading.hide();
        showStatus(exceptionMapper.map(error).userMessage(), true);
    }

    private void showStatus(String message, boolean error) {
        statusLabel.setText(message);
        statusLabel.getStyleClass().removeAll("sql-result-hint", "sql-error-hint");
        statusLabel.getStyleClass().add(error ? "sql-error-hint" : "sql-result-hint");
        statusLabel.setVisible(true);
        statusLabel.setManaged(true);
    }

    private void refreshIndexStatus() {
        KnowledgeIndexService.IndexStatus status = indexService.status();
        indexStatusLabel.setText("索引：" + status.mode() + "｜待处理 " + status.pendingJobs()
            + "｜已索引 " + status.indexedChunks() + "｜失败 " + status.failedChunks());
    }

    private static String summarize(String value) {
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 497) + "…";
    }

    private static String field(TextField field) { return field.getText() == null ? "" : field.getText().trim(); }
    private static SimpleStringProperty text(Object value) { return new SimpleStringProperty(String.valueOf(value)); }
    private static String visibilityLabel(KnowledgeVisibility value) {
        return switch (value) { case PRIVATE -> "私有"; case PUBLISHED -> "已发布"; case INACTIVE -> "已停用"; };
    }

    @FunctionalInterface
    private interface ArticleChange { CourseKnowledgeArticle run(); }
}
