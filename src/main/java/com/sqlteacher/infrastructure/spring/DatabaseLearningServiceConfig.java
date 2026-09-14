package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.JdbcLearningAnalyticsService;
import com.sqlteacher.infrastructure.database.JdbcLearningDiagnosisService;
import com.sqlteacher.infrastructure.database.JdbcLearningEventQueryService;
import com.sqlteacher.infrastructure.database.JdbcLearningEventRecorder;
import com.sqlteacher.infrastructure.database.JdbcStudyPlanCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Learning-event, diagnosis, analytics, and study-plan cache services backed by the local
 * SQLite application database (v3.4.0 REF-20 split of DatabaseServiceConfig).
 */
@Configuration
public class DatabaseLearningServiceConfig {

    @Bean
    public LearningEventRecorder learningEventRecorder(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningEventRecorder(connectionFactory);
    }

    @Bean
    public InMemoryLearningEventOwnerContext learningEventOwnerContext() {
        return new InMemoryLearningEventOwnerContext();
    }

    @Bean
    public LearningEventService learningEventService(
            LearningEventRecorder learningEventRecorder,
            LearningEventOwnerProvider ownerProvider) {
        return new DefaultLearningEventService(learningEventRecorder, ownerProvider);
    }

    @Bean
    public LearningEventQueryService learningEventQueryService(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningEventQueryService(connectionFactory);
    }

    @Bean
    public LearningDiagnosisService learningDiagnosisService(JdbcConnectionFactory connectionFactory,
                                                              LearningEventOwnerProvider ownerProvider) {
        return new JdbcLearningDiagnosisService(connectionFactory, ownerProvider);
    }

    @Bean
    public StudyPlanCache studyPlanCache(JdbcConnectionFactory connectionFactory,
                                         LearningEventOwnerProvider ownerProvider) {
        return new JdbcStudyPlanCache(connectionFactory, ownerProvider);
    }

    @Bean
    public LearningAnalyticsService learningAnalyticsService(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningAnalyticsService(connectionFactory);
    }
}
