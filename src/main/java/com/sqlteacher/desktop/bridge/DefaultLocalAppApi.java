package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.connection.DatabaseConnectionProfile;
import com.sqlteacher.application.connection.DatabaseCredentialSession;
import com.sqlteacher.application.database.DatabaseInitializationService;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;
import com.sqlteacher.infrastructure.spring.SqlTeacherApplicationConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * v3.4.0 REF-8: facade of the desktop IPC bridge. The frozen {@code 3.0-v1} contract
 * ({@link LocalAppContract}) is dispatched through a registry of business-domain
 * {@link LocalAppApiSection}s. The facade keeps exactly the cross-cutting concerns:
 * the whitelist guard, the lazily initialized Spring core, the shared
 * {@link SqlConfirmationCache}, and the lifecycle.
 */
public final class DefaultLocalAppApi implements LocalAppApi, ApiSectionHost {
    public static final String CONTRACT_VERSION = LocalAppContract.VERSION;

    private final ObjectMapper mapper;
    private final SqlConfirmationCache sqlCache = new SqlConfirmationCache();
    private final List<LocalAppApiSection> sections;
    private final Map<String, LocalAppApiSection> sectionsByMethod;
    private volatile AnnotationConfigApplicationContext context;

    public DefaultLocalAppApi(ObjectMapper mapper) {
        this.mapper = mapper;
        this.sections = List.of(
            new SystemApiSection(this),
            new HomeApiSection(this),
            new KnowledgeApiSection(this),
            new ActivityApiSection(this),
            new PracticeApiSection(this),
            new DataSqlApiSection(this, sqlCache),
            new AiApiSection(this),
            new AccountApiSection(this),
            new TeachingApiSection(this),
            new CloudApiSection(this),
            new SettingsApiSection(this)
        );
        this.sectionsByMethod = buildRegistry(sections);
    }

    /** Fail fast when a section registers twice, claims a non-whitelisted method, or misses one. */
    private static Map<String, LocalAppApiSection> buildRegistry(List<LocalAppApiSection> sections) {
        Map<String, LocalAppApiSection> registry = new LinkedHashMap<>();
        for (LocalAppApiSection section : sections) {
            for (String method : section.supportedMethods()) {
                LocalAppApiSection previous = registry.putIfAbsent(method, section);
                if (previous != null || !LocalAppContract.API_METHODS.contains(method)) {
                    throw new IllegalStateException("Section registers a method outside its slice: " + method);
                }
            }
        }
        if (!registry.keySet().containsAll(LocalAppContract.API_METHODS)) {
            Set<String> missing = new TreeSet<>(LocalAppContract.API_METHODS);
            missing.removeAll(registry.keySet());
            throw new IllegalStateException("Sections do not cover whitelisted methods: " + missing);
        }
        return Map.copyOf(registry);
    }

    /** Registry keys; test-only helper proving every whitelisted method has exactly one handler. */
    Set<String> dispatchedMethods() {
        return sectionsByMethod.keySet();
    }

    @Override
    public ObjectMapper mapper() {
        return mapper;
    }

    @Override
    public boolean coreInitialized() {
        return context != null;
    }

    @Override
    public JsonNode invoke(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        if (!LocalAppContract.API_METHODS.contains(method)) {
            throw new IllegalArgumentException("Unknown local application method: " + method);
        }
        LocalAppApiSection section = sectionsByMethod.get(method);
        if (section == null) {
            throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        }
        return section.handle(method, params, cancellation, events);
    }

    /** Retained for existing callers/tests; the logic lives in {@link DataSqlApiSection}. */
    static char[] resolveTestPassword(
        DatabaseCredentialSession session,
        DatabaseConnectionProfile profile,
        char[] formPassword
    ) {
        return DataSqlApiSection.resolveTestPassword(session, profile, formPassword);
    }

    @Override
    public synchronized AnnotationConfigApplicationContext context() {
        if (context != null) return context;
        AnnotationConfigApplicationContext created = new AnnotationConfigApplicationContext(SqlTeacherApplicationConfig.class);
        try {
            created.getBean(DatabaseInitializationService.class).initialize();
            var owners = created.getBean(InMemoryLearningEventOwnerContext.class);
            created.getBean(CloudSessionService.class).current()
                .ifPresentOrElse(session -> owners.useAuthenticatedUser(session.user().id()), owners::useGuest);
            context = created;
            return created;
        } catch (RuntimeException error) {
            created.close();
            throw error;
        }
    }

    @Override
    public synchronized void close() {
        sqlCache.clear();
        if (context != null) {
            context.close();
            context = null;
        }
    }
}
