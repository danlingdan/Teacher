package com.sqlteacher.server;

import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudKnowledgeSearchHit;
import com.sqlteacher.domain.SqlTeacherException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CloudKnowledgeIndexService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CloudKnowledgeIndexService.class);
    private static final int INDEX_VERSION = 1;
    private static final int BATCH_SIZE = 64;
    private static final double RRF_K = 60.0;

    private final V14CloudStore store;
    private final CloudKnowledgeEmbeddingClient embeddings;
    private final CloudKnowledgeVectorClient vectors;
    private final int vectorSize;
    private final boolean enabled;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean processing = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    private CloudKnowledgeIndexService(V14CloudStore store) {
        this.store = store;
        this.embeddings = null;
        this.vectors = null;
        this.vectorSize = 0;
        this.enabled = false;
        this.executor = null;
    }

    CloudKnowledgeIndexService(V14CloudStore store, CloudKnowledgeEmbeddingClient embeddings,
                               CloudKnowledgeVectorClient vectors, int vectorSize) {
        this.store = java.util.Objects.requireNonNull(store);
        this.embeddings = java.util.Objects.requireNonNull(embeddings);
        this.vectors = java.util.Objects.requireNonNull(vectors);
        if (vectorSize < 1) throw new IllegalArgumentException("vectorSize must be positive");
        this.vectorSize = vectorSize;
        this.enabled = true;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = Thread.ofPlatform().daemon().name("cloud-knowledge-indexer").unstarted(runnable);
            thread.setUncaughtExceptionHandler((ignored, error) -> log.warn(
                "Cloud knowledge index worker stopped unexpectedly, exceptionType={}", error.getClass().getSimpleName()));
            return thread;
        });
    }

    static CloudKnowledgeIndexService fromEnvironment(V14CloudStore store) {
        Map<String, String> environment = System.getenv();
        String qdrantUrl = environment.getOrDefault("SQLTEACHER_QDRANT_URL", "").trim();
        String qdrantKey = environment.getOrDefault("SQLTEACHER_QDRANT_API_KEY", "").trim();
        if (qdrantUrl.isBlank() || qdrantKey.isBlank()) return new CloudKnowledgeIndexService(store);
        String collection = environment.getOrDefault("SQLTEACHER_QDRANT_COLLECTION",
            "sqlteacher_course_knowledge_v1").trim();
        int dimension = Integer.parseInt(environment.getOrDefault("SQLTEACHER_QDRANT_VECTOR_SIZE", "512"));
        URI embeddingUrl = URI.create(environment.getOrDefault("SQLTEACHER_EMBEDDING_URL",
            "http://127.0.0.1:11434"));
        String model = environment.getOrDefault("SQLTEACHER_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5").trim();
        String provider = environment.getOrDefault("SQLTEACHER_EMBEDDING_PROVIDER", "ollama").trim();
        return new CloudKnowledgeIndexService(store,
            new OllamaCloudKnowledgeEmbeddingClient(embeddingUrl, model, dimension, provider),
            new QdrantVectorClient(URI.create(qdrantUrl), collection, qdrantKey), dimension);
    }

    void start() {
        if (!enabled || !started.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(this::processSafely, 0, 5, TimeUnit.SECONDS);
    }

    void wake() {
        if (enabled && started.get()) executor.execute(this::processSafely);
    }

    List<CloudKnowledgeSearchHit> search(AuthenticatedUser actor, String courseId, String query, int limit) {
        List<CloudKnowledgeSearchHit> keyword = store.searchKnowledge(actor, courseId, query,
            Math.min(50, Math.max(limit * 4, limit)));
        if (!enabled) return keyword.stream().limit(limit).toList();
        try {
            CloudKnowledgeEmbeddingClient.EmbeddingBatch batch = embeddings.embed(List.of(query),
                CloudKnowledgeEmbeddingClient.Purpose.QUERY);
            List<String> visibility = store.allowedKnowledgeVisibility(actor, courseId);
            List<QdrantVectorClient.SearchHit> raw = vectors.search(batch.vectors().getFirst(), courseId,
                visibility, Math.min(100, Math.max(limit * 4, limit)));
            List<V14CloudStore.VectorCandidate> candidates = raw.stream()
                .map(item -> new V14CloudStore.VectorCandidate(item.id(), item.score())).toList();
            List<CloudKnowledgeSearchHit> semantic = store.resolveAuthorizedVectorHits(actor, courseId, query,
                candidates, Math.min(100, Math.max(limit * 4, limit)));
            return fuse(keyword, semantic, limit);
        } catch (RuntimeException error) {
            log.warn("Cloud semantic search degraded to keyword retrieval, exceptionType={}",
                error.getClass().getSimpleName());
            return keyword.stream().limit(limit).toList();
        }
    }

    Health health() {
        long backlog;
        try {
            backlog = store.pendingKnowledgeIndexCount();
        } catch (RuntimeException error) {
            return new Health(enabled ? "degraded" : "disabled", -1);
        }
        if (!enabled) return new Health("disabled", backlog);
        return new Health(vectors.ready() && embeddings.ready() ? "ready" : "degraded", backlog);
    }

    int rebuild() {
        if (!enabled) throw new IllegalStateException("Cloud knowledge vector indexing is disabled");
        int queued = store.requeueKnowledgeIndex();
        wake();
        return queued;
    }

    int processPendingNow() {
        if (!enabled || !processing.compareAndSet(false, true)) return 0;
        try {
            vectors.validateCollection(vectorSize);
            List<V14CloudStore.PendingKnowledgeChunk> pending = store.pendingKnowledgeChunks(BATCH_SIZE);
            if (pending.isEmpty()) return 0;
            try {
                CloudKnowledgeEmbeddingClient.EmbeddingBatch batch = embeddings.embed(
                    pending.stream().map(V14CloudStore.PendingKnowledgeChunk::content).toList(),
                    CloudKnowledgeEmbeddingClient.Purpose.PASSAGE);
                if (batch.vectors().size() != pending.size()) {
                    throw new SqlTeacherException("CLOUD_EMBEDDING_INVALID", "Embedding batch size mismatch");
                }
                List<QdrantVectorClient.Point> points = new ArrayList<>();
                for (int index = 0; index < pending.size(); index++) {
                    V14CloudStore.PendingKnowledgeChunk item = pending.get(index);
                    points.add(new QdrantVectorClient.Point(item.chunkId(), batch.vectors().get(index), Map.of(
                        "courseId", item.courseId(), "visibility", item.visibility(),
                        "articleId", item.articleId(), "revision", item.revision(),
                        "chunkIndex", item.chunkIndex(), "contentHash", item.contentHash(),
                        "embeddingModel", batch.model(), "embeddingVersion", INDEX_VERSION)));
                }
                vectors.upsert(points);
                store.markKnowledgeIndexed(pending, batch.provider(), batch.model(), vectorSize, INDEX_VERSION);
                log.info("Cloud knowledge vector batch indexed, chunks={}", pending.size());
                return pending.size();
            } catch (RuntimeException error) {
                String failure = error instanceof SqlTeacherException sql ? sql.errorCode()
                    : error.getClass().getSimpleName();
                store.markKnowledgeIndexFailed(pending, failure);
                throw error;
            }
        } finally {
            processing.set(false);
        }
    }

    private void processSafely() {
        try {
            int processed;
            do {
                processed = processPendingNow();
            } while (processed == BATCH_SIZE);
        } catch (RuntimeException error) {
            log.warn("Cloud knowledge indexing deferred, exceptionType={}", error.getClass().getSimpleName());
        }
    }

    private static List<CloudKnowledgeSearchHit> fuse(List<CloudKnowledgeSearchHit> keyword,
                                                       List<CloudKnowledgeSearchHit> semantic, int limit) {
        Map<String, RankedHit> ranked = new LinkedHashMap<>();
        add(ranked, keyword);
        add(ranked, semantic);
        return ranked.values().stream().sorted(Comparator.comparingDouble(RankedHit::score).reversed()
                .thenComparing(value -> value.hit().articleId())
                .thenComparingInt(value -> value.hit().chunkIndex()))
            .limit(limit).map(value -> new CloudKnowledgeSearchHit(value.hit().articleId(), value.hit().title(),
                value.hit().revision(), value.hit().chunkIndex(), value.hit().snippet(), value.score())).toList();
    }

    private static void add(Map<String, RankedHit> ranked, List<CloudKnowledgeSearchHit> hits) {
        for (int index = 0; index < hits.size(); index++) {
            CloudKnowledgeSearchHit hit = hits.get(index);
            String key = hit.articleId() + ':' + hit.revision() + ':' + hit.chunkIndex();
            double contribution = 1.0 / (RRF_K + index + 1);
            ranked.compute(key, (ignored, current) -> current == null ? new RankedHit(hit, contribution)
                : new RankedHit(current.hit(), current.score() + contribution));
        }
    }

    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }

    record Health(String status, long backlog) {}

    private record RankedHit(CloudKnowledgeSearchHit hit, double score) {}
}
