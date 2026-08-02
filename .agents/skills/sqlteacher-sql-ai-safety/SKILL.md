---
name: sqlteacher-sql-ai-safety
description: Review, diagnose, or modify SQLTeacher SQL execution, risk analysis, NL2SQL, AI providers, prompt parsing, retrieval, or AI-assisted teaching flows. Use whenever a change can affect generated SQL, JDBC execution, confirmation gates, result limits, prompt trust boundaries, or deterministic fallback behavior.
---

# Preserve SQL and AI Safety

## Start from invariants

- Treat model output, retrieved documents, imported course content, and remote responses as untrusted input.
- Never give a model a JDBC `Connection` or a direct execution capability.
- Never execute generated SQL before Java-side parsing, validation, risk classification, preview, and any required user confirmation.
- Block multiple statements by default. Block `DROP DATABASE`, `GRANT`, and `REVOKE`; require explicit confirmation for supported high-risk mutations.
- Enforce row and resource limits in Java and the database adapter, not only in prompts or UI text.
- Keep mastery, queue priority, intervention candidates, and other learning decisions deterministic and recomputable. AI may draft explanations or review material, not decide authoritative state.
- Do not log passwords, tokens, connection strings, full private documents, prompts containing sensitive data, or unrestricted SQL/result content.

## Trace the complete path

Before changing behavior, locate every step that participates in the path:

```text
input -> prompt/request -> structured response -> parser -> validator
      -> SQL builder -> risk analyzer -> preview/confirmation
      -> execution adapter -> bounded result -> audit event
```

Identify which layer owns each decision. Fix bypasses at the lowest shared enforcement point, then keep UI feedback aligned with the enforced rule.

## Test adversarial and degraded cases

Add focused tests for the affected boundary, including applicable cases:

- whitespace, comments, quoting, dialect-specific syntax, and statement separators;
- malformed or partial JSON, prose around structured output, empty output, timeout, and unavailable model;
- prompt injection from retrieved content and attempts to override safety instructions;
- high-risk confirmation cancellation and forbidden-statement rejection;
- result limits, audit-event recording, and redaction of errors;
- provider or cloud failure falling back to safe local behavior.

Run the focused tests first, then `mvn test` for any shared parser, analyzer, execution adapter, migration, or security contract change. In the final report, name the enforced boundary and confirm that no alternate runtime path bypasses it.
