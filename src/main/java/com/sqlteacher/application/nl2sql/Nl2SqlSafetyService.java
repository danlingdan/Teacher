package com.sqlteacher.application.nl2sql;

/**
 * Application-level use case that generates an AI SQL draft and evaluates it with the
 * same Java-side safety policy used by manual SQL execution.
 *
 * <p>This service only returns a draft and its assessment. It deliberately exposes no
 * SQL execution operation.</p>
 */
public interface Nl2SqlSafetyService {
    Nl2SqlSafetyResult generateAndAssess(Nl2SqlRequest request);
}
