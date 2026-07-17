package com.sqlteacher.application.nl2sql;

import com.sqlteacher.application.event.LearningEventService;
import com.sqlteacher.application.risk.SqlRiskAnalysis;
import com.sqlteacher.application.risk.SqlRiskAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Coordinates AI draft generation, Java-side risk analysis, and risk event recording.
 */
public final class DefaultNl2SqlSafetyService implements Nl2SqlSafetyService {
    private static final Logger log = LoggerFactory.getLogger(DefaultNl2SqlSafetyService.class);

    private final Nl2SqlService nl2SqlService;
    private final SqlRiskAnalysisService riskAnalysisService;
    private final LearningEventService learningEventService;

    public DefaultNl2SqlSafetyService(
        Nl2SqlService nl2SqlService,
        SqlRiskAnalysisService riskAnalysisService,
        LearningEventService learningEventService
    ) {
        this.nl2SqlService = Objects.requireNonNull(nl2SqlService, "nl2SqlService must not be null");
        this.riskAnalysisService = Objects.requireNonNull(
            riskAnalysisService,
            "riskAnalysisService must not be null"
        );
        this.learningEventService = Objects.requireNonNull(
            learningEventService,
            "learningEventService must not be null"
        );
    }

    @Override
    public Nl2SqlSafetyResult generateAndAssess(Nl2SqlRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        Nl2SqlPlan plan = Objects.requireNonNull(
            nl2SqlService.generate(request),
            "nl2SqlService result must not be null"
        );
        SqlRiskAnalysis riskAnalysis = riskAnalysisService.analyze(plan.sqlDraft());
        Nl2SqlSafetyResult result = new Nl2SqlSafetyResult(plan, riskAnalysis);

        if (result.draftAvailable() && !result.accepted()) {
            recordBlockedDraft(request, riskAnalysis);
        }

        return result;
    }

    private void recordBlockedDraft(Nl2SqlRequest request, SqlRiskAnalysis riskAnalysis) {
        try {
            learningEventService.recordSqlRiskBlocked(
                request.connectionId(),
                riskAnalysis.statementType(),
                riskAnalysis.level(),
                riskAnalysis.multiStatement()
            );
        } catch (RuntimeException error) {
            log.warn("Failed to record blocked AI SQL draft", error);
        }
    }
}
