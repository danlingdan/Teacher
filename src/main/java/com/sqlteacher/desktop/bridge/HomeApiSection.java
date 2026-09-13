package com.sqlteacher.desktop.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sqlteacher.application.course.CourseMapService;
import com.sqlteacher.application.learning.LearningDiagnosisService;
import com.sqlteacher.application.learning.MasteryLevel;

import java.util.Set;
import java.util.function.Consumer;

/** v3.4.0 REF-8: local home dashboard and course workspace landing surface. */
final class HomeApiSection extends ApiSection {

    HomeApiSection(ApiSectionHost host) {
        super(host);
    }

    @Override
    public Set<String> supportedMethods() {
        return Set.of("home.summary", "home.action.dismiss", "course.workspace");
    }

    @Override
    public JsonNode handle(String method, JsonNode params, CancellationToken cancellation,
                           Consumer<LocalAppEvent> events) throws Exception {
        return switch (method) {
            case "home.summary" -> homeSummary(cancellation);
            case "home.action.dismiss" -> homeActionDismiss(params, cancellation);
            case "course.workspace" -> courseWorkspace(cancellation);
            default -> throw new IllegalStateException("Method whitelist and dispatcher are inconsistent");
        };
    }

    private ObjectNode homeSummary(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var applicationContext = context();
        cancellation.throwIfCancelled();
        // The default home path is local-only. Cloud refresh and synchronization require an explicit
        // action in the cloud workspace and must never delay the deterministic offline dashboard.
        var dashboard = applicationContext.getBean(LearningDiagnosisService.class).refresh();
        ObjectNode result = mapper.createObjectNode();
        result.put("ownerId", dashboard.ownerId());
        result.put("policyVersion", dashboard.policyVersion());
        result.put("knowledgePointCount", dashboard.mastery().size());
        result.put("needsPracticeCount", dashboard.mastery().stream()
            .filter(item -> item.level() == MasteryLevel.NEEDS_PRACTICE).count());
        result.put("cloudAvailable", false);
        result.put("calculationMillis", dashboard.calculationTime().toMillis());
        ArrayNode actions = result.putArray("actions");
        dashboard.actions().forEach(item -> {
            ObjectNode action = actions.addObject();
            action.put("id", item.id());
            action.put("type", item.type().name());
            action.put("title", item.title());
            action.put("description", item.description());
            action.put("priority", item.priority());
            action.put("exerciseId", item.exerciseId());
            action.put("knowledgePoint", item.knowledgePoint());
            action.put("reason", item.reason().name());
        });
        return result;
    }

    private JsonNode homeActionDismiss(JsonNode params, CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        String actionId = requiredText(params, "actionId", 128);
        context().getBean(LearningDiagnosisService.class).dismissAction(actionId);
        return mapper.createObjectNode().put("dismissed", true).put("actionId", actionId);
    }

    private JsonNode courseWorkspace(CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        var core = context();
        var result = mapper.createObjectNode();
        result.set("courses", mapper.valueToTree(core.getBean(CourseMapService.class).load().courses()));
        var knowledge = core.getBean(com.sqlteacher.application.knowledge.CourseKnowledgeService.class).listArticles();
        result.set("articles", mapper.valueToTree(knowledge));
        result.put("articleCount", knowledge.size());
        return result;
    }
}
