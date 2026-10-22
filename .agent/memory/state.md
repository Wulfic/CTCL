# Current State

> Rewritten at every phase boundary — not appended to. Keep it under ~30 lines so it is
> cheap to read at the start of every session. History belongs in `decisions.md` and
> `gotchas.md`; this file only answers "where am I right now?".

**Last updated:** 2026-10-22 (Stage 17 done: public release)
**Branch:** `band/26.1` for code, `main` for memory and `TODO.md`. **One folder**, branches switched in place
(`decisions.md` "One folder, no worktrees"). Stages 10–17 are done. Everything is pushed.

## Where we are
- **Public since 2026-10-22** (`decisions.md`, `TODO.md` Stage 17). `Wulfic/CTCL` is a fresh repo, MIT, one commit per
  branch. The old history is `Wulfic/CTCL-private-archive` (private, archived). **SHAs in these notes from before
  2026-10-22 refer to the archive**, not this repo.
- **Releases** (re-published byte for byte, sha256 checked): `v1.2.0+26.1-26.3` (one four-loader jar) and
  `v1.0.0+1.21.11`. Their tags are on the band tips. Backup: `~/.ctcl-backups/public-migration/`.
- **CI** (`ci.yml`): plan (with `check_pins.py`) → build → 14 verify jobs → release, on `band/**` pushes and
  `workflow_dispatch`. **A green band run releases.** Diagnose on a throwaway `ci/*` branch with its own workflow.

## What's next
1. **The owner:** rotate every credential that was in the old repo (GitHub PAT, Context7 key, every `.env` password).
   Copy `.env` into this folder by hand (an agent can't: `.env` is deny-listed). Swap the folder names with VS Code
   closed: `CTCL` → `CTCL-private-archive`, `CTCL-public` → `CTCL`.
2. **Watch:** Forge's early-window timeout (one retry, answer 43) and the Forge 26.1.2 restart hang (1 in 8 runs). On
   either, read `<console>-threads.txt` in the failure artifact.
3. **Pin notice:** NeoForge `26.3.0.40-beta` shipped (pin: `.39-beta`). Bumping a beta pin needs a fresh sweep (answer 31).
4. **The owner:** the LAN checklists in `TESTING.md`.

## Environment
- JAVA_HOME and PATH `java` are Temurin 25. JDK 21 is also installed. Use the Gradle wrapper only.
- `gh` is logged in as Wulfic. The `github` MCP doesn't connect; use `gh`. There's no `jq` in Git Bash, so use `gh ... -q`.
- Python is `python` (3.11), not `python3`. Python edits: always `encoding="utf-8"`.
- Commits use `34788214+Wulfic@users.noreply.github.com` (set in this clone's git config). Never the personal email.
- A long sweep dies with the session that started it (`gotchas.md`). Run it in the session that will read it.

## Do not forget
- `.env`, `.mcp.json`, `.claude/mcp.vscode-reference.json` are local-only and gitignored. Template: `.mcp.example.json`.
- Backups in `~/.ctcl-backups/`: releases, the superflat stash (patch + bundle), `ci/1.21.11-diag`, `band/26.2`.
- `.claude/skills/` and `.github/skills/` are hand-synced copies. Edit both.
