# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-09-29
**Branch:** `band/26.1`. Stage 9 is done: CI is green and the first releases are out.

## Where we are
- **Released 2026-09-28:** `v1.0.0+1.21.11` (`d6f16e6`) and `v1.0.0+26.1-26.3` (`934de8a`), each a GitHub release with one jar.
  After a release, CI keeps only the newest tag and release per band.
- **CI** (`.github/workflows/ci.yml`): plan → build (base) → verify (each other pin) → release. Client steps have
  `timeout-minutes`, because a client with no render backend hangs. `mesa-vulkan-drivers` is installed because 26.3's OpenGL
  finds no GLX visual under xvfb and falls back to Vulkan (lavapipe). Loom's own xvfb wrapper is used as-is.
- The client gametest turns off the menu blur. On CI's llvmpipe, the blur starved the harness's one-tick-per-frame lockstep (see `gotchas.md`).
- `band/26.1` ships one range jar; `band/1.21.11` ships one jar. How it's proven: `BANDS.md` and `decisions.md` (2026-09-24).
- `band/26.2` is deleted. Its backup is `~/.ctcl-backups/band-26.2-9896337.bundle`.

## What's next
0. **Next up (2026-09-29): Quilt → NeoForge → Forge on 26.x** (stable loader builds only; no stable build means
   that version is skipped). 1.21.11 stays Fabric-only. The owner has answered everything. Start with `TODO.md` Stage 10 (research, no code).
1. **The owner:** the LAN checklist in `TESTING.md` on 1.21.11 and 26.x (6.1, 7.8b). This is post-release.
2. A new Minecraft version: add a `fabric_api.<mc>` pin, which widens the label, and push. CI proves it or fails.
   If CI breaks on a new version, check Fabric API's `build.yml` on that branch first.

## Environment
- JAVA_HOME and PATH `java` are Temurin 25. JDK 21 is also installed. Use the Gradle wrapper only.
- `gh` is logged in as Wulfic. The `github` MCP doesn't connect; use `gh`. There's no `jq` in Git Bash, so use `gh ... -q`.
- Python is `python` (3.11), not `python3`. Python edits: always `encoding="utf-8"`.
- Worktrees: `../CTCL-main` (main), `../CTCL-1.21.11` (band). Remove them with `git worktree remove` once they're clean.

## Do not forget
- `../CTCL-1.21.11` `stash@{0}` holds a previous session's superflat WIP. It was superseded by the blur fix and is safe to drop once the owner agrees.
- The local branch `ci/1.21.11-diag` (`5a34b22`) holds the jstack/top CI probe (throwaway; its PR #3 is closed and the remote is deleted).
- **Public since 2026-10-22** (`decisions.md`): `.env` and the MCP configs are local-only and gitignored; never commit
  them. Old SHAs in these notes refer to the archived private history.
- `.claude/skills/` and `.github/skills/` are hand-synced copies. Edit both.
