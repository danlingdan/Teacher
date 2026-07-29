package com.sqlteacher.infrastructure.ai;

import com.sqlteacher.application.ai.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultAiContextPolicyTest {
    @Test void shouldRemoveIdentityAndCredentialAndExposeMetadataOnlyPreview() {
        AiPreparedContext prepared = new DefaultAiContextPolicy().prepare(AiTaskType.NL2SQL, List.of(
            new AiContextItem(AiContextCategory.USER_REQUEST, "request",
                "联系 student@example.test，api_key=secret-value 后查询学生"),
            new AiContextItem(AiContextCategory.DETERMINISTIC_RESULT, "forbidden", "must not leave")
        ));

        assertEquals(1, prepared.items().size());
        assertFalse(prepared.items().get(0).content().contains("student@example.test"));
        assertFalse(prepared.items().get(0).content().contains("secret-value"));
        assertTrue(prepared.preview().redactions().stream().anyMatch(text -> text.contains("隐藏")));
        assertFalse(prepared.preview().sources().contains("forbidden"));
    }

    @Test void shouldBoundEveryItemAndTotalContext() {
        String oversized = "x".repeat(9_000);
        AiPreparedContext prepared = new DefaultAiContextPolicy().prepare(AiTaskType.NL2SQL, List.of(
            new AiContextItem(AiContextCategory.USER_REQUEST, "one", oversized),
            new AiContextItem(AiContextCategory.DATABASE_SCHEMA, "two", oversized)
        ));
        assertTrue(prepared.preview().characterCount() <= 20_000);
        assertTrue(prepared.items().stream().allMatch(item -> item.content().length() <= 5_000));
    }
}
