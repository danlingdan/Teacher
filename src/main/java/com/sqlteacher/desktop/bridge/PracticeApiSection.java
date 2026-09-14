package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.exercise.ExerciseAttemptResult;
import com.sqlteacher.application.exercise.ExerciseCatalogService;
import com.sqlteacher.application.exercise.ExercisePracticeService;
import com.sqlteacher.infrastructure.database.ExerciseBankSyncService;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferences;
import com.sqlteacher.infrastructure.system.ExerciseBankPreferencesStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: practice sessions, wrong book, recommendations, and exercise bank sync. */
final class PracticeApiSection extends ApiSection {

    PracticeApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of(
            "practice.catalog", "practice.preview", "practice.start", "practice.run", "practice.submit",
            "practice.hint", "practice.reset", "practice.close", "practice.wrongbook", "practice.recommend",
            "practice.bank.check", "practice.bank.update", "practice.bank.channels", "practice.bank.notice"
        );
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "practice.catalog" -> practiceCatalog(params, cancellation);
            case "practice.preview" -> practicePreview(params, cancellation);
            case "practice.start" -> practiceStart(params, cancellation);
            case "practice.run" -> practiceAttempt(params, cancellation, false);
            case "practice.submit" -> practiceAttempt(params, cancellation, true);
            case "practice.hint" -> practiceHint(params, cancellation);
            case "practice.reset" -> practiceReset(params, cancellation);
            case "practice.close" -> practiceClose(params, cancellation);
            case "practice.wrongbook" -> practiceWrongBook(cancellation);
            case "practice.recommend" -> practiceRecommend(cancellation);
            case "practice.bank.check" -> practiceBankCheck(cancellation);
            case "practice.bank.update" -> practiceBankUpdate(cancellation, events);
            case "practice.bank.channels" -> practiceBankChannels(cancellation);
            case "practice.bank.notice" -> practiceBankNotice(cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private JsonNode practiceCatalog(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var service = context().getBean(ExerciseCatalogService.class);
        if (!params.has("pageSize")) {
            var items = service.listAvailableExercises();
            return mapper.createObjectNode().set("items", mapper.valueToTree(items));
        }
        int pageSize = Math.min(500, Math.max(1, params.path("pageSize").asInt(50)));
        int page = Math.max(0, params.path("page").asInt(0));
        var result = service.listExercises(
            page, pageSize,
            params.path("q").asText(""),
            params.path("difficulty").asText(""),
            params.path("status").asText("")
        );
        return mapper.valueToTree(result);
    }

    private JsonNode practicePreview(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var exercise = context().getBean(ExerciseCatalogService.class)
            .findAvailableExercise(requiredText(params, "exerciseId", 128))
            .orElseThrow(() -> new IllegalArgumentException("Exercise is not available"));
        return mapper.valueToTree(exercise);
    }

    private JsonNode practiceStart(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .start(requiredText(params, "exerciseId", 128)));
    }

    private JsonNode practiceAttempt(JsonNode params, CancellationToken cancellation, boolean submit) {
        cancellation.throwIfCancelled();
        String sessionId = requiredText(params, "sessionId", 128);
        String sql = requiredText(params, "answer", 256 * 1024);
        ExerciseAttemptResult attempt = submit
            ? context().getBean(ExercisePracticeService.class).submit(sessionId, sql)
            : context().getBean(ExercisePracticeService.class).run(sessionId, sql);
        cancellation.throwIfCancelled();
        return mapper.valueToTree(attempt);
    }

    private JsonNode practiceHint(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .requestHint(requiredText(params, "sessionId", 128)));
    }

    private JsonNode practiceReset(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        return mapper.valueToTree(context().getBean(ExercisePracticeService.class)
            .reset(requiredText(params, "sessionId", 128)));
    }

    private JsonNode practiceClose(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String sessionId = requiredText(params, "sessionId", 128);
        context().getBean(ExercisePracticeService.class).close(sessionId);
        return mapper.createObjectNode().put("closed", true).put("sessionId", sessionId);
    }

    private JsonNode practiceWrongBook(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var items = context().getBean(ExerciseCatalogService.class).wrongBook();
        return mapper.createObjectNode().set("items", mapper.valueToTree(items));
    }

    private JsonNode practiceRecommend(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var recommendation = context().getBean(ExerciseCatalogService.class).recommendNextExercise();
        ObjectNode node = mapper.createObjectNode();
        if (recommendation.isPresent()) {
            node.set("recommendation", mapper.valueToTree(recommendation.get()));
        } else {
            node.putNull("recommendation");
        }
        return node;
    }

    private JsonNode practiceBankCheck(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        // 按订阅频道逐一检查（W4.2）；离线/失败静默降级，不影响本地练习。
        var sync = context().getBean(ExerciseBankSyncService.class);
        List<String> channels = context().getBean(ExerciseBankPreferencesStore.class)
            .load().subscribedChannels();
        int pendingBlocks = 0;
        int appliedVersion = Integer.MAX_VALUE;
        int serverVersion = 0;
        boolean upToDate = true;
        List<String> messages = new ArrayList<>();
        for (String channel : channels) {
            var status = sync.check(channel);
            pendingBlocks += status.pendingBlocks();
            appliedVersion = Math.min(appliedVersion, status.appliedVersion());
            serverVersion = Math.max(serverVersion, status.serverVersion());
            upToDate = upToDate && status.upToDate();
            if (!status.upToDate() && status.pendingBlocks() > 0) {
                messages.add("频道 " + channel + "：" + status.message());
            }
        }
        if (messages.isEmpty()) {
            messages.add("题库已是最新。");
        }
        return mapper.valueToTree(new ExerciseBankSyncService.BankUpdateStatus(
            appliedVersion == Integer.MAX_VALUE ? 0 : appliedVersion,
            serverVersion, pendingBlocks, upToDate && pendingBlocks == 0,
            String.join("；", messages)));
    }

    private JsonNode practiceBankUpdate(CancellationToken cancellation, Consumer<LocalAppEvent> events) {
        cancellation.throwIfCancelled();
        var sync = context().getBean(ExerciseBankSyncService.class);
        var store = context().getBean(ExerciseBankPreferencesStore.class);
        List<String> channels = store.load().subscribedChannels();
        int appliedVersion = 0;
        int updatedDatasets = 0;
        int updatedExercises = 0;
        boolean applied = false;
        List<String> messages = new ArrayList<>();
        for (String channel : channels) {
            var result = sync.update(channel,
                progress -> emit(events, "import.progress", "phase", progress));
            applied = applied || result.applied();
            appliedVersion = Math.max(appliedVersion, result.appliedVersion());
            updatedDatasets += result.updatedDatasets();
            updatedExercises += result.updatedExercises();
            if (result.applied()) {
                messages.add("频道 " + channel + "：" + result.message());
            }
        }
        if (applied) {
            // 更新落地后清除定时检查产生的待更新通知。
            var prefs = store.load();
            store.save(new ExerciseBankPreferences(
                prefs.autoCheckEnabled(), prefs.subscribedChannels(), null));
        } else {
            messages.add("题库已是最新。");
        }
        return mapper.valueToTree(new ExerciseBankSyncService.BankUpdateResult(
            applied, appliedVersion, updatedDatasets, updatedExercises,
            String.join("；", messages)));
    }

    /** Lists server-known channels for the subscription UI; unavailable server means empty. */
    private JsonNode practiceBankChannels(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        try {
            var items = context().getBean(com.sqlteacher.application.collaboration.CloudBankApi.class)
                .fetchExerciseBankChannels();
            return mapper.createObjectNode().set("items", mapper.valueToTree(items));
        } catch (RuntimeException error) {
            return mapper.createObjectNode().set("items", mapper.createArrayNode());
        }
    }

    /** Returns the pending auto-check notice (display only); null when there is none. */
    private JsonNode practiceBankNotice(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var notice = context().getBean(ExerciseBankPreferencesStore.class).load().pendingNotice();
        if (notice == null) {
            return mapper.createObjectNode().putNull("notice");
        }
        return mapper.createObjectNode().set("notice", mapper.valueToTree(notice));
    }
}
