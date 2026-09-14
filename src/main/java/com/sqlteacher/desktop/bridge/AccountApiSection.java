package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.collaboration.CloudAccountApi;
import com.sqlteacher.application.collaboration.CloudAuthApi;
import com.sqlteacher.application.collaboration.CloudSessionService;
import com.sqlteacher.infrastructure.cloud.InMemoryLearningEventOwnerContext;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: cloud account lifecycle: sign-in/out, credentials, exports, and deletion. */
final class AccountApiSection extends ApiSection {

    AccountApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "account.login", "account.register", "account.logout",
            "account.password.change", "account.password.reset.request",
            "account.sessions", "account.session.revoke",
            "account.export.request", "account.export.get",
            "account.deletion.request", "account.deletion.cancel", "account.deletion.status"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "account.login" -> accountLogin(params, cancellation);
            case "account.register" -> accountRegister(params, cancellation);
            case "account.logout" -> accountLogout(cancellation);
            case "account.password.change" -> accountPasswordChange(params, cancellation);
            case "account.password.reset.request" -> accountPasswordResetRequest(params, cancellation);
            case "account.sessions" -> accountSessions(cancellation);
            case "account.session.revoke" -> accountSessionRevoke(params, cancellation);
            case "account.export.request" -> accountExportRequest(cancellation);
            case "account.export.get" -> accountExportGet(params, cancellation);
            case "account.deletion.request" -> accountDeletionRequest(cancellation);
            case "account.deletion.cancel" -> accountDeletionCancel(cancellation);
            case "account.deletion.status" -> accountDeletionStatus(cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode accountLogin(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String email = requiredText(params, "email", 320);
        char[] password = requiredRawText(params, "password", 1_024).toCharArray();
        try {
            var core = context();
            var session = core.getBean(CloudAuthApi.class).login(email, password);
            cancellation.throwIfCancelled();
            core.getBean(CloudSessionService.class).signIn(session);
            core.getBean(InMemoryLearningEventOwnerContext.class).useAuthenticatedUser(session.user().id());
            return currentSession();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private JsonNode accountLogout(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        CloudSessionService sessions = core.getBean(CloudSessionService.class);
        var active = sessions.current();
        boolean remoteLogoutSucceeded = true;
        try {
            if (active.isPresent()) core.getBean(CloudAuthApi.class).logout(active.get().accessToken());
        } catch (RuntimeException error) {
            remoteLogoutSucceeded = false;
        } finally {
            sessions.signOut();
            core.getBean(InMemoryLearningEventOwnerContext.class).useGuest();
        }
        ObjectNode result = currentSession();
        result.put("remoteLogoutSucceeded", remoteLogoutSucceeded);
        return result;
    }

    private JsonNode accountRegister(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        char[] password = requiredRawText(params, "password", 1_024).toCharArray();
        try {
            var core = context();
            var session = core.getBean(CloudAuthApi.class).register(requiredText(params, "email", 320),
                requiredText(params, "displayName", 160), password);
            core.getBean(CloudSessionService.class).signIn(session);
            core.getBean(InMemoryLearningEventOwnerContext.class).useAuthenticatedUser(session.user().id());
            return currentSession();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private JsonNode accountPasswordChange(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        char[] currentPassword = requiredRawText(params, "currentPassword", 1_024).toCharArray();
        char[] newPassword = requiredRawText(params, "newPassword", 1_024).toCharArray();
        try {
            var session = requireCloudSession();
            context().getBean(CloudAuthApi.class).changePassword(session.accessToken(), currentPassword, newPassword);
            return mapper.createObjectNode().put("changed", true);
        } finally {
            Arrays.fill(currentPassword, '\0');
            Arrays.fill(newPassword, '\0');
        }
    }

    private JsonNode accountPasswordResetRequest(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        context().getBean(CloudAuthApi.class).requestPasswordReset(requiredText(params, "email", 320));
        return mapper.createObjectNode().put("accepted", true);
    }

    private JsonNode accountSessions(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().set("items", mapper.valueToTree(
            context().getBean(CloudAccountApi.class).listSessions(session.accessToken())));
    }

    private JsonNode accountSessionRevoke(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        String sessionId = requiredText(params, "sessionId", 256);
        context().getBean(CloudAccountApi.class).revokeSession(session.accessToken(), sessionId);
        return mapper.createObjectNode().put("revoked", true).put("sessionId", sessionId);
    }

    private JsonNode accountExportRequest(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudAccountApi.class).requestAccountExport(session.accessToken()));
    }

    private JsonNode accountExportGet(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.createObjectNode().put("payload", context().getBean(CloudAccountApi.class)
            .getAccountExport(session.accessToken(), requiredText(params, "taskId", 256)));
    }

    private JsonNode accountDeletionRequest(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudAccountApi.class).requestAccountDeletion(session.accessToken()));
    }

    private JsonNode accountDeletionCancel(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudAccountApi.class).cancelAccountDeletion(session.accessToken()));
    }

    private JsonNode accountDeletionStatus(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        return mapper.valueToTree(context().getBean(CloudAccountApi.class).getAccountDeletionStatus(session.accessToken()));
    }
}
