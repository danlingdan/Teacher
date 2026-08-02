---
name: sqlteacher-documentation
description: Create, reorganize, audit, or update SQLTeacher Markdown documentation, indexes, plans, guides, acceptance evidence, release notes, operations records, and historical stage files. Use for documentation-only tasks or when a code change requires coordinated document updates; do not use for copyright forms where identity, ownership, or publication facts must be supplied by the user.
---

# Maintain SQLTeacher Documentation

## Use the document map

Start from `docs/README.md` and place each fact in one category:

| Purpose | Location |
| --- | --- |
| Stable user or developer guidance | `docs/guide/` |
| Planned scope and decisions | `docs/plans/` |
| Acceptance commands and evidence | `docs/acceptance/` |
| User-visible version changes | `docs/releases/` |
| Production operation records | `docs/operations/` |
| Completed implementation history | `docs/history/stages/` |
| Copyright evidence and generators | `docs/copyright/` |

Keep `docs/` root small. Do not create a new top-level category when an existing one fits.

## Preserve chronology without presenting it as current

- Keep dated plans and operation records immutable in meaning. Add status notes or link to later evidence instead of rewriting old assumptions as if they were never made.
- Derive the current version from `pom.xml` and Git tags. Do not copy a version into navigation without checking it.
- Maintain one authority for each fact and link to it from other documents.
- Move completed stage records under `docs/history/stages/`; update every repository-relative Markdown link and inline path reference in the same change.
- Never infer applicant identity, ownership, publication status, school authorization, or other copyright facts. Mark them for user confirmation.

## Validate

Run the bundled link checker from the repository root:

```powershell
./.agents/skills/sqlteacher-documentation/scripts/check-markdown-links.ps1
```

Then run `git diff --check` and inspect `git status --short`. Documentation-only changes do not require Maven tests unless they alter generated artifacts, executable examples, packaging inputs, or runtime configuration. Report moved/deleted material explicitly and say whether it is recoverable through Git.
