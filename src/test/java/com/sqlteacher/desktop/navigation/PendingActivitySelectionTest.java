package com.sqlteacher.desktop.navigation;

import com.sqlteacher.application.course.CourseMapActivity;
import com.sqlteacher.domain.activity.ActivityDifficulty;
import com.sqlteacher.domain.activity.ActivityType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PendingActivitySelectionTest {
    @Test
    void shouldRetainADeepLinkUntilTheCatalogArrives() {
        PendingActivitySelection selection = new PendingActivitySelection();
        selection.request("trace");

        assertEquals(-1, selection.resolve(List.of(activity("sql", ActivityType.SQL))));
        assertEquals(1, selection.resolve(List.of(
            activity("sql", ActivityType.SQL), activity("trace", ActivityType.TRACE)
        )));
        assertEquals(-1, selection.resolve(List.of(activity("trace", ActivityType.TRACE))));
    }

    private static CourseMapActivity activity(String id, ActivityType type) {
        return new CourseMapActivity(id, id, type, ActivityDifficulty.BEGINNER, 5, true, List.of("point"));
    }
}
