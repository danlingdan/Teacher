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
2. Confirm the target version is new and all user-visible changes have release notes under `docs/releases/vX.Y.Z.md`.
3. Inspect the current packaging script instead of assuming artifact names or gates from an older release.

## Run local gates

1. Run `mvn test` and record totals, failures, errors, and intentional skips.
2. Run frontend tests/audit, Rust tests, then `./packaging/package-v3.ps1` from PowerShell with JDK 25.
3. Verify the current-version EXE, Windows x64 ZIP, `SHA256SUMS.txt`, Java CycloneDX SBOM, and UI CycloneDX SBOM required by the script.
4. Confirm checksum entries match the intended release artifacts and stale versioned EXE/ZIP files are absent from `target/installer`.
5. Verify the packaged sidecar contract/runtime and smoke-start the portable Tauri executable when the environment supports it.
6. Inspect ZIP entries for `.secrets`, `.env`, `app-data`, databases, logs, credentials, private course material, and unexpected `target` content. Do not print secret values.

## Publish and verify

When authorized, commit the version and documentation together, push `main`, create and push the matching `vX.Y.Z` tag, then follow the triggered GitHub Actions run. Verify that the workflow tests, packages, signs the stable update manifest, uploads the expected assets, and publishes a non-draft, non-prerelease Release marked latest.

Verify remote metadata and asset names by default. Do not download remote Java artifacts solely to compare hashes unless the user requests that comparison.

Stop before tagging if local gates fail, the worktree contains unrelated release changes, the version/tag mismatch, or required signing configuration is unavailable. Report the exact gate and leave recoverable state.
