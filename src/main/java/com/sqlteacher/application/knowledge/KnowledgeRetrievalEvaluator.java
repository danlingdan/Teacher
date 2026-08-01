package com.sqlteacher.application.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic offline metrics for comparing FTS5 and hybrid retrieval runs. */
public final class KnowledgeRetrievalEvaluator {
    public EvaluationReport evaluate(List<EvaluationCase> cases) {
        if (cases == null || cases.isEmpty()) throw new IllegalArgumentException("evaluation cases must not be empty");
        double recall = 0, reciprocalRank = 0, citationCoverage = 0;
        int noAnswerCases = 0, correctNoAnswers = 0;
        List<Long> latencies = new ArrayList<>();
        for (EvaluationCase item : cases) {
            Set<String> returned = new HashSet<>(item.returnedDocumentIds());
            if (item.expectedDocumentIds().isEmpty()) {
                noAnswerCases++;
                if (returned.isEmpty()) correctNoAnswers++;
            } else {
                long relevant = item.expectedDocumentIds().stream().filter(returned::contains).count();
                recall += relevant / (double) item.expectedDocumentIds().size();
                for (int index = 0; index < item.returnedDocumentIds().size(); index++) {
                    if (item.expectedDocumentIds().contains(item.returnedDocumentIds().get(index))) {
                        reciprocalRank += 1.0 / (index + 1); break;
                    }
                }
            }
            citationCoverage += item.answerClaimCount() == 0 ? 1.0
                : Math.min(1.0, item.citedClaimCount() / (double) item.answerClaimCount());
            latencies.add(item.latencyMillis());
        }
        latencies.sort(Comparator.naturalOrder());
        int p95Index = Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1);
        return new EvaluationReport(recall / cases.size(), reciprocalRank / cases.size(),
            noAnswerCases == 0 ? 1.0 : correctNoAnswers / (double) noAnswerCases,
            citationCoverage / cases.size(), latencies.get(p95Index), cases.size());
    }

    public record EvaluationCase(Set<String> expectedDocumentIds, List<String> returnedDocumentIds,
                                 int answerClaimCount, int citedClaimCount, long latencyMillis) {
        public EvaluationCase {
            expectedDocumentIds = expectedDocumentIds == null ? Set.of() : Set.copyOf(expectedDocumentIds);
            returnedDocumentIds = returnedDocumentIds == null ? List.of() : List.copyOf(returnedDocumentIds);
            if (answerClaimCount < 0 || citedClaimCount < 0 || citedClaimCount > answerClaimCount || latencyMillis < 0)
                throw new IllegalArgumentException("evaluation case values are invalid");
        }
    }
    public record EvaluationReport(double recallAtK, double meanReciprocalRank, double noAnswerPrecision,
                                   double citationCoverage, long latencyP95Millis, int caseCount) { }
}
