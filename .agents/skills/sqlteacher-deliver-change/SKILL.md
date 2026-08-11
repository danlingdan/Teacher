---
name: sqlteacher-deliver-change
description: Implement or fix SQLTeacher Java, Tauri, React, Maven, persistence, or application-service behavior with scoped architecture changes and proportional verification. Use for feature work, bug fixes, refactors, schema migrations, UI integration, or test changes in this repository; do not use for release publication, production operations, or documentation-only cleanup.
---

# Deliver a SQLTeacher Change

## Establish the live baseline

1. Read the closest `AGENTS.md`, `git status --short`, `pom.xml`, and the affected source and tests.
2. Treat `docs/README.md` as the documentation map. Read only the current guide or plan needed for the change.
3. Preserve unrelated modified and untracked files. Do not revive historical staffing or pre-demo scope from `docs/history/`.
4. State any assumption that changes public behavior, schema, security, or module ownership before relying on it.

## Design the smallest complete slice

Trace the runtime path before editing:

```text
desktop -> application -> domain
infrastructure -> application/domain
```

- Keep business rules and orchestration outside React components and Rust commands.
- Keep JDBC, HTTP, Ollama, files, WebView, and Tauri types out of domain and application contracts.
- Extend existing contracts and adapters before adding parallel abstractions.
- Keep Java 21 compatibility and existing dependency versions unless the task requires a coordinated change.
- Keep database, file, network, Runner, and AI work asynchronous from the WebView. Represent loading, success, empty, and failure states honestly.

For SQLite or Cloud schema changes, update migration, persistence behavior, and tests together. Cover an empty database, the oldest supported schema, repeated startup, failed migration rollback, and rejection of a future schema. Keep derived learning state recomputable from authoritative events.

## Implement and verify

1. Add or update focused tests with the behavior change.
2. Run the narrowest relevant test first. In PowerShell, quote comma-separated selectors:

   ```powershell
   mvn -q test "-Dtest=FirstTest,SecondTest"
   ```

3. Run `mvn test` for cross-module, schema, security, release-bound, or accumulated changes. Use CLI verification apps when UI startup is unavailable and packaged Tauri E2E when graphics are available.
4. Update current documentation when changing a public contract, schema, safety rule, runtime requirement, or user-visible workflow.
5. Inspect `git diff --check` and `git status --short`. Confirm no `app-data/`, `target/`, logs, databases, `.env`, `.secrets`, or credentials entered the change.

Report changed behavior, important files, commands run, results, and any unverified runtime path. Do not call work complete after compilation alone when executable behavior changed.
