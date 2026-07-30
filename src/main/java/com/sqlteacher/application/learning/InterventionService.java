package com.sqlteacher.application.learning;

import java.util.List;

public interface InterventionService {
    List<InterventionCandidate> refreshAuthorized();

    void updateStatus(String candidateId, InterventionStatus status);

    String exportCsv(List<InterventionCandidate> candidates);
}
