package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.activity.ActivityBackedSqlExerciseEvaluationService;
import com.sqlteacher.application.activity.ActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.ActivityEvaluator;
import com.sqlteacher.application.activity.ActivityLearningService;
import com.sqlteacher.application.activity.ActivityReviewService;
import com.sqlteacher.application.activity.CodeActivityEvaluator;
import com.sqlteacher.application.activity.DefaultActivityEvaluationDispatcher;
import com.sqlteacher.application.activity.LabActivityEvaluator;
import com.sqlteacher.application.activity.ProjectActivityEvaluator;
import com.sqlteacher.application.activity.ProjectPortfolioService;
import com.sqlteacher.application.activity.QuizActivityEvaluator;
import com.sqlteacher.application.activity.ReadingActivityEvaluator;
import com.sqlteacher.application.activity.SimulationActivityEvaluator;
import com.sqlteacher.application.activity.SqlActivityEvaluator;
import com.sqlteacher.application.activity.TraceActivityEvaluator;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExerciseManagementService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.application.exercise.ExerciseProgressService;
import com.sqlteacher.application.exercise.SqlExerciseEvaluationService;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import com.sqlteacher.application.runner.CodeRunner;
import com.sqlteacher.infrastructure.database.DeterministicSqlExerciseEvaluationService;
import com.sqlteacher.infrastructure.database.JdbcActivityLearningService;
import com.sqlteacher.infrastructure.database.JdbcActivityReviewService;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.database.JdbcExerciseCatalogService;
import com.sqlteacher.infrastructure.database.JdbcExerciseManagementService;
import com.sqlteacher.infrastructure.database.JdbcExercisePracticeService;
import com.sqlteacher.infrastructure.database.JdbcExerciseProgressService;
import com.sqlteacher.infrastructure.database.JdbcCourseMapService;
import com.sqlteacher.infrastructure.database.JdbcProjectPortfolioService;
import com.sqlteacher.infrastructure.database.SqlResultMapper;
import com.sqlteacher.infrastructure.runner.WslSandboxCodeRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Exercise, activity, and course-map services backed by the local SQLite application
 * database (v3.4.0 REF-20 split of DatabaseServiceConfig).
 */
@Configuration
public class DatabaseExerciseServiceConfig {

    @Bean
    public ExerciseManagementService exerciseManagementService(JdbcConnectionFactory connectionFactory) {
        return new JdbcExerciseManagementService(connectionFactory);
    }

    @Bean
    public ExerciseCatalogService exerciseCatalogService(
        JdbcConnectionFactory connectionFactory,
        ExerciseManagementService managementService,
        LearningEventOwnerProvider ownerProvider
    ) {
        return new JdbcExerciseCatalogService(connectionFactory, managementService, ownerProvider);
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
            LearningEventService learningEventService,
            LearningEventOwnerProvider ownerProvider) {
        return new JdbcExercisePracticeService(
            connectionFactory,
            managementService,
            riskAnalysisService,
            evaluationService,
            resultMapper,
            configuration,
            learningEventService,
            ownerProvider
        );
    }

    @Bean
    public ExerciseProgressService exerciseProgressService(JdbcConnectionFactory connectionFactory,
                                                           LearningEventOwnerProvider ownerProvider) {
        return new JdbcExerciseProgressService(connectionFactory, ownerProvider);
    }
}
