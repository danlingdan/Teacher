package com.sqlteacher.application.databaselearning;

import com.sqlteacher.application.course.CourseMapActivity;

import java.util.List;

public interface LearningGoalCatalogService {
    List<LearningGoal> load();

    record LearningGoal(String id, String title, String outcome, List<LearningStage> stages) {
        public LearningGoal {
            stages = List.copyOf(stages);
        }
    }

    record LearningStage(String title, String guidance, List<CourseMapActivity> activities) {
        public LearningStage {
            activities = List.copyOf(activities);
        }
    }
}
