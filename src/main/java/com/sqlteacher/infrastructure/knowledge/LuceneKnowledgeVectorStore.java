package com.sqlteacher.infrastructure.knowledge;

import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.KnowledgeChunkRecord;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.knowledge.KnowledgeVisibility;
import com.sqlteacher.domain.SqlTeacherException;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LuceneKnowledgeVectorStore implements KnowledgeVectorStore {
    private static final String VECTOR = "embedding";
    private final Path indexPath;

    public LuceneKnowledgeVectorStore(Path indexPath) {
        this.indexPath = Objects.requireNonNull(indexPath).toAbsolutePath().normalize();
    }

    @Override
    public synchronized void replaceRevision(String revisionId, List<KnowledgeChunkRecord> chunks, List<float[]> vectors) {
        if (chunks == null || vectors == null || chunks.size() != vectors.size() || chunks.isEmpty()) {
            throw new IllegalArgumentException("chunks and vectors must have the same non-zero size");
        }
        try {
            Files.createDirectories(indexPath);
            try (Directory directory = FSDirectory.open(indexPath);
                 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
                writer.deleteDocuments(new Term("revisionId", revisionId));
                for (int index = 0; index < chunks.size(); index++) writer.addDocument(toDocument(chunks.get(index), vectors.get(index)));
                writer.commit();
            }
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_VECTOR_WRITE_FAILED", "Failed to update local vector index", error);
        }
    }

    @Override
    public synchronized List<VectorSearchHit> search(float[] queryVector, CourseKnowledgeSearchFilter filter, String ownerId, int limit) {
        if (queryVector == null || queryVector.length == 0 || limit < 1) throw new IllegalArgumentException("invalid vector search request");
        if (!Files.isDirectory(indexPath)) return List.of();
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) return List.of();
            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                int candidateLimit = Math.min(Math.max(limit * 8, limit), 200);
                ScoreDoc[] hits = searcher.search(new KnnFloatVectorQuery(VECTOR, queryVector, candidateLimit), candidateLimit).scoreDocs;
                List<VectorSearchHit> results = new ArrayList<>();
                for (ScoreDoc hit : hits) {
                    Document document = searcher.storedFields().document(hit.doc);
                    KnowledgeChunkRecord chunk = fromDocument(document);
                    if (authorized(chunk, ownerId, filter)) results.add(new VectorSearchHit(chunk, hit.score));
                    if (results.size() == limit) break;
                }
                return List.copyOf(results);
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw new SqlTeacherException("KNOWLEDGE_VECTOR_SEARCH_FAILED", "Failed to search local vector index", error);
        }
    }

    @Override
    public synchronized void deleteArticle(String articleId) {
        mutate(writer -> writer.deleteDocuments(new Term("articleId", articleId)));
    }

    @Override
    public synchronized void clear() {
        mutate(IndexWriter::deleteAll);
    }

    private void mutate(WriterAction action) {
        try {
            Files.createDirectories(indexPath);
            try (Directory directory = FSDirectory.open(indexPath);
                 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(new StandardAnalyzer()))) {
                action.run(writer);
                writer.commit();
            }
        } catch (IOException error) {
            throw new SqlTeacherException("KNOWLEDGE_VECTOR_WRITE_FAILED", "Failed to update local vector index", error);
        }
    }

    private static Document toDocument(KnowledgeChunkRecord chunk, float[] vector) {
        Document document = new Document();
        string(document, "id", chunk.id()); string(document, "documentId", chunk.documentId());
        string(document, "articleId", chunk.articleId()); string(document, "revisionId", chunk.revisionId());
        string(document, "visibility", chunk.visibility().name()); string(document, "ownerId", chunk.ownerId());
        stored(document, "revision", chunk.revision()); stored(document, "chunkIndex", chunk.chunkIndex());
        stored(document, "title", chunk.title()); stored(document, "courseTitle", chunk.courseTitle());
        stored(document, "sectionTitle", chunk.sectionTitle()); stored(document, "headingPath", chunk.headingPath());
        stored(document, "knowledgePoints", String.join("\u001f", chunk.knowledgePoints()));
        stored(document, "content", chunk.content());
        document.add(new KnnFloatVectorField(VECTOR, vector, VectorSimilarityFunction.COSINE));
        return document;
    }

    private static KnowledgeChunkRecord fromDocument(Document document) {
        String points = document.get("knowledgePoints");
        return new KnowledgeChunkRecord(document.get("id"), document.get("documentId"), document.get("articleId"),
            document.get("revisionId"), document.getField("revision").numericValue().intValue(),
            document.getField("chunkIndex").numericValue().intValue(), document.get("title"), document.get("courseTitle"),
            document.get("sectionTitle"), points == null || points.isBlank() ? List.of() : List.of(points.split("\\u001f")),
            document.get("headingPath"), document.get("content"), KnowledgeVisibility.valueOf(document.get("visibility")),
            document.get("ownerId"));
    }

    private static boolean authorized(KnowledgeChunkRecord chunk, String ownerId, CourseKnowledgeSearchFilter filter) {
        if (!(chunk.ownerId().equals(ownerId) || chunk.visibility() == KnowledgeVisibility.PUBLISHED)) return false;
        if (!filter.includePrivate() && chunk.visibility() != KnowledgeVisibility.PUBLISHED) return false;
        if (!filter.courseTitle().isBlank() && !chunk.courseTitle().equalsIgnoreCase(filter.courseTitle())) return false;
        if (!filter.sectionTitle().isBlank() && !chunk.sectionTitle().equalsIgnoreCase(filter.sectionTitle())) return false;
        return filter.knowledgePoint().isBlank() || chunk.knowledgePoints().stream()
            .anyMatch(value -> value.equalsIgnoreCase(filter.knowledgePoint()));
    }

    private static void string(Document document, String name, String value) {
        document.add(new StringField(name, value, Field.Store.YES));
    }
    private static void stored(Document document, String name, String value) { document.add(new StoredField(name, value)); }
    private static void stored(Document document, String name, int value) { document.add(new StoredField(name, value)); }

    @FunctionalInterface private interface WriterAction { void run(IndexWriter writer) throws IOException; }
}
