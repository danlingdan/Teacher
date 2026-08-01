package com.sqlteacher.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlteacher.application.ai.AiContextCategory;
import com.sqlteacher.application.ai.AiContextItem;
import com.sqlteacher.application.ai.AiContextPolicy;
import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.ai.AiPreparedContext;
import com.sqlteacher.application.ai.AiTaskRequest;
import com.sqlteacher.application.ai.AiTaskResult;
import com.sqlteacher.application.ai.AiTaskService;
import com.sqlteacher.application.ai.AiTaskType;
import com.sqlteacher.application.knowledge.CourseKnowledgeArticle;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;
import com.sqlteacher.application.knowledge.CourseKnowledgeService;
import com.sqlteacher.application.knowledge.GroundedKnowledgeAnswer;
import com.sqlteacher.application.knowledge.GroundedKnowledgeExplanationService;
import com.sqlteacher.application.knowledge.HybridKnowledgeRetrievalService;
import com.sqlteacher.application.knowledge.KnowledgeSearchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DefaultGroundedKnowledgeExplanationService implements GroundedKnowledgeExplanationService {
    static final String PROMPT_VERSION = "knowledge-explanation-v1";
    private static final int MAX_CITATIONS = 5;

    private final CourseKnowledgeService knowledgeService;
    private final HybridKnowledgeRetrievalService retrievalService;
    private final AiTaskService aiTaskService;
    private final AiContextPolicy contextPolicy;
    private final ObjectMapper mapper;

    public DefaultGroundedKnowledgeExplanationService(
        CourseKnowledgeService knowledgeService,
        AiTaskService aiTaskService,
        AiContextPolicy contextPolicy
    ) {
        this(knowledgeService, (query, filter, limit) -> new HybridKnowledgeRetrievalService.RetrievalResponse(
            knowledgeService.search(query, filter, limit), "FTS5", false, ""), aiTaskService, contextPolicy);
    }

    public DefaultGroundedKnowledgeExplanationService(
        CourseKnowledgeService knowledgeService,
        HybridKnowledgeRetrievalService retrievalService,
        AiTaskService aiTaskService,
        AiContextPolicy contextPolicy
    ) {
        this.knowledgeService = Objects.requireNonNull(knowledgeService);
        this.retrievalService = Objects.requireNonNull(retrievalService);
        this.aiTaskService = Objects.requireNonNull(aiTaskService);
        this.contextPolicy = Objects.requireNonNull(contextPolicy);
        this.mapper = new ObjectMapper();
    }

    @Override
    public AiContextPreview preview(String question, CourseKnowledgeSearchFilter filter) {
        PreparedKnowledge prepared = prepare(question, filter);
        return prepared.context().preview();
    }

    @Override
    public GroundedKnowledgeAnswer explain(String question, CourseKnowledgeSearchFilter filter) {
        PreparedKnowledge prepared = prepare(question, filter);
        if (prepared.citations().isEmpty()) {
            return new GroundedKnowledgeAnswer(false, "未检索到可以支撑回答的课程知识。", "", List.of(),
                "请调整问题或课程、章节、知识点筛选条件。");
        }
        String prompt = buildPrompt(requireQuestion(question), prepared.context());
        AiTaskResult result = aiTaskService.execute(new AiTaskRequest(
            AiTaskType.KNOWLEDGE_EXPLANATION,
            aiTaskService.preferredModel(),
            prompt,
            PROMPT_VERSION,
            prepared.context().preview()
        ));
        if (!result.success()) {
            return deterministicFallback(prepared.citations(), result.message(), result.model());
        }
        try {
            JsonNode root = mapper.readTree(result.content());
            String answer = root.path("answer").asText("").trim();
            if (answer.isBlank()) {
                return deterministicFallback(prepared.citations(), "AI 未返回有效答案。", result.model());
            }
            List<GroundedKnowledgeAnswer.Citation> used = new ArrayList<>();
            for (JsonNode number : root.path("citations")) {
                int index = number.asInt(-1);
                if (index >= 1 && index <= prepared.citations().size()) {
                    GroundedKnowledgeAnswer.Citation citation = prepared.citations().get(index - 1);
                    if (!used.contains(citation)) {
                        used.add(citation);
                    }
                }
            }
            if (used.isEmpty()) {
                return deterministicFallback(prepared.citations(), "AI 答案缺少有效引用，已拒绝直接展示。", result.model());
            }
            return new GroundedKnowledgeAnswer(true, answer, result.model(), used, "回答仅基于本次确认发送的课程知识片段。");
        } catch (Exception error) {
            return deterministicFallback(prepared.citations(), "AI 返回格式无效，已改用检索摘要。", result.model());
        }
    }

    private PreparedKnowledge prepare(String requestedQuestion, CourseKnowledgeSearchFilter requestedFilter) {
        String question = requireQuestion(requestedQuestion);
        CourseKnowledgeSearchFilter filter = requestedFilter == null
            ? CourseKnowledgeSearchFilter.published() : requestedFilter;
        List<KnowledgeSearchResult> results = retrievalService.retrieve(question, filter, MAX_CITATIONS).results();
        Map<String, CourseKnowledgeArticle> articles = knowledgeService.listArticles().stream()
            .collect(Collectors.toMap(CourseKnowledgeArticle::documentId, Function.identity()));
        List<GroundedKnowledgeAnswer.Citation> citations = new ArrayList<>();
        List<AiContextItem> items = new ArrayList<>();
        items.add(new AiContextItem(AiContextCategory.USER_REQUEST, "学生问题", question));
        for (int index = 0; index < results.size(); index++) {
            KnowledgeSearchResult result = results.get(index);
            CourseKnowledgeArticle article = articles.get(result.documentId());
            if (article == null) {
                continue;
            }
            int number = citations.size() + 1;
            GroundedKnowledgeAnswer.Citation citation = new GroundedKnowledgeAnswer.Citation(
                number, result.documentId(), article.title(), article.currentRevision(), result.chunkIndex(), result.snippet()
            );
            citations.add(citation);
            items.add(new AiContextItem(AiContextCategory.KNOWLEDGE_EXCERPT,
                "[" + number + "] " + article.title() + " v" + article.currentRevision(), result.snippet()));
        }
        return new PreparedKnowledge(contextPolicy.prepare(AiTaskType.KNOWLEDGE_EXPLANATION, items), List.copyOf(citations));
    }

    private static String buildPrompt(String question, AiPreparedContext context) {
        String sources = context.items().stream()
            .filter(item -> item.category() == AiContextCategory.KNOWLEDGE_EXCERPT)
            .map(item -> item.source() + "\n" + item.content())
            .collect(Collectors.joining("\n\n"));
        return """
            你是 SQLTeacher 的课程知识解释助手。只能根据给定资料回答，不得补充资料外事实。
            若资料不足，请在 answer 中明确说明。引用只能填写给定资料的编号。
            只返回 JSON 对象：{"answer":"简洁中文答案","citations":[1,2]}

            学生问题：
            %s

            课程资料：
            %s
            """.formatted(question, sources);
    }

    private static GroundedKnowledgeAnswer deterministicFallback(
        List<GroundedKnowledgeAnswer.Citation> citations,
        String message,
        String model
    ) {
        String answer = citations.stream()
            .map(citation -> "[" + citation.number() + "] " + citation.snippet())
            .collect(Collectors.joining("\n\n"));
        return new GroundedKnowledgeAnswer(false, answer, model, citations,
            message == null || message.isBlank() ? "AI 不可用，已展示可核验的检索摘要。" : message);
    }

    private static String requireQuestion(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        String question = value.trim();
        if (question.length() > 500) {
            throw new IllegalArgumentException("question must not exceed 500 characters");
        }
        return question;
    }

    private record PreparedKnowledge(
        AiPreparedContext context,
        List<GroundedKnowledgeAnswer.Citation> citations
    ) {
    }
}
