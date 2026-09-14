package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.knowledge.EmbeddingProvider;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import com.sqlteacher.application.knowledge.WebSearchProvider;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.SqliteKnowledgeService;
import com.sqlteacher.infrastructure.knowledge.BraveWebSearchProvider;
import com.sqlteacher.infrastructure.knowledge.DefaultHybridKnowledgeRetrievalService;
import com.sqlteacher.infrastructure.knowledge.JdkSafeWebContentFetcher;
import com.sqlteacher.infrastructure.knowledge.LuceneKnowledgeVectorStore;
import com.sqlteacher.infrastructure.knowledge.OllamaEmbeddingProvider;
import com.sqlteacher.infrastructure.knowledge.SqliteKnowledgeIndexService;
import com.sqlteacher.infrastructure.knowledge.SqliteKnowledgeReadStateService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hybrid knowledge retrieval, indexing, read state, and web providers for the local SQLite
 * application database (v3.4.0 REF-20 split of DatabaseServiceConfig).
 */
@Configuration
public class DatabaseKnowledgeServiceConfig {

    @Bean
    public SqliteKnowledgeService sqliteKnowledgeService(
            JdbcConnectionFactory connectionFactory,
            LearningEventService learningEventService,
            LearningEventOwnerProvider ownerProvider) {
        return new SqliteKnowledgeService(connectionFactory, learningEventService, ownerProvider);
    }

    @Bean
    public EmbeddingProvider knowledgeEmbeddingProvider(SqlTeacherConfiguration configuration) {
        return new OllamaEmbeddingProvider(configuration.ai(), System.getProperty(
            "sqlteacher.knowledge.embedding-model", "embeddinggemma"));
    }

    @Bean
    public KnowledgeVectorStore knowledgeVectorStore(SqlTeacherConfiguration configuration) {
        return new LuceneKnowledgeVectorStore(configuration.dataDirectory().resolve("indexes").resolve("knowledge"));
    }

    @Bean
    public KnowledgeIndexService knowledgeIndexService(JdbcConnectionFactory connectionFactory,
            EmbeddingProvider embeddingProvider, KnowledgeVectorStore vectorStore) {
        return new SqliteKnowledgeIndexService(connectionFactory, embeddingProvider, vectorStore);
    }

    @Bean
    public HybridKnowledgeRetrievalService hybridKnowledgeRetrievalService(SqliteKnowledgeService knowledgeService,
            EmbeddingProvider embeddingProvider, KnowledgeVectorStore vectorStore, LearningEventOwnerProvider ownerProvider) {
        return new DefaultHybridKnowledgeRetrievalService(knowledgeService, embeddingProvider, vectorStore, ownerProvider);
    }

    @Bean
    public KnowledgeReadStateService knowledgeReadStateService(JdbcConnectionFactory connectionFactory,
            LearningEventOwnerProvider ownerProvider) {
        return new SqliteKnowledgeReadStateService(connectionFactory, ownerProvider);
    }

    @Bean
    public WebSearchProvider webSearchProvider() {
        String key = System.getProperty("sqlteacher.knowledge.brave-api-key",
            System.getenv().getOrDefault("SQLTEACHER_BRAVE_API_KEY", ""));
        return new BraveWebSearchProvider(key);
    }

    @Bean
    public SafeWebContentFetcher safeWebContentFetcher() {
        return new JdkSafeWebContentFetcher();
    }
}
