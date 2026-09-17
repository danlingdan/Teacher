package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.KnowledgeBundleService;
import com.sqlteacher.application.knowledge.KnowledgeBundleSource;
import com.sqlteacher.application.knowledge.KnowledgeBundleUpdateService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
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

    /**
     * v3.6.0 KBX-1: 索引工作移出 IPC 调用线程——导入/修订/重建立即返回，进度经
     * knowledge.index.status 轮询呈现。单线程串行避免并发重建互相踩踏。
     */
    private static final java.util.concurrent.ExecutorService INDEX_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "knowledge-index-worker");
            thread.setDaemon(true);
            return thread;
        });

    /** 测试可替换为同步执行器；生产走单线程后台。 */
    java.util.concurrent.Executor indexExecutor = INDEX_EXECUTOR;

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
            "knowledge.bundle.remove",
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
            case "knowledge.bundle.remove" -> knowledgeBundleRemove(params, cancellation);
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
        // v3.6.0 KBX-2/3：课程/章节/知识点过滤与 offset 分页进入桥契约；过滤下推 SQL 层。
        var filter = new CourseKnowledgeSearchFilter(
            optionalText(params, "courseTitle", 240),
            optionalText(params, "sectionTitle", 240),
            optionalText(params, "knowledgePoint", 240),
            true);
        int offset = Math.max(0, params.path("offset").asInt(0));
        CourseKnowledgeService service = context().getBean(CourseKnowledgeService.class);
        Map<String, String> articleIds = service.listArticles().stream()
            .collect(Collectors.toMap(item -> item.documentId(), item -> item.id(), (left, right) -> left));
        ArrayNode items = mapper.createArrayNode();
        int unmapped = 0;
        for (var item : service.search(query, filter, limit, offset)) {
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
        ObjectNode response = mapper.createObjectNode();
        response.set("items", items);
        response.put("hasMore", items.size() >= limit);
        return response;
    }

    /** v3.6.0 KBF-1/KIX：可选的过滤字段——缺省/空白等价于不过滤。 */
    private static String optionalText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("").trim();
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        }
        return value;
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
        // v3.6.0 KBX-1/KBQ-1：重建 = 当前分块算法重切全部文章 + 整体重建索引，后台执行。
        indexExecutor.execute(() -> {
            try {
                context().getBean(KnowledgeIndexService.class).rebuildContent();
            } catch (RuntimeException error) {
                log.warn("Background knowledge index rebuild failed: {}", error.getClass().getSimpleName());
            }
        });
        return mapper.createObjectNode().put("started", true);
    }

    /** v3.6.0 KBX-1: 导入/修订/可见性变更后的增量索引刷新，后台执行不阻塞 IPC。 */
    private void scheduleIndexRefresh() {
        indexExecutor.execute(() -> {
            try {
                context().getBean(KnowledgeIndexService.class).rebuildPending();
            } catch (RuntimeException error) {
                log.warn("Background knowledge index refresh failed: {}", error.getClass().getSimpleName());
            }
        });
    }

    private JsonNode knowledgeArticleImport(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        // v3.6.0 KBF-3：导入前先做同内容查重；命中时原样返回候选清单且不建索引，
        // 是否仍要导入由用户在前端显式确认（allowDuplicate=true）后重新发起。
        var result = context().getBean(CourseKnowledgeService.class).importArticle(
            Path.of(requiredText(params, "path", 32_768)), requiredText(params, "courseTitle", 240),
            requiredText(params, "sectionTitle", 240), textList(params.path("knowledgePoints"), 100, 240),
            params.path("allowDuplicate").asBoolean(false));
        if (!result.duplicateCandidate()) {
            scheduleIndexRefresh();
        }
        return mapper.valueToTree(result);
    }

    private JsonNode knowledgeArticleRevise(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).reviseArticle(
            requiredText(params, "articleId", 128), Path.of(requiredText(params, "path", 32_768)),
            textList(params.path("knowledgePoints"), 100, 240));
        scheduleIndexRefresh();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleVisibility(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        var article = context().getBean(CourseKnowledgeService.class).changeVisibility(
            requiredText(params, "articleId", 128), KnowledgeVisibility.valueOf(requiredText(params, "visibility", 32)));
        scheduleIndexRefresh();
        return mapper.valueToTree(article);
    }

    private JsonNode knowledgeArticleDelete(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        requireTeacher();
        String articleId = requiredText(params, "articleId", 128);
        // v3.6.0 KBF-2：走文章聚合删除（修订/分块/索引作业/底层文档与 FTS）。不再经旧版
        // deleteDocument 只删文档行的路径——应用库外键是关闭的、级联从不生效，过去会留下
        // 树里仍可见、可打开的文章残影，再次删除还会报 NOT_FOUND。
        context().getBean(CourseKnowledgeService.class).deleteArticle(articleId);
        deleteArticleVectors(articleId);
        return mapper.createObjectNode().put("deleted", true).put("articleId", articleId);
    }

    /**
     * v3.6.0 KBF-2: 单篇删除经外键级联清空 SQLite 侧数据后，同步清理向量索引残留（过去要等
     * 全量重建才消失）。清理失败不推翻已完成的删除：检索层本就按现存文章过滤孤儿命中，
     * 残留可经「重建索引」收敛。
     */
    private void deleteArticleVectors(String articleId) {
        try {
            context().getBean(KnowledgeVectorStore.class).deleteArticle(articleId);
        } catch (RuntimeException error) {
            log.warn("Article vector cleanup failed for {}: {} (rebuild the knowledge index to clear residue)",
                articleId, error.getClass().getSimpleName());
        }
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
        // v3.4.4：官方知识库是分发内容，检查/下载/手动导入对所有角色开放（用户 2026-09-16 确认）；
        // 人工导入/修订/删除等教学管理操作仍限教师。
        emit(events, "import.progress", "phase", "bundle-importing");
        var report = context().getBean(KnowledgeBundleService.class)
            .importBundle(Path.of(requiredText(params, "path", 32_768)), KnowledgeBundleSource.MANUAL);
        cancellation.throwIfCancelled();
        emit(events, "import.progress", "phase", "completed");
        return mapper.valueToTree(report);
    }

    private JsonNode knowledgeBundleRemove(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        // 与导入/下载同权：官方知识库内容对所有角色可管理（用户 2026-09-16 确认）。
        String bundleId = requiredText(params, "bundleId", 128);
        int removed = context().getBean(KnowledgeBundleService.class).removeBundle(bundleId);
        return mapper.createObjectNode().put("bundleId", bundleId).put("removedArticles", removed);
    }

    private JsonNode knowledgeBundleCheck(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(KnowledgeBundleUpdateService.class).check());
    }

    private JsonNode knowledgeBundleDownload(CancellationToken cancellation, Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
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
        // 多官方包（v3.5.3）：面板列出每个已装包，不再只透出第一个状态。
        var bundles = mapper.createArrayNode();
        for (var state : states) {
            bundles.add(mapper.createObjectNode()
                .put("bundleId", state.bundleId())
                .put("version", state.version())
                .put("source", state.source().wireName()));
        }
        result.set("bundles", bundles);
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
