package com.sqlteacher.application.ermodel;

/**
 * AI-assisted Chen-notation E-R modeling (v3.11.0). The implementation drafts with the local AI
 * provider, then everything crosses deterministic Java validation before leaving the service;
 * AI and provider failures degrade to a fail-safe status and never throw across this boundary.
 */
public interface ErModelingService {

    ErModelingResult generate(ErModelingRequest request);
}
