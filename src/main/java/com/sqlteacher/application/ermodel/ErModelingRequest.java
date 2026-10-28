package com.sqlteacher.application.ermodel;

/**
 * One E-R modeling request. The requirement is user input: it is bounded by the bridge
 * (trim non-empty, at most 2000 characters) and injected into the prompt as untrusted material.
 */
public record ErModelingRequest(String requirement) {
}
