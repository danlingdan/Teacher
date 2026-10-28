package com.sqlteacher.application.ermodel;

/** Fail-safe outcome of one E-R modeling generation (v3.11.0 plan §4; semantics mirror NL2SQL). */
public enum ErGenerationStatus {
    /** The model was generated, validated, and is carried by the result. */
    OK,
    /** No AI model is selected; the provider was never called. */
    MODEL_UNAVAILABLE,
    /** The provider threw or returned a failure (timeout, unreachable, HTTP error). */
    PROVIDER_ERROR,
    /** The prompt template could not be prepared. */
    PROMPT_ERROR,
    /** The response could not be parsed into a JSON object, even after the one repair retry. */
    PARSE_ERROR,
    /** Deterministic validation still found errors after the one repair retry. */
    VALIDATION_FAILED
}
