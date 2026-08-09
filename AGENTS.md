# AGENTS.md

These are the durable working rules for AI coding agents in SQLTeacher. Keep task-specific
procedures in `.agents/skills/`; keep this file concise enough to load on every task.

## Authority And Live Context

Resolve conflicts in this order:

1. The user's explicit instruction in the current task.
2. Current source code, tests, `pom.xml`, packaging, and deployment configuration.
3. This `AGENTS.md`.
4. Current documentation linked from `docs/README.md`.
5. Historical material under `docs/history/`.

Do not infer the current version, roadmap, schema, or release state from an old plan. Verify the
live branch, `pom.xml`, Git status/tags, and relevant implementation before changing them.

The early five-person plan and isolated demo plan are historical. SQLTeacher is maintained as a
single-developer project on `main`; do not introduce member ownership boundaries or a PR workflow
unless the user requests them.

## Project Invariants

- Build with Maven and compile for Java 21 (`--release 21`).
- Use Spring Context for dependency injection, SLF4J + Logback for logging, and standard Maven
  source directories.
- Keep runtime and generated data out of Git: `app-data/`, `target/`, logs, local databases,
  `.env`, `.secrets`, credentials, IDE private state, and imported private content.
- Use UTF-8, 4-space Java indentation, clear English identifiers, and no pinyin identifiers.
- Prefer existing dependencies and abstractions. Add a dependency only when it materially reduces
  risk or complexity, and explain the choice.
- Do not silently change product direction, public contracts, module boundaries, Java version,
  package names, safety policy, or supported runtime requirements. Coordinate code, tests, and
  current documentation when such a change is required.

## Skill Routing

Repository skills live under `.agents/skills/`. When a task matches a description below, read that
skill's complete `SKILL.md` before acting. Use the smallest set that covers the task.

| Task | Skill |
| --- | --- |
| Java/JavaFX/Maven feature, fix, refactor, migration, or integration | `sqlteacher-deliver-change` |
| SQL execution, risk analysis, NL2SQL, AI provider, prompt, RAG, or safety review | `sqlteacher-sql-ai-safety` |
| Version bump, installer, ZIP, checksum, tag, or GitHub Release | `sqlteacher-windows-release` |
| Production deploy, incident, certificate, backup, restore, Qdrant, or systemd | `sqlteacher-cloud-operations` |
| Documentation creation, reorganization, audit, indexes, plans, gates, or release notes | `sqlteacher-documentation` |

Agents that do not support automatic skill discovery must treat the matching `SKILL.md` as
repository instructions and follow it manually.

## Working Method

1. Read relevant files before editing and check `git status --short`.
2. Separate user-owned changes from the requested scope. Never revert, overwrite, stage, or commit
   unrelated work.
3. Trace the real runtime path and identify the owning layer before implementing.
4. Make the smallest complete change; avoid unrelated formatting, renaming, or speculative
   refactors.
5. Add or update focused tests for changed behavior.
6. Run verification proportional to risk, then inspect `git diff --check` and `git status --short`.
7. Update current documentation when public behavior, schema, safety, packaging, deployment, or
   user workflow changes.
8. Report behavior changed, files affected, commands and results, and any unverified limitation.

If a task is diagnosis or review only, do not implement or publish a fix unless requested. If a
task authorizes a change, complete and verify it without stopping at a plan.

## Architecture

Keep responsibilities explicit:

- `com.sqlteacher.domain`: domain models, value objects, exceptions, and deterministic rules.
- `com.sqlteacher.application`: use-case contracts, orchestration, and DTOs.
- `com.sqlteacher.infrastructure`: JDBC, SQLite, Ollama, HTTP, persistence, retrieval, files,
  configuration, and Spring wiring.
- `com.sqlteacher.desktop`: JavaFX launchers, FXML, CSS, controllers, and view state.
- `com.sqlteacher.server`: Cloud API, server persistence, authentication, and operational entry
  points.

Preferred dependency direction:

```text
desktop -> application -> domain
infrastructure -> application/domain
server -> application/domain/infrastructure as currently wired
```

- Keep business rules out of JavaFX controllers.
- Do not expose JavaFX, JDBC, Ollama, HTTP, or filesystem types through domain contracts.
- Do not make `application` depend on `desktop` or concrete infrastructure adapters.
- Use records for simple immutable DTOs when appropriate.
- Return empty collections instead of `null`.
- Do not swallow exceptions; convert them into meaningful domain or application failures.
- Do not use `System.out.println` in production code.

## Non-Negotiable Safety

### SQL and AI

- A model must never execute SQL or receive a JDBC `Connection`.
- Treat model output and retrieved/imported content as untrusted.
- Generated SQL must follow the Java-enforced path: structured parsing, validation, SQL building,
  risk analysis, preview, required confirmation, bounded execution, and audit recording.
- Block multi-statement SQL by default. Block `DROP DATABASE`, `GRANT`, and `REVOKE`; require
  explicit user confirmation for supported high-risk mutations.
- Prompts and UI warnings do not replace enforcement in shared Java services and adapters.
- AI may draft explanations and review material; deterministic code owns mastery, queue priority,
  interventions, permissions, and other authoritative learning state.
- AI and cloud features must fail safely without blocking the core local SQL-learning flow.

### Privacy and operations

- Never expose passwords, tokens, API keys, connection details, private course documents, student
  records, or unrestricted analytics in logs, errors, screenshots, tests, or responses.
- Redact sensitive values while retaining useful failure classification.
- Do not send private project or student data to external services without explicit user
  authorization.
- For production work, inspect read-only state first, back up before mutation, define rollback,
  make the minimum change, and verify locally and through public HTTPS.
- Production API traffic goes through Nginx at `https://api.sqlteacher.tech`; the Java API remains
  loopback-bound at `127.0.0.1:18080` unless an explicit architecture change is approved.

## JavaFX

- Prefer the existing FXML/CSS separation.
- Never block the JavaFX application thread with database, file, network, or AI work.
- Long-running work must represent loading, success/empty, and understandable failure states.
- Risky SQL needs a clear confirmation dialog.
- Keep pages usable at lower resolutions and do not present placeholders or mock data as real
  functionality.
- Prefer service/controller tests that do not require fragile UI automation.

## Verification Ladder

Choose the narrowest command that proves the change, then widen for risk:

```powershell
mvn -q test "-Dtest=RelevantTest"
mvn -q test "-Dtest=FirstTest,SecondTest"
mvn -q test -Pfast
mvn test
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.TechnologyVerificationApp"
mvn -q exec:java "-Dexec.mainClass=com.sqlteacher.StageOneVerificationApp"
mvn javafx:run
./packaging/package-stage1.ps1
```

- Quote comma-separated Maven `-Dtest` selectors in PowerShell.
- Run focused tests first.
- Use `-Pfast` for broad local feedback without `integration`, `runner`, or `live` tagged tests; it is not a release gate.
- Run full `mvn test` for cross-module, schema, security, accumulated, or release-bound changes.
- Use CLI verification apps in headless environments; run `mvn javafx:run` only with graphics.
- Packaging or release changes require the current packaging script and release workflow gates.
- Documentation-only changes need link and diff checks, not Maven, unless they affect executable
  examples, generated artifacts, runtime configuration, or packaging inputs.
- If verification cannot run, report the exact command, failure, and remaining risk. Never weaken
  or remove tests merely to pass.

## Documentation

Use `docs/README.md` as the map:

- stable guidance: `docs/guide/`
- plans and decision scope: `docs/plans/`
- acceptance evidence: `docs/acceptance/`
- release notes: `docs/releases/`
- production records: `docs/operations/`
- completed implementation history: `docs/history/stages/`
- copyright materials: `docs/copyright/`

Keep historical plans accurate to their time; add status and links instead of rewriting their
past assumptions. Keep `docs/` root small and update indexes when adding a category document.
For copyright materials, never infer identity, ownership, applicant, authorization, or publication
facts; the user must supply them.

## Git And External Actions

- Use focused commit messages in `type(scope): short description` form.
- Do not stage, commit, push, tag, publish a Release, deploy, restore, rotate credentials, or change
  external state unless the user requested that action.
- When a commit or release is requested, use `main` directly and skip PR creation unless explicitly
  requested.
- Before committing, inspect staged files and exclude secrets, generated data, and unrelated work.
- Never use destructive Git commands to discard user changes.

## Completion Gate

Do not claim completion until:

- the requested behavior or document change is present;
- relevant focused tests and risk-appropriate wider checks passed, or limitations are explicit;
- public contracts, schema, safety behavior, and docs agree;
- the runtime path was exercised when practical;
- no secret or generated junk was added;
- the final response names the important files and verification results.
