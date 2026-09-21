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
- Keep Java 25 compatibility (per `pom.xml`) and existing dependency versions unless the task requires a coordinated change.
- Keep database, file, network, Runner, and AI work asynchronous from the WebView. Represent loading, success, empty, and failure states honestly.

For SQLite or Cloud schema changes, update migration, persistence behavior, and tests together. Cover an empty database, the oldest supported schema, repeated startup, failed migration rollback, and rejection of a future schema. Keep derived learning state recomputable from authoritative events.

## Iterate UI changes at the narrowest scope

Do not run full packaging to preview UI effects. Pick the loop that matches the change:

1. Pure UI (React/TypeScript/CSS/layout): run `npm --prefix ui-web run tauri dev` once and iterate through Vite HMR. Debug builds use the repository dev sidecar `ui-web/src-tauri/sidecar/` (see `sidecar_root` in `ui-web/src-tauri/src/lib.rs`), so the window talks to the real Java core and local data. Browser access to `http://localhost:1420` is intentionally refused (`DESKTOP_HOST_REQUIRED`); always preview from the Tauri shell.
2. UI plus Java: `mvn -q -DskipTests package`, copy `target/Teacher-<pom.xml version>.jar` into `ui-web/src-tauri/sidecar/app/`, and restart `tauri dev`. Remove the previous version's jar first when the version changed; run `packaging/build-v3-sidecar.ps1` only when dependencies or the JDK change, and note it recreates the sidecar directory.
3. Cloud-facing changes: verify server logic locally against the loopbound cloud API (`127.0.0.1:18080`); never point UI previews at production `https://api.sqlteacher.tech` and never edit the production server to preview an effect.

`ui-web/src-tauri/sidecar/` is untracked; generate it once with `packaging/build-v3-sidecar.ps1` after a clean checkout. Full `packaging/package-v3.ps1` remains a release gate. Details: `docs/guide/24-tauri-desktop-architecture.md`.

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
