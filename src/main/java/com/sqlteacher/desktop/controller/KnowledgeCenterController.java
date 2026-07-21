package com.sqlteacher.desktop.controller;

import com.sqlteacher.application.error.ApplicationExceptionMapper;
import com.sqlteacher.application.knowledge.KnowledgeDocument;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;
import com.sqlteacher.application.knowledge.KnowledgeSearchService;
import com.sqlteacher.desktop.DesktopExecutors;
import com.sqlteacher.desktop.GlobalLoading;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

public final class KnowledgeCenterController {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault());

    private final KnowledgeDocumentService documentService;
    private final KnowledgeSearchService searchService;
    private final ApplicationExceptionMapper exceptionMapper;

    @FXML private TextField queryField;
    @FXML private Button importButton;
    @FXML private Label statusLabel;
    @FXML private TableView<KnowledgeDocument> documentTable;
    @FXML private TableColumn<KnowledgeDocument, String> documentTitleColumn;
    @FXML private TableColumn<KnowledgeDocument, String> sourceColumn;
    @FXML private TableColumn<KnowledgeDocument, String> chunksColumn;
    @FXML private TableColumn<KnowledgeDocument, String> importedColumn;
    @FXML private TableView<KnowledgeSearchResult> resultTable;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultTitleColumn;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultSourceColumn;
    @FXML private TableColumn<KnowledgeSearchResult, String> resultSnippetColumn;

    public KnowledgeCenterController(
        KnowledgeDocumentService documentService,
        KnowledgeSearchService searchService,
        ApplicationExceptionMapper exceptionMapper
    ) {
        this.documentService = Objects.requireNonNull(documentService);
        this.searchService = Objects.requireNonNull(searchService);
        this.exceptionMapper = Objects.requireNonNull(exceptionMapper);
    }

    @FXML
    private void initialize() {
        documentTitleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        sourceColumn.setCellValueFactory(cell -> text(cell.getValue().sourceName()));
        chunksColumn.setCellValueFactory(cell -> text(cell.getValue().chunkCount()));
        importedColumn.setCellValueFactory(cell -> text(TIME_FORMAT.format(cell.getValue().importedAt())));
        resultTitleColumn.setCellValueFactory(cell -> text(cell.getValue().title()));
        resultSourceColumn.setCellValueFactory(cell -> text(cell.getValue().sourceName()));
        resultSnippetColumn.setCellValueFactory(cell -> text(cell.getValue().snippet()));
        queryField.setOnAction(event -> onSearch());
        refreshDocuments("正在加载课程知识库…");
    }

    @FXML
    private void onImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入课程知识文档");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("UTF-8 文本或 Markdown", "*.txt", "*.md", "*.markdown")
        );
        File file = chooser.showOpenDialog(importButton.getScene().getWindow());
        if (file == null) {
            return;
        }
        GlobalLoading.show("正在切片并建立本地索引…");
        DesktopExecutors.background().execute(() -> {
            try {
                KnowledgeDocument imported = documentService.importDocument(file.toPath());
                List<KnowledgeDocument> documents = documentService.listDocuments();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    documentTable.getItems().setAll(documents);
                    showStatus("已导入《" + imported.title() + "》，共 " + imported.chunkCount() + " 个片段。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onDelete() {
        KnowledgeDocument selected = documentTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showStatus("请先选择要删除的文档。", true);
            return;
        }
        Alert confirm = new Alert(
            Alert.AlertType.CONFIRMATION,
            "删除后文档片段将立即从本地检索索引中移除。",
            ButtonType.CANCEL,
            ButtonType.OK
        );
        confirm.setTitle("删除知识文档");
        confirm.setHeaderText("确认删除《" + selected.title() + "》？");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        GlobalLoading.show("正在删除文档与索引…");
        DesktopExecutors.background().execute(() -> {
            try {
                documentService.deleteDocument(selected.id());
                List<KnowledgeDocument> documents = documentService.listDocuments();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    documentTable.getItems().setAll(documents);
                    resultTable.getItems().clear();
                    showStatus("文档与对应索引已删除。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onSearch() {
        String query = queryField.getText() == null ? "" : queryField.getText().trim();
        if (query.isBlank()) {
            showStatus("请输入要检索的课程知识。", true);
            return;
        }
        GlobalLoading.show("正在检索本地课程资料…");
        DesktopExecutors.background().execute(() -> {
            try {
                List<KnowledgeSearchResult> results = searchService.search(query, 20);
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    resultTable.getItems().setAll(results);
                    showStatus(results.isEmpty()
                        ? "未找到相关资料，请尝试更短或更精确的关键词。"
                        : "找到 " + results.size() + " 个片段；结果均标注本地来源。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
    }

    @FXML
    private void onRefresh() {
        refreshDocuments("正在刷新知识文档…");
    }

    private void refreshDocuments(String loadingText) {
        GlobalLoading.show(loadingText);
        DesktopExecutors.background().execute(() -> {
            try {
                List<KnowledgeDocument> documents = documentService.listDocuments();
                Platform.runLater(() -> {
                    GlobalLoading.hide();
                    documentTable.getItems().setAll(documents);
                    showStatus(documents.isEmpty()
                        ? "暂无课程资料。可导入 UTF-8 文本或 Markdown 文档。"
                        : "已加载 " + documents.size() + " 份本地课程资料。", false);
                });
            } catch (Throwable error) {
                Platform.runLater(() -> fail(error));
            }
        });
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

    private static SimpleStringProperty text(Object value) {
        return new SimpleStringProperty(String.valueOf(value));
    }
}
