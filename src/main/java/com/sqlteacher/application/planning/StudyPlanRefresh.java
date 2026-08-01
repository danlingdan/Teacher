package com.sqlteacher.application.planning;

import java.util.List;

public record StudyPlanRefresh(StudyPlanSnapshot snapshot, List<StudyPlanChange> changes) {
    public StudyPlanRefresh {
        if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");
        changes = changes == null ? List.of() : List.copyOf(changes);
    }
}
