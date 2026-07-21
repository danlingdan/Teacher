package com.sqlteacher.application.knowledge;

import java.util.List;

public interface KnowledgeSearchService {
    List<KnowledgeSearchResult> search(String query, int limit);
}
