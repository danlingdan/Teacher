package com.sqlteacher.desktop.controller;
import com.sqlteacher.desktop.AppI18n;

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
import com.sqlteacher.application.planning.GroundedTutorService;
import com.sqlteacher.application.planning.TutorFeedbackType;
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
    private final GroundedTutorService tutorService;
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
    @FXML private TextField objectiveField;
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
    private String lastTutorSessionId = "";

    public KnowledgeCenterController(
        KnowledgeDocumentService documentService,
        CourseKnowledgeService knowledgeService,
        GroundedKnowledgeExplanationService explanationService,
        GroundedTutorService tutorService,
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
        Objects.requireNonNull(explanationService);
        this.tutorService = Objects.requireNonNull(tutorService);
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
        refreshArticles(AppI18n.get("KnowledgeCenterController.1"));
    }

    @FXML
    private void onImport() {
        String course = field(courseField);
        String section = field(sectionField);
        if (course.isBlank() || section.isBlank()) {
            showStatus(AppI18n.get("KnowledgeCenterController.2"), true);
            return;
        }
        FileChooser chooser = knowledgeFileChooser(AppI18n.get("KnowledgeCenterController.3"));
        List<File> files = chooser.showOpenMultipleDialog(importButton.getScene().getWindow());
        if (files == null || files.isEmpty()) {
            return;
        }
        List<String> points = knowledgePoints();
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.4"));
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
                    showStatus(AppI18n.get("KnowledgeCenterController.5") + imported.size() + AppI18n.get("KnowledgeCenterController.6") + indexReport.message(), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onRevise() {
        CourseKnowledgeArticle selected = selectedArticle(AppI18n.get("KnowledgeCenterController.7"));
        if (selected == null) return;
        FileChooser chooser = knowledgeFileChooser(AppI18n.get("KnowledgeCenterController.8"));
        File file = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (file == null) return;
        runArticleChange(AppI18n.get("KnowledgeCenterController.9"),
            () -> knowledgeService.reviseArticle(selected.id(), file.toPath(), knowledgePoints()),
            AppI18n.get("KnowledgeCenterController.10"));
    }

    @FXML private void onPublish() { changeVisibility(KnowledgeVisibility.PUBLISHED); }
    @FXML private void onMakePrivate() { changeVisibility(KnowledgeVisibility.PRIVATE); }
    @FXML private void onInactivate() { changeVisibility(KnowledgeVisibility.INACTIVE); }

    @FXML
    private void onDelete() {
        CourseKnowledgeArticle selected = selectedArticle(AppI18n.get("KnowledgeCenterController.11"));
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            AppI18n.get("KnowledgeCenterController.12"), ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle(AppI18n.get("KnowledgeCenterController.13"));
        confirm.setHeaderText(AppI18n.get("KnowledgeCenterController.14") + selected.title() + AppI18n.get("KnowledgeCenterController.15"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.16"));
        DesktopExecutors.background().execute(() -> {
            try {
                documentService.deleteDocument(selected.documentId());
                List<CourseKnowledgeArticle> articles = visibleArticles();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    articleTable.getItems().setAll(articles);
                    clearDetail();
                    showStatus(AppI18n.get("KnowledgeCenterController.17"), false);
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
            showStatus(AppI18n.get("KnowledgeCenterController.18"), true);
            return;
        }
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.19"));
        DesktopExecutors.background().execute(() -> {
            try {
                HybridKnowledgeRetrievalService.RetrievalResponse response = retrievalService.retrieve(query, currentFilter(), 20);
                List<KnowledgeSearchResult> results = response.results();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    resultTable.getItems().setAll(results);
                    showStatus(results.isEmpty() ? AppI18n.get("KnowledgeCenterController.20") : AppI18n.get("KnowledgeCenterController.21") + results.size()
                        + AppI18n.get("KnowledgeCenterController.22") + response.mode() + AppI18n.get("KnowledgeCenterController.23") + (response.degraded() ? response.message() : ""), false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onWebSearch() {
        String query = field(queryField);
        if (query.isBlank()) { showStatus(AppI18n.get("KnowledgeCenterController.24"), true); return; }
        if (!webSearchProvider.enabled()) {
            showStatus(AppI18n.get("KnowledgeCenterController.25"), true);
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
            AppI18n.get("KnowledgeCenterController.26") + query, ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle(AppI18n.get("KnowledgeCenterController.27")); confirm.setHeaderText(AppI18n.get("KnowledgeCenterController.28"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.29"));
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
                    if (snippet.isBlank()) snippet = AppI18n.get("KnowledgeCenterController.30");
                    results.add(new KnowledgeSearchResult("web:" + Integer.toUnsignedString(item.uri().hashCode()), item.title(),
                        item.uri().toString(), index, snippet, Math.max(0, searchResults.size() - index)));
                }
                Platform.runLater(() -> {
                    GlobalLoading.hide(); resultTable.getItems().setAll(results);
                    showStatus(results.isEmpty() ? AppI18n.get("KnowledgeCenterController.31") : AppI18n.get("KnowledgeCenterController.32") + results.size() + AppI18n.get("KnowledgeCenterController.33"), false);
                });
            } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
        });
    }

    @FXML
    private void onRebuildIndex() {
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.34"));
        DesktopExecutors.background().execute(() -> {
            try {
                KnowledgeIndexService.IndexReport report = indexService.rebuildAll();
                Platform.runLater(() -> { GlobalLoading.hide(); refreshIndexStatus(); showStatus(report.message(), report.failedJobs() > 0); });
            } catch (Throwable error) { Platform.runLater(() -> fail(error)); }
        });
    }

    @FXML
    private void onMarkRead() {
        CourseKnowledgeArticle selected = selectedArticle(AppI18n.get("KnowledgeCenterController.35"));
        if (selected == null) return;
        readStateService.save(selected.id(), selected.currentRevision(), 100);
        showStatus(AppI18n.get("KnowledgeCenterController.36"), false);
    }

    @FXML
    private void onExplain() {
        String question = field(queryField);
        if (question.isBlank()) {
            showStatus(AppI18n.get("KnowledgeCenterController.37"), true);
            return;
        }
        try {
            String objective = field(objectiveField);
            if (objective.isBlank()) {
                showStatus(AppI18n.get("KnowledgeCenterController.38"), true);
                return;
            }
            AiContextPreview preview = tutorService.preview(question, currentFilter());
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                AppI18n.get("KnowledgeCenterController.39") + preview.characterCount() + AppI18n.get("KnowledgeCenterController.40") + String.join("\n", preview.sources()),
                ButtonType.CANCEL, ButtonType.OK);
            confirm.setTitle(AppI18n.get("KnowledgeCenterController.41"));
            confirm.setHeaderText(AppI18n.get("KnowledgeCenterController.42"));
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        } catch (Throwable error) {
            fail(error);
            return;
        }
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.43"));
        DesktopExecutors.background().execute(() -> {
            try {
                var result = tutorService.ask(field(courseField), field(objectiveField), question, currentFilter());
                GroundedKnowledgeAnswer answer = result.answer();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    lastTutorSessionId = result.sessionId();
                    answerArea.setText(formatAnswer(answer));
                    showStatus(answer.message(), !answer.aiGenerated());
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML private void onTutorHelpful() { saveTutorFeedback(TutorFeedbackType.HELPFUL); }
    @FXML private void onTutorStillConfused() { saveTutorFeedback(TutorFeedbackType.STILL_CONFUSED); }
    @FXML private void onTutorCitationError() { saveTutorFeedback(TutorFeedbackType.CITATION_ERROR); }

    private void saveTutorFeedback(TutorFeedbackType type) {
        if (lastTutorSessionId.isBlank()) {
            showStatus(AppI18n.get("KnowledgeCenterController.44"), true);
            return;
        }
        try {
            tutorService.feedback(lastTutorSessionId, type, "");
            showStatus(AppI18n.get("KnowledgeCenterController.45"), false);
        } catch (Throwable error) {
            fail(error);
        }
    }

    @FXML
    private void onOpenExercise() {
        ExerciseSummary selected = exerciseTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus(AppI18n.get("KnowledgeCenterController.46"), true);
            return;
        }
        openExercise.accept(selected.id());
    }

    @FXML private void onRefresh() { refreshArticles(AppI18n.get("KnowledgeCenterController.47")); }

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
        GlobalLoading.show(AppI18n.get("KnowledgeCenterController.48"));
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
                    contentArea.setText(AppI18n.get("KnowledgeCenterController.49") + selected.currentRevision() + AppI18n.get("KnowledgeCenterController.50") + detail.history().size()
                        + "\n\n" + detail.revision().content());
                    exerciseTable.getItems().setAll(exercises);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    private void changeVisibility(KnowledgeVisibility visibility) {
        CourseKnowledgeArticle selected = selectedArticle(AppI18n.get("KnowledgeCenterController.51"));
        if (selected == null) return;
        runArticleChange(AppI18n.get("KnowledgeCenterController.52"), () -> knowledgeService.changeVisibility(selected.id(), visibility),
            AppI18n.get("KnowledgeCenterController.53") + visibilityLabel(visibility) + AppI18n.get("KnowledgeCenterController.54"));
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
                    showStatus(articles.isEmpty() ? AppI18n.get("KnowledgeCenterController.55")
                        : AppI18n.get("KnowledgeCenterController.56") + articles.size() + AppI18n.get("KnowledgeCenterController.57"), false);
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
        return List.of(field(knowledgePointsField).split(AppI18n.get("KnowledgeCenterController.58"))).stream()
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
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(AppI18n.get("KnowledgeCenterController.59"), "*.txt", "*.md", "*.markdown", "*.pdf", "*.docx"));
        return chooser;
    }

    private static String formatAnswer(GroundedKnowledgeAnswer answer) {
        StringBuilder text = new StringBuilder(answer.answer());
        if (!answer.citations().isEmpty()) text.append(AppI18n.get("KnowledgeCenterController.60"));
        for (GroundedKnowledgeAnswer.Citation citation : answer.citations()) {
            text.append("\n[").append(citation.number()).append("] ").append(citation.articleTitle())
                .append(" v").append(citation.revision()).append(AppI18n.get("KnowledgeCenterController.61")).append(citation.chunkIndex() + 1);
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
        indexStatusLabel.setText(AppI18n.get("KnowledgeCenterController.62") + status.mode() + AppI18n.get("KnowledgeCenterController.63") + status.pendingJobs()
            + AppI18n.get("KnowledgeCenterController.64") + status.indexedChunks() + AppI18n.get("KnowledgeCenterController.65") + status.failedChunks());
    }

    private static String summarize(String value) {
        String clean = value.replaceAll("\\s+", " ").trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 497) + "…";
    }

    private static String field(TextField field) { return field.getText() == null ? "" : field.getText().trim(); }
    private static SimpleStringProperty text(Object value) { return new SimpleStringProperty(String.valueOf(value)); }
    private static String visibilityLabel(KnowledgeVisibility value) {
        return switch (value) { case PRIVATE -> AppI18n.get("KnowledgeCenterController.66"); case PUBLISHED -> AppI18n.get("KnowledgeCenterController.67"); case INACTIVE -> AppI18n.get("KnowledgeCenterController.68"); };
    }

    @FunctionalInterface
    private interface ArticleChange { CourseKnowledgeArticle run(); }
}
