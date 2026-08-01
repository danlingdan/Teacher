package com.sqlteacher.application.planning;

import com.sqlteacher.application.ai.AiContextPreview;
import com.sqlteacher.application.knowledge.CourseKnowledgeSearchFilter;

public interface GroundedTutorService {
    AiContextPreview preview(String question, CourseKnowledgeSearchFilter filter);

    GroundedTutorResult ask(String courseScope, String objectiveId, String question,
                            CourseKnowledgeSearchFilter filter);

    void feedback(String sessionId, TutorFeedbackType type, String note);
}
