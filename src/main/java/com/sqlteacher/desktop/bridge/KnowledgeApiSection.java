package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleUpdateService;
import com.sqlteacher.application.knowledge.KnowledgeDocumentService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.application.knowledge.ObsidianVaultImportService;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** v3.4.0 REF-8: knowledge articles, local search, read state, and Obsidian vault import. */
final class KnowledgeApiSection extends ApiSection {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeApiSection.class);

    KnowledgeApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "knowledge.article", "knowledge.article.asset", "knowledge.search", "knowledge.read.mark",
            "knowledge.index.status", "knowledge.index.rebuild",
            "knowledge.article.import", "knowledge.article.revise",
            "knowledge.article.visibility", "knowledge.article.delete",
            "knowledge.import.preview", "knowledge.import.execute",
            "knowledge.bundle.import", "knowledge.bundle.check", "knowledge.bundle.download",
            "knowledge.overview"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "knowledge.article" -> knowledgeArticle(params, cancellation);
            case "knowledge.article.asset" -> knowledgeArticleAsset(params, cancellation);
            case "knowledge.search" -> knowledgeSearch(params, cancellation);
            case "knowledge.read.mark" -> knowledgeReadMark(params, cancellation);
            case "knowledge.index.status" -> knowledgeIndexStatus(cancellation);
            case "knowledge.index.rebuild" -> knowledgeIndexRebuild(cancellation);
            case "knowledge.article.import" -> knowledgeArticleImport(params, cancellation);
            case "knowledge.article.revise" -> knowledgeArticleRevise(params, cancellation);
            case "knowledge.article.visibility" -> knowledgeArticleVisibility(params, cancellation);
            case "knowledge.article.delete" -> knowledgeArticleDelete(params, cancellation);
            case "knowledge.import.preview" -> knowledgeImportPreview(params, cancellation);
            case "knowledge.import.execute" -> knowledgeImportExecute(params, cancellation, events);
            case "knowledge.bundle.import" -> knowledgeBundleImport(params, cancellation, events);
            case "knowledge.bundle.check" -> knowledgeBundleCheck(cancellation);
            case "knowledge.bundle.download" -> knowledgeBundleDownload(cancellation, events);
            case "knowledge.overview" -> knowledgeOverview(cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode knowledgeArticle(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var detail = context().getBean(CourseKnowledgeService.class)
            .getArticle(requiredText(params, "articleId", 128));
        ObjectNode result = mapper.createObjectNode();
        result.set("article", mapper.valueToTree(detail.article()));
        result.put("markdown", detail.revision().content());
        result.put("sourceName", detail.revision().sourceName());
        result.put("revision", detail.revision().revision());
        result.put("trustedHtml", false);
        result.put("externalResourcesAllowed", false);
        return result;
    }

    private JsonNode knowledgeArticleAsset(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String articleId = requiredText(params, "articleId", 128);
        String path = requiredText(params, "path", 4096);
        var asset = context().getBean(KnowledgeBundleService.class).readArticleAsset(articleId, path);
        ObjectNode result = mapper.createObjectNode();
        result.put("contentType", asset.contentType());
        result.put("dataBase64", Base64.getEncoder().encodeToString(asset.data()));
        return result;
    }

    private JsonNode knowledgeSearch(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String query = requiredText(params, "query", 500);
        int limit = Math.clamp(params.path("limit").asInt(30), 1, 100);
        CourseKnowledgeService service = context().getBean(CourseKnowledgeService.class);
        Map<String, String> articleIds = service.listArticles().stream()
            .collect(Collectors.toMap(item -> item.documentId(), item -> item.id(), (left, right) -> left));
        ArrayNode items = mapper.createArrayNode();
        int unmapped = 0;
        for (var item : service.search(query, CourseKnowledgeSearchFilter.allLocal(), limit)) {
            // v3.4.1 KNW-2：映射不到课程知识文章的命中直接跳过。前端把每条检索结果都渲染
            // 为可打开按钮，过去 articleId 为空串的命中会变成永远点不动的禁用项。
            String articleId = articleIds.get(item.documentId());
            if (articleId == null || articleId.isBlank()) {
                unmapped++;
                continue;
            }
            ObjectNode result = items.addObject();
            result.put("articleId", articleId);
            result.put("documentId", item.documentId());
            result.put("title", item.title());
            result.put("sourceName", item.sourceName());
            result.put("chunkIndex", item.chunkIndex());
            result.put("snippet", item.snippet());
            result.put("relevance", item.relevance());
        }
        if (unmapped > 0) {
            log.debug("knowledge.search skipped {} hit(s) without a course article mapping", unmapped);
        }
        return mapper.createObjectNode().set("items", items);
    }

    private JsonNode knowledgeReadMark(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(KnowledgeReadStateService.class).save(
            requiredText(params, "articleId", 128), Math.max(1, params.path("revision").asInt(1)),
            Math.clamp(params.path("progressPercent").asInt(100), 0, 100)));
    }

    private JsonNode knowledgeIndexStatus(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(KnowledgeIndexService.class).status());
    }

    private JsonNode knowledgeIndexRebuild(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        return mapper.valueToTree(context().getBean(KnowledgeIndexService.class).rebuildAll());
    }

    private JsonNode knowledgeArticleImport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).importArticle(
            Path.of(requiredText(params, "path", 32_768)), requiredText(params, "courseTitle", 240),
            requiredText(params, "sectionTitle", 240), textList(params.path("knowledgePoints"), 100, 240));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleRevise(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).reviseArticle(
            requiredText(params, "articleId", 128), Path.of(requiredText(params, "path", 32_768)),
            textList(params.path("knowledgePoints"), 100, 240));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleVisibility(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).changeVisibility(
            requiredText(params, "articleId", 128), KnowledgeVisibility.valueOf(requiredText(params, "visibility", 32)));
        context().getBean(KnowledgeIndexService.class).rebuildPending();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleDelete(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        String articleId = requiredText(params, "articleId", 128);
        var service = context().getBean(CourseKnowledgeService.class);
        var article = service.listArticles().stream().filter(item -> item.id().equals(articleId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Knowledge article was not found"));
        context().getBean(KnowledgeDocumentService.class).deleteDocument(article.documentId());
        return mapper.createObjectNode().put("deleted", true).put("articleId", articleId);
    }

    private JsonNode knowledgeImportPreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String root = requiredText(params, "root", 32_768);
        var mapping = new ObsidianVaultImportService.ImportMapping(
            params.path("courseTitle").asText("Obsidian 知识库"),
            params.path("sectionDepth").asInt(1),
            params.path("includeAttachments").asBoolean(true));
        var preview = context().getBean(ObsidianVaultImportService.class).preview(Path.of(root), mapping);
        return mapper.valueToTree(preview);
    }

    private JsonNode knowledgeImportExecute(JsonNode params, CancellationToken cancellation,
                                            Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "verifying");
        var report = context().getBean(ObsidianVaultImportService.class)
            .execute(requiredText(params, "previewToken", 128));
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "completed");
        return mapper.valueToTree(report);
    }

    private JsonNode knowledgeBundleImport(JsonNode params, CancellationToken cancellation,
                                           Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        requireTeacher();
        emit(events, "import.progress", "phase", "bundle-importing");
        var report = context().getBean(KnowledgeBundleService.class)
            .importBundle(Path.of(requiredText(params, "path", 32_768)), KnowledgeBundleSource.MANUAL);
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "completed");
        return mapper.valueToTree(report);
    }

    private JsonNode knowledgeBundleCheck(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        return mapper.valueToTree(context().getBean(KnowledgeBundleUpdateService.class).check());
    }

    private JsonNode knowledgeBundleDownload(CancellationToken cancellation, Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        requireTeacher();
        emit(events, "import.progress", "phase", "bundle-downloading");
        var report = context().getBean(KnowledgeBundleUpdateService.class).downloadAndImport();
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "completed");
        return mapper.valueToTree(report);
    }

    /**
     * v3.4.3 KSR-1: one authoritative snapshot for the knowledge page's three-state rendering, so it
     * no longer borrows the course workspace payload (which mixes courses, activities, and articles).
     */
    private JsonNode knowledgeOverview(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var articles = context().getBean(CourseKnowledgeService.class).listArticles();
        var index = context().getBean(KnowledgeIndexService.class).status();
        var states = context().getBean(KnowledgeBundleService.class).listBundleStates();
        ObjectNode result = mapper.createObjectNode();
        result.put("articleCount", articles.size());
        result.set("articles", mapper.valueToTree(articles));
        if (states.isEmpty()) {
            result.put("hasOfficialBundle", false);
            result.putNull("bundle");
        } else {
            result.put("hasOfficialBundle", true);
            result.set("bundle", mapper.valueToTree(states.get(0)));
        }
        result.set("index", mapper.valueToTree(index));
        return result;
    }
}
