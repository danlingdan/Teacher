package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.List;

public interface CourseKnowledgeService {
    /**
     * v3.6.0 KBF-3: 导入单篇文章。`allowDuplicate` 为 false 时先做归一化内容查重，命中既有
     * 文章则返回 duplicateCandidate 结果（不导入）；为 true 时跳过查重直接导入。
     */
    CourseKnowledgeImportResult importArticle(
        Path path,
        String courseTitle,
        String sectionTitle,
        List<String> knowledgePoints,
        boolean allowDuplicate
    );

    List<CourseKnowledgeArticle> listArticles();

    CourseKnowledgeDetail getArticle(String articleId);

    CourseKnowledgeArticle reviseArticle(String articleId, Path path, List<String> knowledgePoints);

    CourseKnowledgeArticle changeVisibility(String articleId, KnowledgeVisibility visibility);

    /**
     * v3.6.0 KBF-2: 删除整篇文章聚合（修订、知识点链接、混合分块、索引作业，以及底层文档与
     * FTS 分块）。应用库连接外键是关闭的，实现必须显式按子表在先的次序删除，不得依赖级联。
     */
    void deleteArticle(String articleId);

    /** v3.6.0 KBX-3: 分页检索——offset 为结果集游标，配合 limit 使用。 */
    List<KnowledgeSearchResult> search(
        String query,
        CourseKnowledgeSearchFilter filter,
        int limit,
        int offset
    );
}
