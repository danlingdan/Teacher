package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.ai.AiContextPolicy;
import com.sqlteacher.application.ai.AiTaskService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.ObsidianVaultImportService;
import com.sqlteacher.application.planning.GroundedTutorService;
import com.sqlteacher.infrastructure.ai.DefaultGroundedKnowledgeExplanationService;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.JdbcGroundedTutorService;
import com.sqlteacher.infrastructure.knowledge.DefaultObsidianVaultImportService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Grounded knowledge explanations, course-knowledge import, and the evidence-backed tutor
 * persisted in the local database (v3.4.0 REF-20 split of SqlTeacherApplicationConfig).
 */
@Configuration
public class GroundedKnowledgeServiceConfig {

    @Bean
    public GroundedKnowledgeExplanationService groundedKnowledgeExplanationService(
        CourseKnowledgeService knowledgeService,
        HybridKnowledgeRetrievalService retrievalService,
        AiTaskService taskService,
        AiContextPolicy contextPolicy
    ) {
        return new DefaultGroundedKnowledgeExplanationService(knowledgeService, retrievalService, taskService, contextPolicy);
    }

    @Bean
    public ObsidianVaultImportService obsidianVaultImportService(CourseKnowledgeService knowledgeService) {
        return new DefaultObsidianVaultImportService(knowledgeService);
    }

    @Bean
    public GroundedTutorService groundedTutorService(GroundedKnowledgeExplanationService explanations,
                                                      JdbcConnectionFactory connections,
                                                      LearningEventOwnerProvider owners) {
        return new JdbcGroundedTutorService(explanations, connections, owners);
    }
}
