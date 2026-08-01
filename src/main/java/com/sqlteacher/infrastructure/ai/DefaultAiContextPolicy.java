package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class DefaultAiContextPolicy implements AiContextPolicy {
    private static final int MAX_TOTAL = 20_000;
    private static final int MAX_ITEM = 5_000;
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b");
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_ -]?key|token|password|authorization)\\s*[:=]\\s*\\S+");

    @Override
    public AiPreparedContext prepare(AiTaskType taskType, List<AiContextItem> requested) {
        Set<AiContextCategory> allowed = allowed(taskType);
        List<AiContextItem> accepted = new ArrayList<>();
        Set<AiContextCategory> categories = EnumSet.noneOf(AiContextCategory.class);
        Set<String> sources = new LinkedHashSet<>();
        List<String> redactions = new ArrayList<>();
        int total = 0;
        for (AiContextItem item : requested == null ? List.<AiContextItem>of() : requested) {
            if (!allowed.contains(item.category())) {
                redactions.add("已排除不属于当前任务的数据类别：" + item.category().name());
                continue;
            }
            String content = SECRET.matcher(EMAIL.matcher(item.content()).replaceAll("[已隐藏身份]")).replaceAll("$1=[已隐藏凭据]");
            if (!content.equals(item.content())) redactions.add("已隐藏身份或凭据信息");
            int remaining = MAX_TOTAL - total;
            if (remaining <= 0) {
                redactions.add("已按总字符预算截断后续内容");
                break;
            }
            int limit = Math.min(MAX_ITEM, remaining);
            if (content.length() > limit) {
                content = content.substring(0, limit);
                redactions.add("已按字符预算截断：" + item.source());
            }
            accepted.add(new AiContextItem(item.category(), item.source(), content));
            categories.add(item.category());
            sources.add(item.source());
            total += content.length();
        }
        return new AiPreparedContext(accepted, new AiContextPreview(
            taskType, categories, List.copyOf(sources), total, List.copyOf(new LinkedHashSet<>(redactions))
        ));
    }

    private static Set<AiContextCategory> allowed(AiTaskType type) {
        return switch (type) {
            case NL2SQL -> EnumSet.of(AiContextCategory.USER_REQUEST, AiContextCategory.DATABASE_SCHEMA,
                AiContextCategory.EXERCISE_DEFINITION, AiContextCategory.KNOWLEDGE_EXCERPT, AiContextCategory.SQL_DRAFT);
            case SQL_ERROR_EXPLANATION -> EnumSet.of(AiContextCategory.DATABASE_SCHEMA,
                AiContextCategory.SQL_DRAFT, AiContextCategory.SQL_ERROR);
            case FEEDBACK_DRAFT -> EnumSet.of(AiContextCategory.DETERMINISTIC_RESULT,
                AiContextCategory.EXERCISE_DEFINITION, AiContextCategory.KNOWLEDGE_EXCERPT);
            case KNOWLEDGE_EXPLANATION -> EnumSet.of(AiContextCategory.USER_REQUEST,
                AiContextCategory.KNOWLEDGE_EXCERPT);
        };
    }
}
