package com.sqlteacher.infrastructure.spring;

import com.sqlteacher.application.collaboration.CloudArtifactSyncService;
import com.sqlteacher.application.collaboration.CloudAuthApi;
import com.sqlteacher.application.collaboration.CloudBankApi;
import com.sqlteacher.application.collaboration.CloudCapabilityApi;
import com.sqlteacher.application.collaboration.CloudLearningSyncService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.CloudSyncApi;
import com.sqlteacher.application.config.SqlTeacherConfiguration;
import com.sqlteacher.application.event.LearningEventOwnerProvider;
import com.sqlteacher.application.event.LearningEventQueryService;
import com.sqlteacher.application.event.LearningEventRecorder;
import com.sqlteacher.infrastructure.cloud.DefaultCloudLearningSyncService;
import com.sqlteacher.infrastructure.cloud.HttpCloudApiClient;
import com.sqlteacher.infrastructure.cloud.JdbcCloudArtifactSyncService;
import com.sqlteacher.infrastructure.cloud.PersistentCloudSessionService;
import com.sqlteacher.infrastructure.cloud.WindowsDpapiCloudSessionStore;
import com.sqlteacher.infrastructure.database.ExerciseBankSyncService;
import com.sqlteacher.infrastructure.database.JdbcConnectionFactory;
import com.sqlteacher.infrastructure.system.ExerciseBankAutoCheckService;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferencesStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * Cloud connectivity: base URI, the single {@link HttpCloudApiClient} behind every narrow
 * cloud port, persistent session storage, learning/artifact sync, and the distributed
 * exercise-bank sync loop (v3.4.0 REF-20 split of SqlTeacherApplicationConfig).
 */
@Configuration
public class CloudCollaborationServiceConfig {

    @Bean
    public URI cloudBaseUri() {
        return URI.create(System.getProperty(
            "sqlteacher.cloud.base-url", SqlTeacherApplicationConfig.DEFAULT_CLOUD_BASE_URL
        ));
    }

    /**
     * Single cloud client instance; it implements every narrow cloud port
     * (v3.4.0 REF-4), so consumers inject the port they need by type.
     */
    @Bean
    public HttpCloudApiClient cloudApiClient(URI cloudBaseUri) {
        return new HttpCloudApiClient(cloudBaseUri);
    }

    @Bean
    public CloudSessionService cloudSessionService(SqlTeacherConfiguration configuration, CloudAuthApi api) {
        return new PersistentCloudSessionService(
            new WindowsDpapiCloudSessionStore(configuration.dataDirectory().resolve("cloud-session.dat")), api
        );
    }

    @Bean
    public CloudLearningSyncService cloudLearningSyncService(CloudSyncApi api, CloudSessionService sessions,
            LearningEventQueryService query, LearningEventRecorder recorder, SqlTeacherConfiguration configuration) {
        return new DefaultCloudLearningSyncService(api, sessions, query, recorder,
            configuration.dataDirectory().resolve("cloud-state"));
    }

    @Bean
    public CloudArtifactSyncService cloudArtifactSyncService(JdbcConnectionFactory connections,
            LearningEventOwnerProvider owners, CloudCapabilityApi capabilities, CloudSyncApi sync,
            CloudSessionService sessions) {
        return new JdbcCloudArtifactSyncService(connections, owners, capabilities, sync, sessions);
    }

    @Bean
    public ExerciseBankSyncService exerciseBankSyncService(
            CloudBankApi bankApi, SqlTeacherConfiguration configuration) {
        return new ExerciseBankSyncService(
            bankApi, configuration.database().appDatabasePath().toString());
    }

    @Bean public ExerciseBankPreferencesStore exerciseBankPreferencesStore(SqlTeacherConfiguration configuration) {
        return new ExerciseBankPreferencesStore(configuration.dataDirectory());
    }

    @Bean(destroyMethod = "close")
    public ExerciseBankAutoCheckService exerciseBankAutoCheckService(
            ExerciseBankSyncService syncService, ExerciseBankPreferencesStore store) {
        ExerciseBankAutoCheckService service = new ExerciseBankAutoCheckService(syncService, store);
        service.start();
        return service;
    }
}
