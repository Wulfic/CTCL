# Decisions

> **Append-only.** Newest entries at the bottom. Never edit or delete a past decision —
> if it gets reversed, append a new entry that says so and links back to it.
>
> Record a decision when a real alternative existed. "Used the standard library function"
> is not a decision. "Chose Postgres over SQLite because we need concurrent writers" is.
>
> Write the **reasoning**, not the diff. Git already stores what changed; it cannot store
> why, or what you rejected.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Context:** what forced the decision
**Choice:** what we did
**Why:** the actual reason
**Rejected:** what we didn't do, and why not
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — Agent memory is repo-local files, not an MCP server
**Context:** Instructions mandated persisting decisions to the `mem0` MCP server, but
`mem0` was never present in the active `.mcp.json`, so nothing was actually being recorded.
**Choice:** Memory lives in `.agent/memory/` as committed Markdown: `decisions.md`,
`gotchas.md`, `state.md`.
**Why:** Works identically in Copilot and Claude Code with no network call, survives a
dead server, and shows up in code review where it can be corrected. A memory write that
silently no-ops is worse than none, because it feels like it worked.
**Rejected:** `mem0` MCP — cross-project recall wasn't worth an invisible failure mode on
the one guarantee that matters most. Also rejected keeping both as a mirror: two sources
of truth means neither is trusted.
**Affects:** `AGENTS.md`, `.agent/memory/`, `.github/skills/recall-session`, `.github/skills/save-memory`

## 2026-08-12 — Dropped the `think` MCP server
**Context:** Every skill routed reasoning through `mcp_wulfnet-think_think_invoke_tool`
before acting.
**Choice:** Removed the server and all call sites. Reasoning happens natively; the
`think-plan` skill was replaced by `plan-work`, which produces a written plan in `TODO.md`.
**Why:** Opus 5 reasons natively. The round-trip added latency and a failure mode without
improving output. A plan written to a file is also a checkable artifact — an MCP call is
not, which matters under Copilot where step-skipping can't be enforced.
**Rejected:** Keeping it for the `criticize` operation — self-review against an explicit
checklist in `code-review` covers the same ground without the dependency.
**Affects:** `AGENTS.md`, `.mcp.json`, all skills that previously called `think`

## 2026-08-12 — MCP surface cut to `github` + `context7` only
**Context:** Nine MCP servers were configured. `filesystem` and `git` had zero references
in any skill — dead config. The rest duplicated capability that is now native to both
Copilot and Claude Code.
**Choice:** Dropped `filesystem`, `git`, `gitnexus`, `playwright`, and `context-mode`.
Kept `github` (with a `gh` CLI fallback) and `context7`.
**Why:** The bar is now "does this do something native tools cannot?" Only two clear it.
`context7` serves live docs that training data can't; `github` returns structured JSON for
issues/PRs/CI. Everything else was a network round-trip and a failure mode in exchange for
what grep, the terminal, and native web fetch already do.
**Rejected:** Keeping `gitnexus` for cross-repo symbol lookup — real capability, but it
doesn't pay for itself on single-repo work, which is the norm here. Keeping `playwright`
for E2E — see the separate entry below; that turned out to be a category error on our part.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`, `AGENTS.md`,
`.github/copilot-instructions.md`, `code-explore`, `research-docs`

## 2026-08-12 — E2E runs through the project's own runner, not an MCP browser
**Context:** Dropping the `playwright` MCP server looked like it would remove E2E testing
capability, since `test-iterate` routed E2E through the old `web-task` skill.
**Choice:** E2E tests are committed spec files executed from the terminal
(`npx playwright test`, `npx cypress run`). The `web-task` skill was deleted.
**Why:** The playwright *MCP server* and Playwright *the test library* are different
things, and conflating them was the original mistake. Ad-hoc agent browser-driving isn't
a test — it can't run in CI and it isn't repeatable. Committed specs are strictly better,
and they need a terminal, not an MCP.
**Rejected:** Keeping the MCP for exploratory UI checks — real but rare, and native web
fetch plus the runner's own `--headed` mode and trace viewer cover the debugging case.
**Affects:** `test-iterate`, `AGENTS.md`, deleted `web-task`

## 2026-08-12 — Merged `git-ops` and `github-workflow` into `git-github`
**Context:** Two skills split one linear workflow: branch → commit → push → PR → CI.
**Choice:** Single `git-github` skill covering local git and GitHub remote operations.
**Why:** The split forced loading two skills for one continuous task and duplicated the
pre-commit checklist across both. The seam was in the docs, not in the work.
**Rejected:** Keeping them separate as "local vs remote concerns" — a clean distinction on
paper that nothing in the actual workflow respects.
**Affects:** `.claude/skills/git-github/`, `.github/skills/git-github/`, `AGENTS.md`,
`.github/copilot-instructions.md`

## 2026-08-12 — Destructive-action doctrine: prose first, permission rules as backup
**Context:** The template had no unified stance on irreversible operations — only scattered
rules in `git-github` (force-push, `.env`). Nothing covered filesystem deletion, database
drops, container/volume pruning, or the guards that code *we write* should carry. Needed a
gate that survives Copilot (no permission layer, no hooks) and a truncated context.
**Choice:** Two layers. (1) Doctrine — a `Destructive Actions` section in `AGENTS.md` as
rule zero, the `guard-destructive` skill holding the full procedure, and per-skill guards
in `code-review`, `git-github`, `build-run`, `plan-work`, `test-iterate`. (2) Mechanical —
`permissions.deny` (unrecoverable) and `permissions.ask` (destructive-shaped) in a new
`.claude/settings.json`. The doctrine is primary; the rules are a seatbelt.
**Why:** Permission rules match on **command prefix**, so `cd sub && rm -rf ../..` walks
straight past them, `PowerShell` coverage is unverified, and Copilot has no permission
layer at all. Anything that only exists as config would therefore cover roughly half the
actual usage. Conversely, prose alone loses to a truncated context. Each layer covers the
other's blind spot, and stating the rules' limits in writing is what stops them being
trusted past their range.
**Rejected:** A `PreToolUse` command-hook script — it would need to be cross-platform and
correct, and a hook that silently no-ops is worse than none (same reasoning that dropped
`mem0`). A `type: "prompt"` hook classifying every shell command semantically *would* catch
the compound-command cases, but costs a model call on every `ls`; documented in
`guard-destructive` as an opt-in escalation instead of a default. Also rejected
`disableBypassPermissionsMode` — locking the owner out of their own tool without asking.
**Affects:** `AGENTS.md`, `.github/copilot-instructions.md`, `.claude/settings.json`,
`.claude/skills/guard-destructive/`, `.github/skills/guard-destructive/`, `code-review`,
`git-github`, `build-run`, `plan-work`, `test-iterate`, `TODO.md`

## 2026-09-23 — CTCL scope: private, uncapped, SP + LAN, removal-safe
**Context:** The plan left three questions open: id and license, a server cap, and target
environments. The owner answered them before Stage 1.
**Choice:**
- Mod id `ctcl`, package `net.wulfnet.ctcl`, license `ARR`. The repo stays private. (Superseded 2026-10-22: public, MIT.)
- No cap on the number of tables or on chunk count, with no config.
- The target is singleplayer plus Open to LAN. Removing the mod must never break a save.
**Why:** It's a personal mod for the owner's own worlds, played with trusted LAN guests.
Caps and permission checks would only get in the owner's way, and there is no untrusted
player to defend against. "Removal can't break a save" is the owner's hard requirement.
It rules out any registry content (blocks, items, block entities) and any persisted state
of a mod-owned type inside vanilla files.
**Rejected:**
- A server config with per-player or per-dimension caps: nobody needs it.
- Dedicated-server support as a tested target: it's not a use case, though nothing in the
  design prevents it.
**Affects:** `TODO.md` (Constraints, Stage 4, Stage 6), `fabric.mod.json`

## 2026-09-23 — No click rate limit on the cycle packet
**Context:** Plan step 4.4 proposed a per-player rate limit, because one click re-tickets up
to 81 chunks.
**Choice:** Dropped it.
**Why:** The owner ruled out limits of any kind. Adding and removing a ticket is cheap, and
chunk loading is asynchronous, so the real cost is the chunks the player *asked* to load,
which is the whole point of the mod. A rate limit would only make fast clicking feel broken.
**Rejected:** Coalescing clicks into one ticket update per tick. It's a possible
optimisation if 6.2 load testing shows a spike, not a v1 feature.
**Affects:** `TODO.md` Stage 4

## 2026-09-23 — One git branch per Minecraft version band, fixes cherry-picked forward
**Context:** The mod targets 1.21.11 (obfuscated, Java 21, remapping Loom) and 26.x
(unobfuscated, Java 25, non-remap Loom). The build scripts differ between them, and so may
some APIs.
**Choice:** `band/<mc>` branches. A fix lands in the oldest affected band and is
`git cherry-pick -x`'d forward. Tooling lands on `main` and is merged main → band only.
A new band is created only when widening the version range of the newest band fails.
The rules are in `BANDS.md`.
**Why:** The owner chose it. Each branch builds with plain Loom and no preprocessor, and
cherry-picking keeps version-specific build files from leaking between bands.
**Rejected:**
- Stonecutter or Architectury: more tooling than a small mod needs, and the owner declined it.
- Merging band → band: it drags `build.gradle` and `gradle.properties` across versions.
**Affects:** `BANDS.md`, all `band/*` branches

## 2026-09-23 — Loader state in a per-dimension SavedData, not a block entity
**Context:** Each crafting table needs a stored size, but the vanilla crafting table has
no block entity.
**Choice:** A `SavedData` (`ctcl_loaders`) per dimension maps `BlockPos → size`, storing
only non-zero sizes. A validation pass drops entries whose block is no longer a crafting table.
**Why:** A block entity means replacing or mixing a BE into `CraftingTableBlock`, which
writes mod data into chunk NBT and breaks, or at least spams errors, when the mod is
removed. A SavedData file in `data/` is ignored by vanilla, and it survives a remove and
reinstall.
**Rejected:**
- A block entity: it breaks removal safety.
- Block state properties: they add registry-visible state, and modifying a vanilla block
  breaks vanilla LAN clients.
**Affects:** `LoaderData`, Stage 3

## 2026-09-23 — Mod-owned non-persistent chunk ticket, not setChunkForced
**Context:** We need the chunks to stay loaded while the table is set, and to stop loading
when the mod is removed.
**Choice:** A mod-owned, non-persistent ticket type, re-applied from `LoaderData` on
level load.
**Why:** `setChunkForced` shares state with `/forceload`, so turning a table off would
unforce chunks someone force-loaded by hand, and the reverse. Forced chunks are also saved
in vanilla data, so they would keep loading after the mod was removed. A non-persistent
ticket is never saved, so removing the mod leaves nothing behind.
**Rejected:**
- `setChunkForced` / `/forceload` state: it collides with `/forceload` and persists after removal.
- A persistent custom ticket: it's written to `chunks.dat` under a type that no longer
  exists once the mod is gone.
**Affects:** `LoaderTickets`, Stage 3

## 2026-09-23 — Mojang official mappings on the 1.21.11 band
**Context:** 1.21.11 is the last obfuscated release. 26.x ships with Mojang names, and
Fabric no longer publishes Yarn for it.
**Choice:** Use `officialMojangMappings()` on `band/1.21.11`.
**Why:** Every symbol then has the same name in both bands, so the 26.1 port is mostly a
build-script change instead of a rename of every symbol. Cherry-picking fixes forward
also stays clean.
**Rejected:** Yarn on 1.21.11. It's more common in older Fabric examples, but every
cherry-pick to 26.x would need a hand-rename.
**Affects:** `band/1.21.11` `build.gradle`

## 2026-09-23 — Stable releases only, for every build dependency
**Context:** FabricMC's example mod for 1.21.11 pins `loom_version=1.17-SNAPSHOT`.
**Choice:** The owner ruled that every version is a stable release, in every band. That
covers Loom, Gradle, Fabric Loader (`"stable": true` in meta.fabricmc.net), Fabric API,
and any library. No `-SNAPSHOT`, `-alpha`, `-beta`, or `-rc`.
**Why:** The owner said "always use stable releases". A snapshot also resolves to
different bytes over time, so a band branch wouldn't build the same way twice.
**Rejected:** Copying the example mod's `1.17-SNAPSHOT`. We use `1.17.21`, the newest
stable release in that line.
**Affects:** every band's `gradle.properties`, `gradle-wrapper.properties`, `BANDS.md` porting checklist

## 2026-09-23 — Tests: JUnit 5 + Mockito for logic, Fabric gametests for integration
**Context:** The owner asked for Mockito. The plan had plain JUnit for pure logic and
gametests for everything that touches Minecraft.
**Choice:** Three layers:
1. **JUnit 5 + Mockito, the bulk of the suite.** Server logic sits behind small seams we
   own: `TicketSink` (add or remove a ticket), `LoaderStore` (read or write sizes), and a
   `MenuResolver` that returns a crafting-table position or empty for an open container.
   The cycle handler, validation pass, removal, and overlap logic are tested against
   mocks, with `verify(...)` / `verifyNoInteractions(...)` asserting that bad input
   touches nothing.
2. **`fabric-loader-junit`** for tests that need real Minecraft classes but no world,
   such as the SavedData codec round-trip and the decode-failure guard, run after
   `Bootstrap.bootStrap()`.
3. **Fabric gametests**, only for what a mock can't prove: that a ticket actually makes
   the N×N chunks entity-ticking, that breaking or pushing a table releases them, and
   that chunks come back after reload.
**Why:** Mockito makes the guard tests cheap and exact ("a wrong container id → the
`TicketSink` is never called"), and they run in milliseconds. Mocks of vanilla
internals, though, only prove the code calls what we *think* loads a chunk. The 1.21.5
ticket rework is exactly where that belief can be wrong, so the real-world layer stays,
kept narrow.
**Rejected:**
- Mocking `ServerLevel` or `ChunkMap` directly: deep vanilla objects need a bootstrapped
  registry, and the mocks encode our assumptions about vanilla, not vanilla's behaviour.
  We mock our own seams instead.
- Dropping gametests: that would leave no test proving the mod's whole purpose.
**Versions (stable):** JUnit BOM 5.14.4, Mockito 5.24.0, fabric-loader-junit 0.19.5. We
stay on JUnit **5**, not 6.x, because fabric-loader-junit 0.19.5 is built against the
JUnit 5 platform (`junit-bom` 5.10.0 in its POM).
Mockito's inline mock maker is attached with `-javaagent` in the `test` task, not
self-attached, because JDK 21+ warns about dynamic agent loading.
**Affects:** `build.gradle`, `src/test/`, Stages 2–5

## 2026-09-23 — TODO.md and .agent/memory/ commit separately on the band being worked
**Context:** Band rule 4 says docs land on `main` and flow main → band. But progress
tracking and gotchas are written *while* working on a band branch, and a truncated
session has to recover from the branch it's on.
**Choice:** On a band branch, `TODO.md` and `.agent/memory/` changes go in their own
`docs(memory): …` commits and never mix with code. At a stage boundary, cherry-pick those
commits onto `main` (`git cherry-pick -x`).
**Why:** The branch you're on always holds the current plan and memory. `main` stays the
source of truth. Memory-only commits cherry-pick cleanly because they never touch build files.
**Rejected:**
- Switching to `main` for every memory write: it would interrupt band work constantly and
  invite committing in the wrong tree.
- Merging band → main: forbidden by band rule 3.
**Affects:** `BANDS.md` rule 4, every band branch

## 2026-09-23 — 26.1: loader sizes reset when a 1.21.11 world is upgraded
**Context:** 26.1 moves per-dimension saved data to `<world>/dimensions/<ns>/<dim>/data/`
and upgrades old world folders with a "file fixer" that only knows vanilla files.
`SavedDataType` now takes an `Identifier`, not a bare string.
**Choice:** On `band/26.1` the data id is `ctcl:loaders`, stored at
`dimensions/<ns>/<dim>/data/ctcl/loaders.dat`. There's no import of the 1.21.11
`ctcl_loaders.dat`: after a Minecraft upgrade every table reads 0 until someone clicks it
again. The old file is never read, moved, or deleted. The README says so.
**Why:** The owner chose it (2026-09-23). A migration would mean reading a file whose
post-upgrade location depends on vanilla's file fixer, plus a guard and a cross-version
test, all to save a few clicks.
**Rejected:**
- A one-time import of the legacy file. It's the extra code path above, and it's reversible later if asked for.
- Keeping the `minecraft:ctcl_loaders` id so the filename matches: it squats on the vanilla
  namespace and still wouldn't match, because the parent folder moved.
**Affects:** `band/26.1` `server/LoaderData.java`, `tools/uninstall_check.py`, `README.md`

## 2026-09-23 — 26.1 pins Loom 1.17.21, not 1.18.x
**Context:** Loom 1.18.2 is the newest stable release. Fabric's example mod for 26.1 – 26.1.2 uses `1.17-SNAPSHOT`.
**Choice:** Pin `1.17.21`, the same Loom as `band/1.21.11`, and change only the plugin id
to the non-remapping `net.fabricmc.fabric-loom`.
**Why:** One variable at a time. The port already changes the platform (unobfuscated MC,
Java 25, renamed Fabric API). A new Loom line on top would make every build failure ambiguous.
**Rejected:** Loom 1.18.2 now. It's a candidate for its own later bump, tested on its own.
**Affects:** `band/26.1` `gradle.properties`

## 2026-09-23 — No CI; branches pushed to the private origin
**Context:** `gh auth` started working (Wulfic, keyring), which unblocked TODO 6.5.
**Choice:** The owner declined CI. `main` and `band/1.21.11` were pushed (fast-forward and a new branch, no force) to the private `Wulfic/CTCL`.
**Why:** The owner chose it. Actions minutes cost money on a private repo, and the local
suite (`build`, `runClientGameTest`, `uninstall_check.py`) is the gate.
**Rejected:** A build-only workflow, and build plus an xvfb client gametest.
**Affects:** TODO 6.5, `.github/workflows/` (none)

## 2026-09-24 — 26.2 and 26.3 get their own bands, even with test-only divergence
**Context:** The 26.2 widening test (band rule 1) compiled `main` and `client` unchanged, but
`CtclClientGameTest` broke: `Minecraft#screen` → `Gui#screen`, plus a Fabric gametest rename.
26.3 also breaks it, since GLFW was replaced by SDL.
**Choice:** The owner chose (2026-09-24) `band/26.2` from `band/26.1` and `band/26.3` from `band/26.2`.
Each declares `~26.x` and is built and fully tested against that version. Divergence in any
compiled source set, tests included, counts as "the code has to diverge".
**Why:** A single jar with a widened range would need proof that the 26.1-built jar runs
unmodified on 26.2/26.3. The recompile-based check doesn't give that proof, and the
26.1-built gametest jar can't run there at all.
**Rejected:**
- One branch with per-version jars: the test would need reflection to pick `client.screen` or `client.gui.screen`.
- A single jar across 26.1–26.3: it needs a new binary-compat harness, for an unproven gain.
**Affects:** `BANDS.md`, `band/26.2`, `band/26.3`

## 2026-09-24 — Cut a band only for a major code change (supersedes "band per version" above)
**Context:** The owner clarified band rule 1: "we only need to cut bands if there is a major
code change. If we can get one band to work for multiple versions with relative ease, we
should be doing that." That replaces the answer recorded in the entry above.
**Choice:** `band/26.1` covers 26.1 – 26.3. `main` and `client` compile unchanged on all
three. The client gametest's version-specific calls are replaced with calls that exist in
all three: a `ScreenEvents` tracker instead of `client.screen`, `InputConstants` instead of
GLFW, and no chunk-render wait. The band ships **one jar per Minecraft version from the same
source**: `fabric.mod.json` declares `~${minecraft_version}`, and `gradle.properties` maps each
version to its Fabric API pin (`fabric_api.<mc>`), so `-Pminecraft_version=<mc>` is enough.
Each jar is built and fully tested against exactly the version it declares.
**Why:** One source tree means one place to fix bugs, with no cherry-picking between bands.
Per-version jars keep the rule that a jar is tested on what it declares.
**Rejected:**
- `band/26.2` and `band/26.3`: the only divergence was a handful of test lines, which is not a
  major code change. `band/26.2` (`9896337`) was never pushed.
- One jar with a wide range (e.g. 26.1 – 26.3): it needs proof that the 26.1-built jar runs
  unmodified on 26.2/26.3. The recompile-based suite can't show that.
- Version-specific source directories or reflection in the test: both unnecessary, because
  version-neutral calls exist.
**Rule of thumb:** divergence you can remove with a version-neutral call is not a reason for a
band. A new band is for when the mod's own code (mixins, core logic, API use) has to differ.
**Affects:** `BANDS.md` rule 1, `band/26.1` `build.gradle`, `gradle.properties`, `fabric.mod.json`, `CtclClientGameTest.java`

## 2026-09-24 — CI with automatic releases (reverses "No CI" of 2026-09-23)
**Context:** The owner asked for CI that bumps the version and cleans up old tags after a successful build.
**Choice:** GitHub Actions (`.github/workflows/ci.yml`, on `main`, merged into the bands). A green push to
`band/**` releases when the commits since the band's last tag call for it:
- `feat` → minor; `fix`/`perf` → patch; `!` or `BREAKING CHANGE` → major. Other types don't release.
- A change in the jar's range label forces at least a patch release.
- The first release is `mod_version` (1.0.0).

The version comes **from tags** (`tools/release.py plan` → `-Pmod_version`). CI never commits a bump.
After a release, `tools/release.py cleanup --force` keeps only that band's newest tag and release (owner: keep 1).
The owner chose to release right away, before the LAN checks (6.1 and 7.8b).
**Why:** It's what the owner asked for. Tag-derived versions mean no bot commits on band branches, no push
permission for the bot to write code, and no bump commits racing a developer's push.
**Rejected:**
- A bump commit to `gradle.properties`: bot writes to a band, a loop risk, and noise in the history.
- A patch on every push: docs-only pushes would release.
- Manual dispatch only: the owner wanted it automatic.
- Keeping 3 per band, or superseded patches only: the owner chose to keep 1.
**Cost:** A Free private repo gets 2000 Actions min/month. A 26.x push runs about 5 jobs. Docs-only paths are ignored.
**Affects:** `.github/workflows/ci.yml`, `tools/release.py`, `BANDS.md` rule 5

## 2026-09-24 — One range jar per band, proven by a production harness (reverses per-version jars)
**Context:** The owner: "multiversion jars should be labeled with the mc jar range i.e 26.1-26.3 covering
everything inbetween." Stage 8 rejected a single range jar, because recompiling per version can't show that
the 26.1-built jar runs on 26.2 or 26.3.
**Choice:** Built on the band's base (lowest pin), the jar is `ctcl-<v>+26.1-26.3.jar` and declares
`"minecraft": ["~26.1", "~26.2", "~26.3"]`. The missing proof is now a harness:
- `prodServerGameTest` and `prodClientGameTest` (Loom `ServerProductionRunTask` / `ClientProductionRunTask`)
  run the **prebuilt** ctcl and gametest jars in a production Fabric install of each pinned version.
- CI runs them on every pin, next to the existing recompile suite.
**Why:** The owner's call. The harness turns "compiles on 26.3" into "the shipped bytes run on 26.3", which was
the objection in Stage 8.
**Rejected:**
- `">=26.1 <26.4"`: it admits `26.4-snapshot-N`, which sorts below 26.4. An OR of `~` ranges excludes it.
- Keeping per-version jars and only renaming: the owner asked for one jar that covers the range.
**Affects:** `band/26.1` `build.gradle`, `fabric.mod.json`, `TESTING.md`, `README.md`

## 2026-09-24 — Byte-identity is the primary proof of a range jar; production gametests back it up
**Context:** The range jar (above) needs proof that the 26.1-built bytes work on 26.2 and 26.3.
**Choice:** `checkReleaseJarBytes` requires the release jar's classes and resources, apart from
`fabric.mod.json` and the manifest, to be byte-identical to each pinned version's compile. The full suite
also runs on each version. Identical bytes plus a green suite means the shipped jar is tested there.
The production gametests (`prodServerGameTest` and `prodClientGameTest`, with the prebuilt jars in a real
Fabric install) add a runtime check that covers what bytes can't: Loader accepting the `depends` range,
mixins applying, and the production classpath.
**Why:** The production client test alone missed nothing this time, but it only covers what its scenario
exercises. Byte-identity covers every class. It found that compile-time constants differ between versions
(`MOUSE_BUTTON_LEFT`), a trap no amount of recompiling shows.
**Rejected:** Production gametests only (partial coverage); recompile only (Stage 8's gap).
**If it fails:** a version whose compile differs isn't covered by the release jar. Either remove the
version-specific construct (usually an inlined constant) or cut a band (`BANDS.md` rule 1).
**Affects:** `band/26.1` `build.gradle`, `.github/workflows/ci.yml`

## 2026-09-29 — Quilt, NeoForge, and Forge on 26.x; 1.21.11 stays Fabric-only (reverses v1 "Not doing")
**Context:** v1 shipped Fabric-only on both bands. The owner wants the other loaders, with as few bands as possible.
**Choice:** Add Forge, NeoForge, and Quilt to the 26.x releases only. `band/1.21.11` stays Fabric and doesn't change.
All loaders share `band/26.1`: a loader never justifies a band. `BANDS.md` rule 1 still decides when one is cut.
The proposed layout is a multi-project Gradle build (`common` + one subproject per loader, each on its native plugin).
Quilt is covered by the Fabric build. Both are confirmed or changed in `TODO.md` Stage 10.
Owner rules (2026-09-29):
- The order is Quilt → NeoForge → Forge.
- **Stable loader versions only.** A loader with no stable build for a version skips that version.
- One jar may serve several loaders and versions if each is fully supported.
- The jar name lists the range plus each loader, e.g. `ctcl-1.0.0+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`.
**Why:** The owner's call. One band per loader would multiply the cherry-pick-forward work by four.
**Rejected:**
- A band per loader: it contradicts "keep bands to a minimum."
- Architectury: declined in v1, and it adds a runtime API dependency. It stays rejected unless Stage 10 research changes that.
**Affects:** `TODO.md`, `band/26.1` layout, `ci.yml`, `tools/release.py`, `BANDS.md`

## 2026-10-22 — Public release: fresh repo, MIT, no history
**Context:** The owner chose to release the repo publicly. The old history holds `.env` (passwords), the GitHub PAT, the
Context7 key, LAN hosts, and the owner's email, and closed PRs pin it through `refs/pull/*` (2026-09-29 entry).
**Choice:** The path that entry named: the old repo became the archived, private `Wulfic/CTCL-private-archive`, and
a fresh `Wulfic/CTCL` started from each branch tip as one commit (`main` the root, each band a child of it). The
credentials and MCP configs are local-only and gitignored, with `.mcp.example.json` as the template. The license moved
from ARR to MIT. Notes were redacted (hosts, local paths). Releases `v1.2.0+26.1-26.3` and `v1.0.0+1.21.11` were
re-published with the same jars, their tags on the fresh band commits. The commits use the GitHub noreply address.
**Why:** It's the owner's call. A fresh repo is the only way no old object stays reachable from the public one.
**Rejected:** Rewriting history in place (`refs/pull/*` keeps it reachable); deleting the old repo (keeps no record).
**Note:** Commit SHAs in memory and `TODO.md` before this date refer to the archived history, not this repo.
Every credential that was ever in the old repo must be rotated: the archive still holds it.
**Affects:** `.gitignore`, `.mcp.example.json`, `LICENSE`, `README.md`, mod metadata, `.agent/memory/`
