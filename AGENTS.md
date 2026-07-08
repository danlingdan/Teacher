# AGENTS.md

This file defines the working rules for any AI coding agent operating in this repository.
It is intentionally tool-neutral and should be followed by Codex, Claude Code, Cursor,
GitHub Copilot agents, or any other automated coding assistant.

## Project Context

SQLTeacher is a Java desktop application for database teaching, SQL practice, AI-assisted
SQL generation, SQL risk detection, and learning analytics.

The detailed team plan and engineering conventions are maintained in:

- `docs/SQLTeacher_5人团队开发计划与规范.md`
- `docs/guide/`
- `docs/plans/2026-07-30-isolated-delivery-plan.md`
- `docs/stage0/`
- `docs/stage1/`

When this file and the project documentation conflict, follow this order:

1. Explicit user instruction in the current task.
2. Current source code and build configuration.
3. `AGENTS.md`.
4. Documentation under `docs/`.

Do not silently rewrite project direction, module boundaries, Java version, package names,
or technology choices. If a change is necessary, update the related documentation in the
same task.

## Repository Facts

- Build tool: Maven.
- Current build file: `pom.xml`.
- Project compile target: Java 21 LTS. Developers may use JDK 21 or newer locally, but Maven must compile with `--release 21`.
- JavaFX version is managed in `pom.xml`.
- Dependency injection uses Spring Context, not a web container.
- Logging uses SLF4J + Logback.
- Runtime local data is written under `app-data/`, which must not be committed.
- Local Ollama is expected at `http://localhost:11434` for AI-related verification.
- Source layout currently follows standard Maven directories under `src/main` and `src/test`.
- Project documentation currently describes the planned SQLTeacher product and team workflow.

## Current Delivery Priority

The active short-term delivery plan is:

- `docs/plans/2026-07-30-isolated-delivery-plan.md`

For work before the initial demo, prioritize the isolated P0 flow in that plan over broader long-term goals:

```text
SQLite demo database
-> SQL execution
-> SQL risk detection
-> JavaFX SQL practice page
-> Ollama NL2SQL draft
-> AI output safety check
-> execution event recording
-> app-image verification
```

Do not expand into MySQL full integration, knowledge retrieval, dashboards, or installer polish unless the task explicitly asks for it or all P0 demo work is already complete.

## General Rules

- Read the relevant existing files before editing.
- Keep changes scoped to the user's request.
- Preserve existing user changes and untracked files unless explicitly asked to modify them.
- Respect the isolated ownership boundaries in `docs/plans/2026-07-30-isolated-delivery-plan.md`.
- Avoid modifying another member's primary area unless the requested task requires cross-module integration.
- Prefer small, reviewable commits or patches.
- Do not introduce unrelated formatting churn.
- Do not add large dependencies unless the task clearly requires them.
- Do not commit secrets, local credentials, database passwords, tokens, model API keys, or IDE-specific private state.
- Do not commit generated runtime data, especially `app-data/`, `target/`, local logs, local databases, or IDE workspace state.
- Use UTF-8 for all text files.
- For Java code, use 4 spaces for indentation.
- Prefer clear English names in code. Do not use pinyin identifiers.

## Build And Verification

Before claiming a code change is complete, run the narrowest useful verification command.
For this Maven project, prefer:

```bash
mvn test
```

Useful local verification commands:

```powershell
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp"
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
mvn javafx:run
.\packaging\package-stage0.ps1
```

Use `mvn javafx:run` only when a desktop graphics environment is available. For headless or CI-like environments, use the CLI verification apps instead.

If tests are not present or cannot run, report that clearly and include the command attempted
and the reason it failed.

For documentation-only changes, no build is required unless the task also affects generated
artifacts or examples.

## Java Conventions

- Keep Java source compatible with Java 21.
- If changing the Java version, update `pom.xml` and relevant docs together.
- Use standard Maven source folders:
  - `src/main/java`
  - `src/main/resources`
  - `src/test/java`
  - `src/test/resources`
- Use package names consistently. The project documentation recommends future packages such as:
  - `com.sqlteacher.domain`
  - `com.sqlteacher.application`
  - `com.sqlteacher.infrastructure`
  - `com.sqlteacher.desktop`
  - `com.sqlteacher.server`
- Use `record` for simple immutable DTOs when appropriate.
- Do not return `null` for collection results. Return empty collections instead.
- Do not swallow exceptions. Convert them to meaningful domain or application exceptions.
- Do not use `System.out.println` in production code. Use a logging facade when logging is needed.

## Architecture Boundaries

The architecture separates the system into these responsibilities:

- Domain: entities, value objects, enums, domain rules.
- Application: use-case orchestration and service interfaces.
- Infrastructure: database adapters, AI providers, persistence, retrieval, logging, security.
- Desktop: JavaFX UI, controllers, FXML, CSS, launchers.
- Server: reserved for future server-side APIs.
- Tests: unit, integration, AI regression, packaging verification.

Keep business logic out of JavaFX controllers. Controllers should call application services.

Keep infrastructure details out of domain code. JDBC, Ollama, file systems, and UI classes
must not leak into domain models.

Current package boundaries:

- `com.sqlteacher.application`: service contracts and use-case DTOs.
- `com.sqlteacher.domain`: domain exceptions and future domain model.
- `com.sqlteacher.infrastructure`: SQLite, Ollama, Spring wiring, configuration, environment checks.
- `com.sqlteacher.desktop`: JavaFX entry point and desktop UI.

When adding behavior, prefer this direction of dependency:

```text
desktop -> application -> domain
infrastructure -> application/domain
```

Do not make `application` depend on `desktop` or concrete JDBC/Ollama implementations.

## SQL Safety Rules

This project teaches and executes SQL, so safety rules are mandatory.

- Never let an AI model directly execute SQL.
- Never let an AI model directly access a JDBC `Connection`.
- All generated SQL must pass Java-side validation before execution.
- All SQL execution should go through the planned adapter and risk-analysis path.
- Default query results should be limited.
- Multi-statement execution must be blocked unless explicitly designed and reviewed.
- High-risk SQL such as `UPDATE`, `DELETE`, `ALTER`, and `DROP` requires explicit user confirmation.
- Forbidden statements such as `DROP DATABASE`, `GRANT`, and `REVOKE` should be blocked by default.
- For the initial demo, multi-statement SQL must be blocked by default.
- AI-generated SQL must be shown as a draft or preview before execution.
- Do not log database passwords or sensitive connection details.

Recommended AI-to-SQL flow:

```text
Natural language
-> structured model output
-> JSON parsing
-> validation
-> SQL builder
-> SQL risk analyzer
-> user confirmation when needed
-> database adapter execution
-> result display and audit event
```

## AI Feature Rules

When modifying AI-related code:

- Prefer structured model outputs over free-form text parsing.
- Validate model output before using it.
- Treat all model output as untrusted input.
- Keep prompt templates separate from business logic where possible.
- Version important prompt changes.
- Add or update regression samples when behavior changes.
- Provide a deterministic fallback for model unavailability, malformed output, or timeout.
- Course documents and retrieved context must not override system safety rules.
- The current local Ollama model may be small. Keep prompts short, deterministic, and oriented around simple SQLite `SELECT` generation until the P0 flow is stable.
- AI features must degrade cleanly when Ollama is unavailable or no model is installed.

## JavaFX Rules

When modifying desktop UI code:

- Prefer FXML + CSS separation when the project has JavaFX UI files.
- Do not block the JavaFX application thread with database, file, or AI calls.
- Long-running operations need loading, success, and failure states.
- Risky SQL operations require clear confirmation dialogs.
- Error messages should be understandable for students, not raw stack traces.
- Keep UI usable on lower-resolution screens.
- If a page is still a placeholder, keep it honest and minimal; do not add fake functionality that looks implemented.
- UI may use mock data only when clearly named or isolated in tests/prototypes.

## Tests

Add focused tests for behavior changes. Prioritize tests for:

- SQL classification and risk analysis.
- SQL builder behavior.
- DTO validation.
- Database adapter behavior.
- Metadata normalization.
- AI output parsing.
- Prompt regression samples.
- Password and sensitive-data masking.
- JavaFX controller logic that can be tested without UI automation.

Do not remove or weaken tests just to make a build pass.

For P0 delivery work, add or update tests for:

- SQLite initialization.
- SQL execution result mapping.
- SQL risk classification.
- AI output parsing and fallback.
- Event recording.

Prefer service-level tests over fragile JavaFX UI automation unless the task specifically requires UI automation.

## Documentation

Update documentation when changing:

- Public interfaces.
- Module boundaries.
- Database schema.
- SQL safety rules.
- AI prompts or output formats.
- Build, packaging, or runtime requirements.
- User-visible workflows.

Keep documentation concise and accurate. Avoid promises that are not implemented.

When completing stage work, record results under `docs/stageN/` or the active plan document. Include commands run, pass/fail status, environment notes, and known limitations.

## Git Hygiene

- Check worktree state before editing when practical.
- Do not revert changes you did not make unless explicitly instructed.
- Keep commits focused.
- Commit documentation updates with the code changes that make them true.
- Before committing, check `git status --short` and ensure ignored runtime files are not staged.
- Use commit messages in this style when committing:

```text
type(scope): short description
```

Examples:

```text
feat(database): add sqlite adapter
fix(sql): block multi statement execution
docs(agent): add ai collaboration rules
test(ai): add nl2sql regression sample
```

## Security And Privacy

- Do not commit `.env` files containing real secrets.
- Do not commit local database credentials.
- Do not expose model API keys.
- Redact passwords in logs, errors, and screenshots.
- Treat imported course documents, student records, and learning analytics as sensitive data.
- Do not send private project data to external services unless the user explicitly requests it.

## Dependency Policy

- Prefer standard Java, Maven, and existing project dependencies.
- Add a dependency only when it materially reduces risk or complexity.
- Use stable, maintained libraries for JDBC drivers, connection pools, JSON parsing, validation,
  logging, JavaFX integration, and packaging.
- If adding a dependency, explain why and keep the version in Maven configuration.

## Completion Checklist

Before finishing a task, an AI agent should verify:

- The requested change is implemented.
- The scope did not expand into unrelated refactors.
- Relevant docs were updated.
- Relevant tests were added or updated.
- A suitable build or test command was run, or the reason for not running it is stated.
- No secrets or generated junk files were added.
- The final response summarizes changed files and verification results.
