package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.sqlteacher.application.support.DiagnosticSelection;
import com.sqlteacher.application.support.ProblemReportDraft;
import com.sqlteacher.application.support.ProblemReportReceipt;
import com.sqlteacher.application.support.ProblemReportService;
import com.sqlteacher.application.system.GeneralSoftwareService;
import com.sqlteacher.application.system.GeneralSoftwareSettings;
import com.sqlteacher.application.system.TaskSnapshot;
import com.sqlteacher.infrastructure.system.SensitiveDataRedactor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * v3.4.2 LEG-10: opt-in problem reporting. Preview and submit share one diagnostic builder so
 * the user always sees exactly what will be sent; every diagnostic block defaults to off and
 * submission works both signed-in (Bearer) and anonymously.
 */
final class SupportApiSection extends ApiSection {

    SupportApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "support.report.preview", "support.report.submit",
            "support.report.status", "support.report.withdraw", "support.report.export"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "support.report.preview" -> supportReportPreview(params, cancellation);
            case "support.report.submit" -> supportReportSubmit(params, cancellation);
            case "support.report.status" -> supportReportStatus(params, cancellation);
            case "support.report.withdraw" -> supportReportWithdraw(params, cancellation);
            case "support.report.export" -> supportReportExport(params, cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode supportReportPreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(diagnosticsFrom(params));
    }

    private JsonNode supportReportSubmit(JsonNode params, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        var core = context();
        String accessToken = currentAccessProfile().isGuest() ? null : requireCloudSession().accessToken();
        ProblemReportReceipt receipt = core.getBean(ProblemReportService.class)
            .submit(draftFrom(params), diagnosticsFrom(params), accessToken);
        cancellation.throwIfCancelled();
        return mapper.valueToTree(receipt);
    }

    private JsonNode supportReportStatus(JsonNode params, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ProblemReportService.class)
            .status(requiredText(params, "reportId", 80), requiredText(params, "queryToken", 120)));
    }

    private JsonNode supportReportWithdraw(JsonNode params, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        context().getBean(ProblemReportService.class)
            .withdraw(requiredText(params, "reportId", 80), requiredText(params, "queryToken", 120));
        return mapper.createObjectNode().put("withdrawn", true);
    }

    private JsonNode supportReportExport(JsonNode params, CancellationToken cancellation) throws Exception {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ProblemReportService.class)
            .export(requiredText(params, "reportId", 80), requiredText(params, "queryToken", 120)));
    }

    private ProblemReportDraft draftFrom(JsonNode params) {
        String idempotencyKey = params.path("idempotencyKey").asText("").trim();
        if (idempotencyKey.isEmpty()) idempotencyKey = UUID.randomUUID().toString();
        return new ProblemReportDraft(
            idempotencyKey,
            ProblemReportDraft.Type.valueOf(requiredText(params, "type", 40).toUpperCase(java.util.Locale.ROOT)),
            ProblemReportDraft.Severity.valueOf(requiredText(params, "severity", 40).toUpperCase(java.util.Locale.ROOT)),
            requiredText(params, "summary", 160),
            requiredText(params, "description", 4000),
            optionalText(params, "reproductionSteps", 4000),
            optionalText(params, "expectedResult", 2000),
            optionalText(params, "actualResult", 2000),
            optionalText(params, "contact", 254),
            selectionFrom(params),
            // Screenshot attachments stay out of this version: the domain cap is 2 MiB but the
            // bridge IPC request cap is 1 MiB, so no params field can carry one yet.
            null);
    }

    private static DiagnosticSelection selectionFrom(JsonNode params) {
        JsonNode diagnostics = params.path("diagnostics");
        return new DiagnosticSelection(
            diagnostics.path("environment").asBoolean(false),
            diagnostics.path("recentErrors").asBoolean(false),
            diagnostics.path("networkSummary").asBoolean(false),
            diagnostics.path("updateState").asBoolean(false));
    }

    private static String optionalText(JsonNode params, String field, int maxLength) {
        String value = params.path(field).asText("").trim();
        if (value.length() > maxLength) throw new IllegalArgumentException(field + " must contain at most " + maxLength + " characters");
        return value;
    }

    /** Shared by preview and submit: the sent map is always exactly the previewed map. */
    private Map<String, Object> diagnosticsFrom(JsonNode params) {
        DiagnosticSelection selection = selectionFrom(params);
        Map<String, Object> result = new LinkedHashMap<>();
        if (selection.environment()) {
            result.put("javaVersion", System.getProperty("java.version", "unknown"));
            result.put("os", System.getProperty("os.name", "unknown") + " " + System.getProperty("os.version", ""));
        }
        if (selection.recentErrors()) {
            result.put("recentErrors", context().getBean(GeneralSoftwareService.class).tasks().stream()
                .filter(task -> task.status() == TaskSnapshot.Status.FAILED)
                .limit(5)
                .map(task -> Map.of(
                    "type", task.type(),
                    "title", SensitiveDataRedactor.redact(task.title()),
                    "code", task.errorCode() == null ? "" : task.errorCode()))
                .toList());
        }
        if (selection.networkSummary()) {
            result.put("connectivity", context().getBean(GeneralSoftwareService.class).connectivitySummary());
        }
        if (selection.updateState()) {
            GeneralSoftwareSettings settings = context().getBean(GeneralSoftwareService.class).settings();
            result.put("automaticUpdateChecks", settings.automaticUpdateChecks());
            result.put("skippedVersion", settings.skippedVersion());
            result.put("updateMirrorsEnabled", settings.updateMirrorsEnabled());
        }
        return result;
    }
}
