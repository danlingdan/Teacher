package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.List;

public interface CourseKnowledgeService {
    CourseKnowledgeArticle importArticle(
        Path path,
        String courseTitle,
        String sectionTitle,
        List<String> knowledgePoints
    );

    List<CourseKnowledgeArticle> listArticles();

    CourseKnowledgeDetail getArticle(String articleId);

    CourseKnowledgeArticle reviseArticle(String articleId, Path path, List<String> knowledgePoints);

    CourseKnowledgeArticle changeVisibility(String articleId, KnowledgeVisibility visibility);

    List<KnowledgeSearchResult> search(
        String query,
        CourseKnowledgeSearchFilter filter,
        int limit
    );
}
