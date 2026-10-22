# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-01 (Stage 16.6, third push)
**Branch:** `band/26.1` for code, `main` for memory and `TODO.md`. **One folder:** `CTCL`. Switch
branches in place; the worktrees are gone (`decisions.md` "One folder, no worktrees"). Stages 10–15 are done, and Stage 16 is in progress.
Stage 16 work is committed locally and isn't pushed.

## Where we are
- **Stages 10–15 are done (2026-10-01).** Everything is pushed: `main`, `band/26.1` (`b37e23b`), `band/1.21.11` (unchanged).
- **Releases:** `v1.1.0+26.1-26.3` (`b37e23b`) ships `...+26.1-26.3-Fabric-Quilt.jar` and `...+26.1.2-26.2-Forge-NeoForge.jar`.
  `v1.0.0+1.21.11` (`d6f16e6`) ships one jar. Keep-1 removed `v1.0.0+26.1-26.3`, whose jar and notes are in `~/.ctcl-backups/`.
- **CI** (`ci.yml`, now on both `main` and `band/26.1`): plan → build → verify (one job per loader and pin) → release. It triggers
  on `band/**` pushes and `workflow_dispatch`. **A green band run releases.** Diagnose on a throwaway `ci/*` branch with its own workflow.
- Remote branches: `main`, `band/26.1`, `band/1.21.11`. No stashes and no extra local branches.

## What's next
1. **Stage 16** (`TODO.md`): Forge and NeoForge on 26.1–26.3. Owner answer 27: a beta only when there's no stable build.
   16.0, 16.1, and 16.2 are done. 16.2 (`db0a4d8`, band/26.1) adds the six pins and one loader range per pinned version
   (answer 29: up to the next pin, and a line's last pin runs to the end of the line). `checkLoaderRange` guards it.
   `release.py plan` now gives one four-loader jar.
   16.3 (`3f0af53`): `tools/check_pins.py` in CI's `plan` job enforces answer 27 against the live APIs and fails closed.
   Forge `-latest` pins are marked in `forge_beta_versions` (answer 30); a newer beta is a notice (answer 31).
   16.4 (sweep green at `5b04a79`): the one 1.2.0 jar runs on all four loaders and every pin, betas included, so there's no split.
   It found three test-only/harness bugs, fixed in `10e3d45`, `1363ee1`, and `5b04a79` (`gotchas.md` 2026-10-01).
   16.5: release notes mark beta pins (`bf00424`), band docs (`ea2508e`), `BANDS.md` (`8fb8130` on main).
   **16.6 is in progress** (owner answers 38–43). The v1.1.0 backup is verified (`~/.ctcl-backups/release-v1.1.0+26.1-26.3/`;
   undo in `TODO.md` 16.6). The first push (`8391a44`, run 36851633937) wasn't green, so nothing was released: two flakes,
   then, on the rerun, Forge's early-window timeout plus a dead client that never exited. 16.6a (`cc621cd`) kills a client whose
   main thread died and retries the window timeout once. The second push (run 36914711767) showed the matcher missed the
   death mid-line; `2e63d34` fixes it (attempt 2 of 3). Next: merge main → band, push `main` + `band/26.1`, watch CI, and record
   the measured minutes in `BANDS.md` (estimate: ~146 runner-min per push). `build/release` holds the swept 1.2.0 jar.
2. **Open:** the Forge 26.1.2 restart hang (`gotchas.md` 2026-10-01; 1 in 8 CI runs). On a recurrence, read `<console>-threads.txt`.
3. **The owner:** the LAN checklists in `TESTING.md`, and rotating the `ghp_` PAT.

## Environment
- JAVA_HOME and PATH `java` are Temurin 25. JDK 21 is also installed. Use the Gradle wrapper only.
- `gh` is logged in as Wulfic. The `github` MCP doesn't connect; use `gh`. There's no `jq` in Git Bash, so use `gh ... -q`.
- Python is `python` (3.11), not `python3`. Python edits: always `encoding="utf-8"`.
- A long sweep dies with the session that started it (`gotchas.md`). Run it in the session that will read it.

## Do not forget
- Backups in `~/.ctcl-backups/`: v1.0.0's release, the superflat stash (patch + bundle), `ci/1.21.11-diag`, and `band/26.2`.
- **Public since 2026-10-22** (`decisions.md`): `.env` and the MCP configs are local-only and gitignored; never commit
  them. Old SHAs in these notes refer to the archived private history.
- CI runs on push only (plus `workflow_dispatch`). `.claude/skills/` and `.github/skills/` are hand-synced copies. Edit both.
