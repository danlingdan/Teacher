---
name: sqlteacher-capability-audit
description: Audit SQLTeacher's real implementation surface against its documentation, reconcile drift, and maintain the capability baseline (docs/guide/00-capability-overview.md). Use before major-version planning, after large feature waves, or when a shipped capability is found missing or misstated in docs; not for routine single-feature doc updates.
---

# Audit SQLTeacher Capabilities Against Documentation

## When to run

- Before major-version planning: the plan must build on the real surface, not stale docs.
- When a shipped capability is discovered missing or misstated in docs, treat it as a class of
  failure and sweep for siblings instead of fixing one line.
- After a release wave that touched multiple workspaces; otherwise on a minor-version cadence.

## Method: three lenses, evidence-backed

Run three read-only sweeps and reconcile the results (parallel read-only agents work well, one per
lens). Every finding carries `file:line`. Verify disputed facts directly in code — migration seed
data, enums, and Spring wiring configs are ground truth; guide documents are not.

1. **Java implementation lens** (`src/main/java`, `src/main/resources`): capability areas with
   entry classes, IPC bridge sections (`desktop/bridge`), Spring wiring configs, migration chain
   heads (local `SqliteSchemaMigrator`, cloud `CloudSchemaMigrator`), the full server route table
   (`SqlTeacherCloudServer`), provider/task enums, and seeded content (courses, exercise banks,
   prompts). Flag "implemented but never wired" code (a bean with no bridge/server caller) — it is
   not a user capability.
2. **UI and shell lens** (`ui-web/src`, `ui-web/src-tauri`): the navigation array and routes,
   per-workspace user abilities, the IPC contract surface (`ui-web/src/shared/ipc.ts` — the
   authoritative list of callable methods), unreachable components, hardcoded identifiers,
   placeholder or mock content. Rust must contain no business logic and no student-code execution.
3. **Delivery and docs lens** (`packaging/`, `.github/workflows`, git tags, `docs/`): release
   chain gates, the version-number declaration points, index completeness, applicability-header
   compliance, and every docs claim that contradicts code.

## Reconciliation rules

- Code wins over docs for current behavior. Dated plans and history stay historically accurate:
  add status notes, never rewrite past assumptions as current truth.
- Update `docs/guide/00-capability-overview.md` first — it is the single authority for "what the
  product does now". Then fix the manuals, the deep guides' applicability headers, and indexes.
- Implemented-but-unwired code (dead services, dead IPC contracts) goes to
  `docs/plans/backlog.md` with evidence and a disposition (wire it, document as a diagnostic tool,
  or remove). Do not document it as a capability.
- Report newly found hazards outside audit scope (version desync, silent release-skip conditions,
  security smells) to the user in the final report even when you do not fix them.

## Validate

Run the documentation link checker (see `sqlteacher-documentation`), `git diff --check`, and
`git status --short`. The audit itself is read-only; only the documentation, backlog, and skill
updates it justifies may write.
