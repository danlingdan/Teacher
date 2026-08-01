package com.sqlteacher.application.knowledge;

import com.sqlteacher.application.ai.AiContextPreview;

public interface GroundedKnowledgeExplanationService {
    AiContextPreview preview(String question, CourseKnowledgeSearchFilter filter);

    GroundedKnowledgeAnswer explain(String question, CourseKnowledgeSearchFilter filter);
}
