package com.sqlteacher.infrastructure.database;

import com.sqlteacher.application.activity.ActivityBackedSqlExerciseEvaluationService;
import com.sqlteacher.application.activity.ActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.ActivityEvaluator;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.activity.DefaultActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.CodeActivityEvaluator;
import com.sqlteacher.application.activity.LabActivityEvaluator;
import com.sqlteacher.application.activity.QuizActivityEvaluator;
import com.sqlteacher.application.activity.ProjectActivityEvaluator;
import com.sqlteacher.application.activity.ProjectPortfolioService;
import com.sqlteacher.application.activity.ReadingActivityEvaluator;
import com.sqlteacher.application.activity.SimulationActivityEvaluator;
import com.sqlteacher.application.activity.SqlActivityEvaluator;
import com.sqlteacher.application.activity.TraceActivityEvaluator;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.infrastructure.runner.WslSandboxCodeRunner;
import com.sqlteacher.application.connection.ConnectionManagementService;
import com.sqlteacher.application.connection.DatabaseConnectionTestService;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.event.DefaultLearningEventService;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.execution.SqlExecutionService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.analytics.LearningAnalyticsService;
import com.sqlteacher.application.maintenance.DataMaintenanceService;
import com.sqlteacher.application.maintenance.ApplicationBackupService;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.application.knowledge.EmbeddingProvider;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeIndexService;
import com.sqlteacher.application.knowledge.KnowledgeReadStateService;
import com.sqlteacher.application.knowledge.KnowledgeVectorStore;
import com.sqlteacher.application.knowledge.WebSearchProvider;
import com.sqlteacher.application.knowledge.SafeWebContentFetcher;
import com.sqlteacher.application.metadata.DatabaseMetadataService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.risk.SqlSafetyModeService;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.CloudApiClient;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.TeachingContentCache;
import com.sqlteacher.infrastructure.cloud.JdbcAssignmentDeliveryService;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.cloud.JdbcTeachingContentCache;
import com.sqlteacher.infrastructure.cloud.DefaultInterventionService;
import com.sqlteacher.infrastructure.cloud.DefaultStudentLearningQueueService;
import com.sqlteacher.infrastructure.config.FileSqlSafetyModeService;
import com.sqlteacher.infrastructure.knowledge.BraveWebSearchProvider;
import com.sqlteacher.infrastructure.knowledge.DefaultHybridKnowledgeRetrievalService;
import com.sqlteacher.infrastructure.knowledge.JdkSafeWebContentFetcher;
import com.sqlteacher.infrastructure.knowledge.LuceneKnowledgeVectorStore;
import com.sqlteacher.infrastructure.knowledge.OllamaEmbeddingProvider;
import com.sqlteacher.infrastructure.knowledge.SqliteKnowledgeIndexService;
import com.sqlteacher.infrastructure.knowledge.SqliteKnowledgeReadStateService;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;

@Configuration
public class DatabaseServiceConfig {

    @Bean
    public JdbcConnectionFactory jdbcConnectionFactory(SqlTeacherConfiguration configuration) {
        return new JdbcConnectionFactory(configuration.database());
    }

    @Bean
    public SqlResultMapper sqlResultMapper() {
        return new SqlResultMapper();
    }

    @Bean
    public SqlRiskAnalysisService sqlRiskAnalysisService() {
        return new DefaultSqlRiskAnalysisService();
    }

    @Bean
    public SqlSafetyModeService sqlSafetyModeService(SqlTeacherConfiguration configuration) {
        return new FileSqlSafetyModeService(configuration.dataDirectory().resolve("sql-safety.properties"));
    }

    @Bean
    public ConnectionManagementService connectionManagementService(
            JdbcConnectionFactory connectionFactory,
            SqlTeacherConfiguration configuration) {
        return new JdbcConnectionManagementService(connectionFactory, configuration.database());
    }

    @Bean
    public DatabaseConnectionTestService databaseConnectionTestService(
            JdbcConnectionFactory connectionFactory) {
        return new JdbcDatabaseConnectionTestService(connectionFactory, Duration.ofSeconds(5));
    }

    @Bean(destroyMethod = "close")
    public DatabaseCredentialSession databaseCredentialSession() {
        return new InMemoryDatabaseCredentialSession();
    }

    @Bean
    public JdbcConnectionProvider jdbcConnectionProvider(
            JdbcConnectionFactory connectionFactory,
            ConnectionManagementService connectionManagementService,
            DatabaseCredentialSession credentialSession) {
        return new ProfileAwareJdbcConnectionProvider(
            connectionFactory,
            connectionManagementService,
            credentialSession
        );
    }

    @Bean
    public SqlExecutionService sqlExecutionService(
            JdbcConnectionProvider connectionProvider,
            SqlResultMapper resultMapper,
            SqlRiskAnalysisService riskAnalysisService,
            SqlSafetyModeService safetyModeService,
            LearningEventService learningEventService) {
        return new JdbcSqlExecutionService(
            connectionProvider, resultMapper, riskAnalysisService, safetyModeService, learningEventService
        );
    }

    @Bean
    public DatabaseMetadataService databaseMetadataService(JdbcConnectionProvider connectionProvider) {
        return new JdbcDatabaseMetadataService(connectionProvider);
    }

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
    public ExerciseManagementService exerciseManagementService(JdbcConnectionFactory connectionFactory) {
        return new JdbcExerciseManagementService(connectionFactory);
    }

    @Bean
    public ExerciseCatalogService exerciseCatalogService(ExerciseManagementService managementService) {
        return new JdbcExerciseCatalogService(managementService);
    }

    @Bean
    public CourseMapService courseMapService(JdbcConnectionFactory connectionFactory) {
        return new JdbcCourseMapService(connectionFactory);
    }

    @Bean
    public DeterministicSqlExerciseEvaluationService deterministicSqlExerciseEvaluationService(
            SqlRiskAnalysisService riskAnalysisService,
            SqlTeacherConfiguration configuration) {
        return new DeterministicSqlExerciseEvaluationService(riskAnalysisService, configuration);
    }

    @Bean
    public ActivityEvaluator<?, ?> sqlActivityEvaluator(
            DeterministicSqlExerciseEvaluationService deterministicEvaluator) {
        return new SqlActivityEvaluator(deterministicEvaluator);
    }

    @Bean
    public ActivityEvaluator<?, ?> quizActivityEvaluator() {
        return new QuizActivityEvaluator();
    }

    @Bean
    public ActivityEvaluator<?, ?> traceActivityEvaluator() {
        return new TraceActivityEvaluator();
    }

    @Bean
    public ActivityEvaluator<?, ?> simulationActivityEvaluator() {
        return new SimulationActivityEvaluator();
    }

    @Bean
    public ActivityEvaluator<?, ?> labActivityEvaluator() {
        return new LabActivityEvaluator();
    }

    @Bean
    public ActivityEvaluator<?, ?> readingActivityEvaluator() {
        return new ReadingActivityEvaluator();
    }

    @Bean
    public ActivityEvaluator<?, ?> projectActivityEvaluator() {
        return new ProjectActivityEvaluator();
    }

    @Bean
    public CodeRunner codeRunner() {
        return new WslSandboxCodeRunner();
    }

    @Bean
    public ActivityEvaluator<?, ?> codeActivityEvaluator(@Qualifier("codeRunner") CodeRunner runner) {
        return new CodeActivityEvaluator(runner);
    }

    @Bean
    public ActivityEvaluationDispatcher activityEvaluationDispatcher(
            List<ActivityEvaluator<?, ?>> evaluators) {
        return new DefaultActivityEvaluationDispatcher(evaluators);
    }

    @Bean
    public ActivityLearningService activityLearningService(
            JdbcConnectionFactory connectionFactory,
            LearningEventOwnerProvider ownerProvider,
            LearningEventService learningEventService,
            ActivityEvaluationDispatcher dispatcher) {
        return new JdbcActivityLearningService(
            connectionFactory, ownerProvider, learningEventService, dispatcher
        );
    }

    @Bean
    public ActivityReviewService activityReviewService(JdbcConnectionFactory connectionFactory) {
        return new JdbcActivityReviewService(connectionFactory);
    }

    @Bean
    public ProjectPortfolioService projectPortfolioService(JdbcConnectionFactory connectionFactory,
                                                            LearningEventOwnerProvider ownerProvider) {
        return new JdbcProjectPortfolioService(connectionFactory, ownerProvider);
    }

    @Bean
    @Primary
    public SqlExerciseEvaluationService sqlExerciseEvaluationService(
            ActivityEvaluationDispatcher dispatcher) {
        return new ActivityBackedSqlExerciseEvaluationService(dispatcher);
    }

    @Bean(destroyMethod = "shutdown")
    public ExercisePracticeService exercisePracticeService(
            JdbcConnectionFactory connectionFactory,
            ExerciseManagementService managementService,
            SqlRiskAnalysisService riskAnalysisService,
            SqlExerciseEvaluationService evaluationService,
            SqlResultMapper resultMapper,
            SqlTeacherConfiguration configuration,
            SqlSafetyModeService safetyModeService,
            LearningEventService learningEventService,
            LearningEventOwnerProvider ownerProvider) {
        return new JdbcExercisePracticeService(
            connectionFactory,
            managementService,
            riskAnalysisService,
            evaluationService,
            resultMapper,
            configuration,
            safetyModeService,
            learningEventService,
            ownerProvider
        );
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
    public ExerciseProgressService exerciseProgressService(JdbcConnectionFactory connectionFactory) {
        return new JdbcExerciseProgressService(connectionFactory);
    }

    @Bean
    public LearningAnalyticsService learningAnalyticsService(JdbcConnectionFactory connectionFactory) {
        return new JdbcLearningAnalyticsService(connectionFactory);
    }

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

    @Bean
    public DataMaintenanceService dataMaintenanceService(JdbcConnectionFactory connectionFactory) {
        return new JdbcDataMaintenanceService(connectionFactory);
    }

    @Bean
    public ApplicationBackupService applicationBackupService(SqlTeacherConfiguration configuration) {
        return new SqliteApplicationBackupService(configuration);
    }

    @Bean
    public AssignmentDeliveryService assignmentDeliveryService(ObjectProvider<CloudApiClient> apiProvider,
                                                               ObjectProvider<CloudSessionService> sessionProvider,
                                                               SqlTeacherConfiguration configuration) {
        return new AssignmentDeliveryService() {
            private volatile AssignmentDeliveryService cachedDelegate;

            @Override
            public com.sqlteacher.application.collaboration.AssignmentDeliveryResult deliver(
                    String classroomId, String assignmentId, boolean passed, String errorCode,
                    java.time.Instant completedAt) {
                return delegate().deliver(classroomId, assignmentId, passed, errorCode, completedAt);
            }

            @Override
            public RetrySummary retryPending() {
                return delegate().retryPending();
            }

            @Override
            public int pendingCount() {
                return delegate().pendingCount();
            }

            private AssignmentDeliveryService delegate() {
                AssignmentDeliveryService service = cachedDelegate;
                if (service != null) return service;
                synchronized (this) {
                    if (cachedDelegate == null) {
                        CloudApiClient api = apiProvider.getIfAvailable();
                        CloudSessionService sessions = sessionProvider.getIfAvailable();
                        if (api == null || sessions == null) {
                            throw new IllegalStateException("Cloud assignment delivery is unavailable in this runtime");
                        }
                        cachedDelegate = new JdbcAssignmentDeliveryService(
                            api, sessions, configuration.database().appDatabasePath());
                    }
                    return cachedDelegate;
                }
            }
        };
    }

    @Bean
    public TeachingContentCache teachingContentCache(SqlTeacherConfiguration configuration) {
        return new JdbcTeachingContentCache(configuration.database().appDatabasePath());
    }

    @Bean
    public InterventionService interventionService(ObjectProvider<CloudApiClient> apiProvider,
                                                   ObjectProvider<CloudSessionService> sessionProvider,
                                                   SqlTeacherConfiguration configuration) {
        return new InterventionService() {
            private volatile InterventionService cachedDelegate;

            @Override
            public java.util.List<com.sqlteacher.application.learning.InterventionCandidate> refreshAuthorized() {
                return delegate().refreshAuthorized();
            }

            @Override
            public void updateStatus(String candidateId,
                                     com.sqlteacher.application.learning.InterventionStatus status) {
                delegate().updateStatus(candidateId, status);
            }

            @Override
            public String exportCsv(java.util.List<com.sqlteacher.application.learning.InterventionCandidate> items) {
                return delegate().exportCsv(items);
            }

            private InterventionService delegate() {
                InterventionService service = cachedDelegate;
                if (service != null) return service;
                synchronized (this) {
                    if (cachedDelegate == null) {
                        CloudApiClient api = apiProvider.getIfAvailable();
                        CloudSessionService sessions = sessionProvider.getIfAvailable();
                        if (api == null || sessions == null) {
                            throw new IllegalStateException("云端教师干预服务当前不可用");
                        }
                        cachedDelegate = new DefaultInterventionService(api, sessions,
                            configuration.database().appDatabasePath());
                    }
                    return cachedDelegate;
                }
            }
        };
    }

    @Bean
    public StudentLearningQueueService studentLearningQueueService(LearningDiagnosisService diagnosis,
                                                                   ObjectProvider<CloudApiClient> apiProvider,
                                                                   ObjectProvider<CloudSessionService> sessionProvider,
                                                                   StudyPlanCache planCache) {
        return new StudentLearningQueueService() {
            @Override public com.sqlteacher.application.learning.StudentLearningQueue refresh() {
                return delegate().refresh();
            }
            @Override public void dismiss(com.sqlteacher.application.learning.StudentLearningQueueItem item) {
                delegate().dismiss(item);
            }
            @Override public void complete(com.sqlteacher.application.learning.StudentLearningQueueItem item) {
                delegate().complete(item);
            }
            private StudentLearningQueueService delegate() {
                CloudApiClient api = apiProvider.getIfAvailable();
                CloudSessionService sessions = sessionProvider.getIfAvailable();
                if (api == null || sessions == null) {
                    return new StudentLearningQueueService() {
                        @Override public com.sqlteacher.application.learning.StudentLearningQueue refresh() {
                            var dashboard = diagnosis.refresh();
                            return new com.sqlteacher.application.learning.StudentLearningQueue(dashboard,
                                dashboard.actions().stream().map(action ->
                                    new com.sqlteacher.application.learning.StudentLearningQueueItem(action, null, "")).toList(), false);
                        }
                        @Override public void dismiss(com.sqlteacher.application.learning.StudentLearningQueueItem item) {
                            diagnosis.dismissAction(item.action().id());
                        }
                        @Override public void complete(com.sqlteacher.application.learning.StudentLearningQueueItem item) {
                            diagnosis.dismissAction(item.action().id());
                        }
                    };
                }
                return new DefaultStudentLearningQueueService(diagnosis, api, sessions, planCache);
            }
        };
    }
}
