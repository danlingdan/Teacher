package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.collaboration.AssignmentDeliveryService;
import com.sqlteacher.application.collaboration.CloudCapabilityApi;
import com.sqlteacher.application.collaboration.CloudClassroomApi;
import com.sqlteacher.application.collaboration.CloudPlanningApi;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.TeachingContentCache;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.learning.InterventionService;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.StudentLearningQueueService;
import com.sqlteacher.application.planning.StudyPlanCache;
import com.sqlteacher.infrastructure.cloud.JdbcAssignmentDeliveryService;
import com.sqlteacher.infrastructure.cloud.JdbcTeachingContentCache;
import com.sqlteacher.infrastructure.cloud.DefaultInterventionService;
import com.sqlteacher.infrastructure.cloud.DefaultStudentLearningQueueService;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cloud-collaboration bridges for classroom assignments, teaching content cache, teacher
 * interventions, and the student learning queue (v3.4.0 REF-20 split of
 * DatabaseServiceConfig). The lazy delegates keep the desktop flow runnable when the
 * narrow cloud ports are absent from the context.
 */
@Configuration
public class DatabaseCollaborationServiceConfig {

    @Bean
    public AssignmentDeliveryService assignmentDeliveryService(ObjectProvider<CloudClassroomApi> apiProvider,
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
                        CloudClassroomApi api = apiProvider.getIfAvailable();
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
    public InterventionService interventionService(ObjectProvider<CloudClassroomApi> apiProvider,
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
                        CloudClassroomApi api = apiProvider.getIfAvailable();
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
                                                                   ObjectProvider<CloudCapabilityApi> capabilityProvider,
                                                                   ObjectProvider<CloudClassroomApi> classroomProvider,
                                                                   ObjectProvider<CloudPlanningApi> planningProvider,
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
                CloudCapabilityApi capabilities = capabilityProvider.getIfAvailable();
                CloudClassroomApi classrooms = classroomProvider.getIfAvailable();
                CloudPlanningApi planning = planningProvider.getIfAvailable();
                CloudSessionService sessions = sessionProvider.getIfAvailable();
                if (capabilities == null || classrooms == null || planning == null || sessions == null) {
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
                return new DefaultStudentLearningQueueService(diagnosis, capabilities, classrooms, planning,
                    sessions, planCache);
            }
        };
    }
}
