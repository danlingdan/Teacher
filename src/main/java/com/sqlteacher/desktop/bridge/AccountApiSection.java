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
            "account.password.change", "account.password.reset.request", "account.password.reset",
            "account.email.bind", "account.email.verify", "account.profile.update",
            "account.role.redeem",
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
            case "account.password.reset" -> accountPasswordReset(params, cancellation);
            case "account.email.bind" -> accountEmailBind(params, cancellation);
            case "account.email.verify" -> accountEmailVerify(params, cancellation);
            case "account.profile.update" -> accountProfileUpdate(params, cancellation);
            case "account.role.redeem" -> accountRoleRedeem(params, cancellation);
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

    /** v3.8.0 ACC-S2: completes the reset with the 6-digit code from the mail; revokes all sessions. */
    private JsonNode accountPasswordReset(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        char[] newPassword = requiredRawText(params, "newPassword", 1_024).toCharArray();
        try {
            context().getBean(CloudAuthApi.class).resetPassword(requiredText(params, "email", 320),
                requiredText(params, "code", 64), newPassword);
            return mapper.createObjectNode().put("reset", true);
        } finally {
            Arrays.fill(newPassword, '\0');
        }
    }

    /** v3.8.0 ACC-S2: mails a verification code to the address being bound (bind or re-bind). */
    private JsonNode accountEmailBind(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        context().getBean(CloudAccountApi.class).requestEmailBinding(session.accessToken(),
            requiredText(params, "email", 320));
        return mapper.createObjectNode().put("sent", true);
    }

    /** v3.8.0 ACC-S2: confirms the mailed code; the pending address becomes the verified account email. */
    private JsonNode accountEmailVerify(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        context().getBean(CloudAccountApi.class).confirmEmailBinding(session.accessToken(),
            requiredText(params, "code", 64));
        return currentSession();
    }

    /** v3.8.0 ACC-S3: display-name change; the session is re-issued so the local identity stays fresh. */
    private JsonNode accountProfileUpdate(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        context().getBean(CloudAccountApi.class).updateProfile(session.accessToken(),
            requiredText(params, "displayName", 160));
        var refreshed = context().getBean(CloudAuthApi.class).refresh(session.refreshToken());
        context().getBean(CloudSessionService.class).signIn(refreshed);
        return currentSession();
    }

    /**
     * v3.8.0 ACC-S4 (decision point 1, plan B): redeems a one-time teacher upgrade code; the
     * session is re-issued so the granted role takes effect immediately without re-login.
     */
    private JsonNode accountRoleRedeem(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var session = requireCloudSession();
        context().getBean(CloudAccountApi.class).redeemRoleCode(session.accessToken(),
            requiredText(params, "code", 64));
        var refreshed = context().getBean(CloudAuthApi.class).refresh(session.refreshToken());
        context().getBean(CloudSessionService.class).signIn(refreshed);
        return currentSession();
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
