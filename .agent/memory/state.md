# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-01 (Stage 16 done)
**Branch:** `band/26.1` for code, `main` for memory and `TODO.md`. **One folder:** `CTCL`. Switch
branches in place; the worktrees are gone (`decisions.md` "One folder, no worktrees"). Stages 10–16 are done. Everything is pushed.

## Where we are
- **Stage 16 is done (2026-10-01).** Forge and NeoForge cover 26.1–26.3 like Fabric/Quilt (a beta pin only where there's no
  stable build, owner answer 27). Pushed: `main`, `band/26.1` (`8374989`), and `band/1.21.11` (unchanged).
- **Releases:** `v1.2.0+26.1-26.3` (`8374989`) ships one jar, `ctcl-1.2.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`.
  `v1.0.0+1.21.11` (`d6f16e6`) ships one jar. Keep-1 removed v1.1.0. Its jars and notes are in `~/.ctcl-backups/release-v1.1.0+26.1-26.3/`.
- **CI** (`ci.yml` on `main` and `band/26.1`): plan (with `check_pins.py`) → build → 14 verify jobs → release. It triggers on
  `band/**` pushes and `workflow_dispatch`. **A green band run releases.** A green run costs ~203 billed minutes, so ~9 pushes a month.
  Diagnose on a throwaway `ci/*` branch with its own workflow.
- The harness kills a client whose main thread died (dump in `<console>-threads.txt`, now in the failure artifact), and it retries
  Forge's 10 s early-window timeout once (answer 43). Before the fix, that timeout hit 2 runs in a row.

## What's next
1. **Watch:** Forge's early-window timeout. If the one retry also times out, ask the owner about `earlyWindowControl = false`
   on CI (it would reverse answer 43). Also the Forge 26.1.2 restart hang (1 in 8 runs). On either, read `<console>-threads.txt`.
2. **Pin notice:** NeoForge `26.3.0.40-beta` shipped (pin: `.39-beta`). Bumping a beta pin needs a fresh sweep (answer 31).
3. **The owner:** the LAN checklists in `TESTING.md`, and rotating the `ghp_` PAT.

## Environment
- JAVA_HOME and PATH `java` are Temurin 25. JDK 21 is also installed. Use the Gradle wrapper only.
- `gh` is logged in as Wulfic. The `github` MCP doesn't connect; use `gh`. There's no `jq` in Git Bash, so use `gh ... -q`.
- Python is `python` (3.11), not `python3`. Python edits: always `encoding="utf-8"`.
- A long sweep dies with the session that started it (`gotchas.md`). Run it in the session that will read it.

## Do not forget
- Backups in `~/.ctcl-backups/`: v1.0.0 and v1.1.0 releases, the superflat stash (patch + bundle), `ci/1.21.11-diag`, and `band/26.2`.
- **Public since 2026-10-22** (`decisions.md`): `.env` and the MCP configs are local-only and gitignored; never commit
  them. Old SHAs in these notes refer to the archived private history.
- CI runs on push only (plus `workflow_dispatch`). `.claude/skills/` and `.github/skills/` are hand-synced copies. Edit both.
