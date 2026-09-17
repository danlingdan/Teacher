package com.sqlteacher.application.knowledge;

import java.util.List;

/**
 * v3.6.0 KBF-3: 单篇导入结果——要么是新导入的文章，要么（未经用户同意导入时）是检测到的
 * 同内容既有文章清单。是否强制导入由用户决定，本契约不做自动合并或拒绝。
 */
public record CourseKnowledgeImportResult(
    CourseKnowledgeArticle article,
    List<ContentDuplicate> duplicates
) {
    public CourseKnowledgeImportResult {
        duplicates = duplicates == null ? List.of() : List.copyOf(duplicates);
    }

    public static CourseKnowledgeImportResult imported(CourseKnowledgeArticle article) {
        return new CourseKnowledgeImportResult(article, List.of());
    }

    /** 未导入且检出同内容既有文章：等待用户显式选择是否仍要导入。 */
    public boolean duplicateCandidate() {
        return article == null && !duplicates.isEmpty();
    }

    public record ContentDuplicate(
        String articleId,
        String title,
        String courseTitle,
        String sectionTitle,
        int revision
    ) {
    }
}
