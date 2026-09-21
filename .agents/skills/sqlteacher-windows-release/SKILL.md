---
name: sqlteacher-windows-release
description: Prepare, verify, or publish a SQLTeacher Windows release, including Maven/npm/Cargo versioning, release notes, full tests, Tauri/NSIS artifacts, checksums, SBOMs, update manifest, Git tag, GitHub Actions, and public Release metadata. Use for release candidates, version bumps, installers, portable ZIPs, tags, or release verification; do not trigger for ordinary feature builds.
---

# Release SQLTeacher for Windows

## Separate preparation from publication

Do not commit, push, tag, or publish unless the user asked to perform the release. A request to inspect or prepare a release authorizes only local and read-only remote checks.

When publication is authorized, use the repository's solo workflow on `main`; do not create a PR unless explicitly requested.

## Establish the release baseline

1. Check `git status --short`, current branch, `pom.xml` version, existing tags, latest release notes, and `.github/workflows/release.yml`.
2. Probe the public `GET /api/v1/app/update-manifest` and record the version it currently serves; this release must supersede it, and a stale value here is a pending incident to close during publish.
2. Confirm the target version is new and all user-visible changes have release notes under `docs/releases/vX.Y.Z.md`.
3. Inspect the current packaging script instead of assuming artifact names or gates from an older release.

## Run local gates

1. Run `mvn test` and record totals, failures, errors, and intentional skips.
2. Run frontend tests/audit, Rust tests, then `./packaging/package-v3.ps1` from PowerShell with JDK 25.
3. Verify the current-version EXE, Windows x64 ZIP, `SHA256SUMS.txt`, Java CycloneDX SBOM, and UI CycloneDX SBOM required by the script.
4. Confirm checksum entries match the intended release artifacts and stale versioned EXE/ZIP files are absent from `target/installer`.
5. Verify the packaged sidecar contract/runtime and smoke-start the portable Tauri executable when the environment supports it.
6. Inspect ZIP entries for `.secrets`, `.env`, `app-data`, databases, logs, credentials, private course material, and unexpected `target` content. Do not print secret values.
7. LEG-15 (v3.4.2+) verification receipt: after all local gates pass and the release content is committed, run `./packaging/write-verification-receipt.ps1 -MvnTests <n> -NpmTests <n>` (it requires a clean worktree and binds the receipt to HEAD), commit `verification-receipt.json`, then tag that commit. The release workflow skips its own `mvn test`/`npm test` only when the receipt binds to the tag commit (or its parent when the sole diff is the receipt) and records zero failures; `npm ci`, `npm audit`, packaging, and contract checks always run. When the receipt is accepted, the local full `mvn test` + `npm test` are the only test execution for the release and must never be omitted; when it is missing or rejected, CI falls back to full tests. Release notes must state the mode ("测试执行：本地全量（CI 凭据跳过）" or "CI 全量"). Never tag a commit whose message contains `[skip ci]`: GitHub then silently skips the entire release workflow for that tag — no tests, no assets, no Release. (v3.6.0 shipped through that path deliberately as a local build; an accidental hit would look like a quiet no-op.)

## Publish and verify

When authorized, commit the version and documentation together, push `main`, create and push the matching `vX.Y.Z` tag, then follow the triggered GitHub Actions run. Verify that the workflow tests, packages, signs the stable update manifest, uploads the expected assets, and publishes a non-draft, non-prerelease Release marked latest. After the Release is public, deploy the CI-signed `update-manifest.json` from the new Release assets to `/opt/sqlteacher/shared/update-manifest.json` on the ECS host (SFTP to a temp name, verify, then `install -o root -g sqlteacher -m 0640` atomically; keep the previous version as backup). The endpoint reads the file per request, so no restart is required. Verify the public endpoint now decodes to the new version and that installer/portable URLs, sizes, and SHA-256 values match the Release assets. v3.4.3 shipped without this step and no client ever saw the 3.4.3 update; v3.4.4 repeated the near-miss in reverse (knowledge bundle deployed, app manifest stale at 3.4.2). Treat "Release published but update manifest not swapped" as an unfinished release. Before the release announcement is finished, complete the release write-back checklist (v3.4.0 DOC-8/DOC-9; both historical drift incidents trace to missing this step):

- Update the docs baseline set: `docs/README.md` "当前基线", `docs/releases/README.md` header status, the release note's date line (replace any "未发布/本地候选" wording with the actual publish date), and the matching `docs/plans/README.md` status column.
- Update the capability baseline: the sections of `docs/guide/00-capability-overview.md` this release touched plus its 适用版本 header, and the 适用版本 headers of `docs/guide/08-user-manual.md` and `docs/guide/09-teacher-manual.md`. New user-facing capabilities must be discoverable in the overview before the announcement is finished (see `sqlteacher-capability-audit`).
- Update the GitHub Release page itself: the body must carry the full changelog sourced from `docs/releases/vX.Y.Z.md`, the status line must show the real date (a published page must never say "未发布"), and "Latest" must point at the new version. Fix earlier published pages the same way if they still carry candidate wording.
- Run the pre-release verification app as part of local gates: `mvn -q exec:java "-Dexec.classpathScope=test" "-Dexec.mainClass=com.sqlteacher.ReleaseVerificationApp"`.

Verify remote metadata and asset names by default. Do not download remote Java artifacts solely to compare hashes unless the user requests that comparison.

Stop before tagging if local gates fail, the worktree contains unrelated release changes, the version/tag mismatch, or required signing configuration is unavailable. Report the exact gate and leave recoverable state.
