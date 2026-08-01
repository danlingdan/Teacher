package com.sqlteacher.server;

import java.util.List;

interface CloudKnowledgeVectorClient {
    void validateCollection(int expectedDimension);

    void upsert(List<QdrantVectorClient.Point> points);

    List<QdrantVectorClient.SearchHit> search(float[] vector, String courseId, List<String> visibility, int limit);

    boolean ready();
}
