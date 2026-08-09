package com.sqlteacher.application.activity;

import java.util.List;

public interface ProjectPortfolioService {
    /** Returns only entries owned by the current local/cloud identity. */
    List<ProjectPortfolioEntry> listOwnEntries();

    /** Export is deliberately gated by a fresh user choice; it never makes an entry public. */
    String exportOwnPortfolio(boolean userConfirmed);
}
