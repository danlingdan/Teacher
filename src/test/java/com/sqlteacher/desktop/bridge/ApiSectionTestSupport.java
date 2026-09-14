package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sqlteacher.application.collaboration.AuthenticatedUser;
import com.sqlteacher.application.collaboration.CloudAuthenticationService;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.application.collaboration.UserRole;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * v3.4.0 REF-9: shared plumbing for section-level unit tests. Sections resolve application
 * ports through the host's Spring core; the hosts built here register hand-made fakes as
 * singletons in an otherwise empty context, so every section stays independently testable
 * without booting the real application core (SQLite files, Ollama, HTTP).
 */
final class ApiSectionTestSupport implements ApiSectionHost, AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final AnnotationConfigApplicationContext context;

    private ApiSectionTestSupport(Object... beans) {
        this.context = new AnnotationConfigApplicationContext();
        this.context.refresh();
        ConfigurableListableBeanFactory factory = this.context.getBeanFactory();
        for (int index = 0; index < beans.length; index++) {
            factory.registerSingleton("section-test-fake-" + index, beans[index]);
        }
    }

    /** Host whose "Spring core" is an empty context holding exactly the given fake beans. */
    static ApiSectionTestSupport hostWithBeans(Object... beans) {
        return new ApiSectionTestSupport(beans);
    }

    /** Host proving a code path never touches the Spring core. */
    static ApiSectionHost hostWithoutCore() {
        return new ApiSectionHost() {
            @Override public ObjectMapper mapper() {
                return new ObjectMapper().registerModule(new JavaTimeModule());
            }

            @Override public AnnotationConfigApplicationContext context() {
                throw new IllegalStateException("test host has no Spring core");
            }

            @Override public boolean coreInitialized() {
                return false;
            }
        };
    }

    @Override public ObjectMapper mapper() {
        return mapper;
    }

    @Override public AnnotationConfigApplicationContext context() {
        return context;
    }

    @Override public boolean coreInitialized() {
        return true;
    }

    @Override public void close() {
        context.close();
    }

    /**
     * Dynamic-proxy fake of an application port: only the named methods are stubbed, every
     * other call fails loudly so tests cannot silently drift past an unstubbed interaction.
     */
    static <T> T fake(Class<T> port, Map<String, Function<Object[], Object>> handlers) {
        Object proxy = Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] { port },
            (instance, method, args) -> {
                Function<Object[], Object> handler = handlers.get(method.getName());
                if (handler == null) {
                    throw new UnsupportedOperationException(
                        port.getSimpleName() + "." + method.getName() + " is not stubbed");
                }
                return handler.apply(args == null ? new Object[0] : args);
            });
        return port.cast(proxy);
    }

    /** In-memory session store matching the desktop rule that only the active session is kept. */
    static final class FakeCloudSessions implements CloudSessionService {
        private CloudAuthenticationService.Session current;
        int signInCount;
        int signOutCount;

        @Override public Optional<CloudAuthenticationService.Session> current() {
            return Optional.ofNullable(current);
        }

        @Override public void signIn(CloudAuthenticationService.Session session) {
            current = session;
            signInCount++;
        }

        @Override public void signOut() {
            current = null;
            signOutCount++;
        }
    }

    /** A signed-in cloud session for the given roles; the token never leaves the test. */
    static CloudAuthenticationService.Session session(String userId, String displayName, UserRole... roles) {
        return new CloudAuthenticationService.Session(
            "token-" + userId,
            Instant.now().plusSeconds(3_600),
            new AuthenticatedUser(userId, userId + "@sqlteacher.test", displayName, Set.of(roles)));
    }
}
