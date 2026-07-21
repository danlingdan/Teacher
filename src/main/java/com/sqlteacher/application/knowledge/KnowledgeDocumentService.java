package com.sqlteacher.application.knowledge;

import java.nio.file.Path;
import java.util.List;

public interface KnowledgeDocumentService {
    KnowledgeDocument importDocument(Path path);

    List<KnowledgeDocument> listDocuments();

    void deleteDocument(String documentId);
}
