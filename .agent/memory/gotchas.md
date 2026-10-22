# Gotchas

> **Append-only.** Traps, dead ends, and environment quirks that cost real time.
>
> The test for whether something belongs here: **would a competent developer hit this
> again in six months?** If yes, write it down. If it's obvious from reading the code,
> it does not belong here.
>
> Log a failed approach as carefully as a successful one. "We tried X, it doesn't work
> because Y" is what stops the next session from burning an hour on X.

## Entry template

```markdown
## YYYY-MM-DD — Short title
**Symptom:** what you actually saw (error text, wrong output, hang)
**Cause:** the real root cause
**Fix:** what worked
**Dead ends:** what looked right but wasn't, and why
**Affects:** src/path/to/file.ts
```

---

## 2026-08-12 — `.gitignore` does not cover `.env`
**Symptom:** `.env` is tracked in git despite a large "Secrets / config" section in
`.gitignore`.
**Cause:** That section only lists `credentials*`, `secrets*`, `*.pem`, `*.key`, `*.crt`.
There is no `.env` pattern, and a file already tracked stays tracked even after a matching
pattern is added.
**Fix:** Not applied — the repo owner opted to leave credential handling as-is for now.
**Update 2026-10-22:** Fixed for the public repo: `.env` and both MCP configs are untracked and gitignored; the template is `.mcp.example.json`.
Anything spawned from this template inherits both the tracked `.env` and the pattern gap.
**Dead ends:** Adding `.env` to `.gitignore` alone does nothing for an already-tracked
file; it needs `git rm --cached` as well.
**Affects:** `.gitignore`, `.env`, `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — The two MCP config files disagreed on hosts (RESOLVED)
**Symptom:** `.mcp.json` and `.claude/mcp.vscode-reference.json` pointed at different self-hosted MCP hosts.
**Cause:** The self-hosted MCP stack moved from one host to another. `.mcp.json` was updated; the reference file
was not.
**Fix:** The reference file was realigned to the current hosts. (Host details are redacted in the public repo.)
**Dead ends:** We initially guessed the `.186` file might be the newer one because
`AGENTS.md` still described a docker-compose stack — the prose was simply stale too. The
`// description` comment in `.mcp.json` is the reliable source here, not the prose.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — mcp-compressor 0.31.4 changed its CLI flags
**Symptom:** Server configs written with `--compression-level` and `--header` fail on
current mcp-compressor. Backends without an `Authorization` header fail differently —
mcp-compressor auto-attempts OAuth discovery, which these backends don't support.
**Cause:** 0.31.4 renamed `--compression-level` → `--compression`, `--header` → `-H`, and
now requires a `--` separator before the backend URL and its args.
**Fix:** Use `--compression`, `-H`, and `--`. For backends with no real auth, pass a
placeholder `-H "Authorization=Bearer unused"` to suppress OAuth discovery.
**Dead ends:** Omitting the Authorization header entirely looks correct for an unauthed
backend, but triggers the OAuth path and fails with an unrelated-looking error.
**Affects:** `.mcp.json`, `.claude/mcp.vscode-reference.json`

## 2026-08-12 — A newly created `.claude/settings.json` does not load in the session that created it
**Symptom:** Added `permissions.deny` / `permissions.ask` rules, then ran `dropdb --help`
(matches the `ask` rule `Bash(dropdb*)`). It executed straight through to
`bash: dropdb: command not found` — no prompt, no block.
**Cause:** The settings watcher only watches directories that already contained a settings
file when the session started. `.claude/settings.json` did not exist at session start, so
the new file stays invisible until the session restarts (or `/hooks` is opened, which
reloads config). Nothing wrong with the file — it parses and matches the published schema.
**Fix:** Restart the session, or open `/hooks` once. Verify afterward with a harmless
matching command such as `dropdb --help`.
**Dead ends:** Re-checking the JSON and the rule syntax — both were correct the whole time.
The failure looks exactly like a bad matcher pattern, which is the trap: **a permission
rule that silently doesn't load is indistinguishable from one that doesn't match.** Same
class of invisible-failure problem that got `mem0` dropped. Probe with a real command
rather than assuming the rules are live.
**Affects:** `.claude/settings.json`, `.claude/skills/guard-destructive/SKILL.md`

## 2026-09-23 — The 1.21.11 Loom plugin id is `net.fabricmc.fabric-loom-remap`, not `fabric-loom`
**Symptom:** The plan assumed the classic `fabric-loom` id for the obfuscated 1.21.11 band.
**Cause:** Since Loom 1.14 there are two plugin ids. `net.fabricmc.fabric-loom-remap` is for
obfuscated versions (≤ 1.21.11) and keeps `modImplementation` / `remapJar`.
`net.fabricmc.fabric-loom` is the non-remapping plugin for unobfuscated 26.x. The legacy
`fabric-loom` marker still resolves, which makes the wrong choice look fine.
**Fix:** `band/1.21.11` copies FabricMC/fabric-example-mod branch `1.21.11` (checked 2026-09-23):
- `id 'net.fabricmc.fabric-loom-remap'`, pinned to **1.17.21**. The example uses
  `1.17-SNAPSHOT`, which we don't use because snapshots aren't reproducible.
- Gradle **9.5.1**, `mappings loom.officialMojangMappings()`, `splitEnvironmentSourceSets()`.
- Loader **0.19.5** (the stable release for 1.21.11) and Fabric API **0.141.6+1.21.11**.
- `fabric.mod.json` uses `"minecraft": "~1.21.11"` and `"java": ">=21"`.
**Dead ends:** Reading the version list on fabricmc.net/develop from memory. Query
`https://meta.fabricmc.net/v2/versions/loader/<mc>` and the maven-metadata.xml files instead.
The example mod's branch per MC version (`git ls-remote`) is the reference for build scripts.
It has branches for `26.1`, `26.1.1`, `26.1.2`, `26.2`, and `26.3`.
**Affects:** `band/1.21.11` `build.gradle`, `gradle.properties`, `settings.gradle`

## 2026-09-23 — Killing `gradlew runClient` orphans the Minecraft JVM
**Symptom:** After stopping a background `./gradlew runClient`, the game window stayed open.
A `java.exe` running `-Dfabric.dli.config=...\CTCL\...` was still alive.
**Cause:** Loom launches the game in a child JVM. Killing the Gradle wrapper process
doesn't kill that child on Windows.
**Fix:** Close the game window normally, or find the child by its command line and stop
only that PID:
`Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match 'fabric\.dli\.config=.*CTCL' }`.
The title screen is reached once the log shows `Sound engine started` followed by the
atlas `Created:` lines.
**Dead ends:** `TaskStop` on the background shell reports success but only kills Gradle.
**Affects:** `runClient`, `runServer`, any automated launch check

## 2026-09-23 — Vanilla tickets are deduplicated by (type, level): no reference count
**Symptom:** None yet. Found reading the 1.21.11 `TicketStorage` source. Two tables in the same
chunk at the same size would add identical tickets. Turning either table off would
remove *the* ticket and unload chunks the other table still needs.
**Cause:** `TicketStorage.addTicket` finds an existing ticket with the same type and level
at that chunk, resets its timeout, and returns `false` without adding a second one.
`removeTicket` removes the first match. Tickets have no owner identity.
**Fix:** `LoaderTickets` keeps its own in-memory refcount keyed by `(ChunkPos, level)`.
It calls vanilla `addTicketWithRadius` only on 0→1 and `removeTicketWithRadius` only on
1→0. The refcount is rebuilt from `LoaderData` on level load, since tickets are
non-persistent. Mockito tests cover it through the `TicketSink` seam.
**Dead ends:** Relying on vanilla to count. Also giving each table its own ticket type,
which would mean a registry entry per table.
**Affects:** `LoaderTickets`, Stage 3

## 2026-09-23 — 1.21.11 ticket facts (from decompiled source)
- `TicketType` is a record `(long timeout, int flags)` in `BuiltInRegistries.TICKET_TYPE`.
  The flags are PERSIST=1, LOADING=2, SIMULATION=4, KEEP_DIMENSION_ACTIVE=8, and
  CAN_EXPIRE_IF_UNLOADED=16. Vanilla `FORCED` is 15.
- **Our type is `new TicketType(0L, 14)`**: load, simulate, and keep the dimension active,
  with no timeout and **no PERSIST**. It's registered with `Registry.register` in
  `onInitialize`. `TicketStorage.packTickets` saves only `persist()` types, so nothing
  of ours reaches `chunks.dat`.
- `addTicketWithRadius(type, pos, r)` places level `33 - r`, and ENTITY_TICKING is
  level ≤ 31. The level spreads +1 per chunk to all 8 neighbours (`ChunkTracker`), which
  is a Chebyshev square, and `SimulationChunkTracker` spreads the same way.
  **One ticket per table at the table's chunk with radius `r + 2`** (level `31 - r`)
  makes the full `(2r+1)²` square entity-ticking. Size 1 gives level 31, the same as
  `/forceload`'s `ChunkMap.FORCED_TICKET_LEVEL`.
- `TICKET_TYPE` is **not** synced. Fabric registry-sync 6.2.6 (Fabric API 0.141.6) has an
  allowlist of 28 synced registries, and `TICKET_TYPE` (intermediary `field_55881`) isn't
  on it. A custom ticket type is invisible to clients, so vanilla LAN guests can still join.
- In loaded chunks with no player nearby, random ticks and block entities still run,
  because `forEachBlockTickingChunk` iterates every entity-ticking chunk. **Natural mob
  spawning needs a player within 128 blocks** (`collectSpawningChunks` →
  `anyPlayerCloseEnoughForSpawningInternal`). This goes in the README.
**Affects:** `LoaderTickets`, `CtclMod`, `README.md`

## 2026-09-23 — A SavedData that fails to decode is silently replaced with empty data
**Symptom:** None yet. Found reading 1.21.11 `DimensionDataStorage`.
**Cause:**
- When the codec fails, `readSavedData` logs `Failed to parse saved data` and returns
  `null`. It also returns `null` on any exception.
- `computeIfAbsent` then constructs a **fresh empty instance** and `set()`s it, which
  marks it dirty, and the next save **overwrites the file**. Every stored table size is lost.
- `.resultOrPartial` also accepts a *partial* decode, so a lenient codec can drop entries
  with no failure at all.
**Fix (Stage 3.2):** Before calling `computeIfAbsent`, if `data/ctcl_loaders.dat` exists,
read and decode it ourselves. On failure, copy it to `ctcl_loaders.dat.corrupt-<timestamp>`,
log ERROR with both paths, and only then continue. Decode per entry, where a bad entry
is skipped with a WARN; one bad pos must not cost the whole map.
Pass **`null` as `DataFixTypes`**: Fabric API's object-builder `DimensionDataStorageMixin`
(21.1.40) makes `readTagFromDisk` skip datafixing for a null type. Using a vanilla
`DataFixTypes` would run vanilla fixers over our schema on version upgrades.
**Dead ends:** Trusting `computeIfAbsent` to "load or create". It can't tell a missing
file from a corrupt one.
**Affects:** `LoaderData`, Stage 3.2

## 2026-09-23 — Block-removal hook in 1.21.11 is best-effort only
**Symptom:** `onRemove` is gone, and 1.21.5 split it up.
**Cause:** The replacement is `BlockBehaviour.affectNeighborsAfterRemoval(state,
ServerLevel, pos, movedByPiston)`. `LevelChunk.setBlockState` calls it only when the
block changes **and** the flags include UPDATE_NEIGHBORS (1) or MOVE_BY_PISTON (64).
Pistons call it directly. Any `setBlock` without flag 1 skips it, including some
commands and editors. Block entities have a separate hook, `preRemoveSideEffects`, which
crafting tables don't use.
**Fix:** Mixin-add an override of `affectNeighborsAfterRemoval` on `CraftingTableBlock`
only, so no other block pays for the hot path. Keep the 20-tick validation pass as the
**guarantee**; the hook only makes release immediate.
**Affects:** Stage 3.5, `CraftingTableBlockMixin`

## 2026-09-23 — Crafting menu position and integrated-server pause (1.21.11)
- `CraftingMenu` has a `private final ContainerLevelAccess access`. For a real table it's
  `ContainerLevelAccess.create(level, pos)` from `CraftingTableBlock.getMenuProvider`.
  The 2×2 inventory grid is `InventoryMenu`, not `CraftingMenu`, but
  `CraftingMenu(int, Inventory)` uses `ContainerLevelAccess.NULL`, so the resolver must
  handle an empty access (`evaluate` returns empty). `stillValid` checks
  `Blocks.CRAFTING_TABLE` and distance. Get the position with an `@Accessor` for `access`,
  then `access.evaluate((level, pos) -> pos)`.
- `IntegratedServer.tickServer`: `paused = Minecraft.isPaused() || no players`.
  `Minecraft.pause` is true only when **not published to LAN**. In singleplayer, the
  pause menu stops all ticking, loaded chunks included. Once the world is open to LAN it
  never pauses. This goes in the README.
**Affects:** `CraftingMenuAccessor`, Stage 4.2, `README.md`

## 2026-09-23 — The server gametest world persists between runs; `clearRunDirectory` doesn't clear it
**Symptom:** The piston gametest failed differently on each run. The third run logged
`Restored 2 crafting table loader(s)` at startup: tables and saved sizes left over from
earlier runs were already at the test positions, so the piston faced two tables.
**Cause:** `runGameTest` reuses `build/run/gameTest/world`. In Loom 1.17.21,
`configureTests { clearRunDirectory = true }` only registers `deleteGameTestRunDir` for
the **client** gametest run (checked in the `FabricApiTesting` bytecode), so the setting
does nothing for server gametests.
**Fix:** Each gametest works in its own far region and clears it first
(`placeTable`). Clearing with `UPDATE_ALL` fires the removal hook, so stale entries are
released too. We did **not** add our own delete task, because hermeticity through the
tests themselves avoids writing deletion code. `./gradlew clean` resets the world by hand.
**Dead ends:** Believing `clearRunDirectory` covered both runs because the setting sits
on the shared `configureTests` block.
**Affects:** `build.gradle`, `src/gametest/.../LoaderGameTests.java`

## 2026-09-23 — Pushing a loader table with a piston can freeze the push
**Symptom:** In the piston gametest, the table stayed a `moving_piston` forever.
**Cause:** The push fires the removal hook and releases the table's own loader, as the
spec requires ("a moved table starts at 0"). If that loader was the only thing keeping
the chunk ticking, the `MovingPistonBlockEntity` never ticks again, so the push only
finishes when a player comes near.
**Fix:** This is expected product behaviour, and it goes in the README. The gametest
force-loads its chunk with a vanilla ticket to stand in for a nearby player.
**Affects:** `README.md` (Stage 6.4), `LoaderGameTests`

## 2026-09-23 — Client gametests: not part of `build`, and they pass silently
**Symptom:** `./gradlew runClientGameTest` ends with `BUILD SUCCESSFUL` and no "tests
passed" line, which looks the same as a test that never ran.
**Cause:** Fabric's client gametest runner just exits 0 when `runTest` returns. A
failure throws: `AssertionError: Timed out waiting for predicate` and exit 1.
`runClientGameTest` needs a display, so Loom doesn't attach it to `check`; `build` runs
only the server gametests.
**Fix:** Run `./gradlew runClientGameTest` by hand after any client change. It opens a
real window for about 2 minutes. We proved the test isn't vacuous by mutating one
expected label and watching it fail. Cursor coordinates for `TestInput.setCursorPos`
are **window pixels**, so multiply GUI coordinates by `getWindow().getGuiScale()`.
**Also:** `GameTestHelper.makeMockServerPlayerInLevel()` is deprecated for removal in
1.21.11. `GameTestSupport.joinTestPlayer` repeats its steps with non-deprecated calls, so
the build stays suppression-free under `-Werror`.
**Affects:** `src/gametest/.../CtclClientGameTest.java`, `GameTestSupport.java`

## 2026-09-23 — 26.1 porting sources, and the world-storage move
**Symptom:** Fabric's 26.1 blog post says "major changes to world storage" but doesn't
explain them. `data/ctcl_loaders.dat` paths are hard-coded in `tools/uninstall_check.py`.
**Cause:** In 26.1, per-dimension data moves to
`<world>/dimensions/<namespace>/<path>/data/`. `SavedDataType(Identifier, ...)` resolves
`ns:a/b` to `data/ns/a/b.dat`. `DimensionDataStorage` was renamed `SavedDataStorage`, not
one-to-one, and `MinecraftServer#getDataStorage` now exists for global data. A new
`FileFixerUpper` rewrites old world folders on first load of a newer version (copy-on-write
into a temp folder, then swapped in), so downgrading is rarely possible.
**Fix:** Read these, not memory:
- `https://fabricmc.net/2026/03/14/261.html`, plus the 26.2 and 26.3 posts on the same blog.
- The NeoForged primer: `gh api -H "Accept: application/vnd.github.raw"
  "repos/neoforged/.github/contents/primers/26.1/index.md"` (also `26.2` and `26.3`).
  It's vanilla-only and about 5000 lines, so grep it.
- The example mod's build files: `gh api ... repos/FabricMC/fabric-example-mod/contents/<file>?ref=26.1`.
- Stable versions: `meta.fabricmc.net/v2/versions/{loader,game}` and each artifact's `maven-metadata.xml`.
**Dead ends:** Assuming a `ctcl_loaders.dat` path carries over. Also, Fabric API
`0.145.x+26.1.1` and `0.155.x+26.1.2` are separate lines per patch version.
**Affects:** `band/26.1` `LoaderData.java`, `tools/uninstall_check.py`

## 2026-09-24 — 1.21.11 → 26.1 rename map (what actually broke)
**Symptom:** 13 main, 2 client, and 11 gametest compile errors after the build-script swap. All were renames.
**Cause:** Fabric API moved to Mojang names in 26.1, and vanilla refactored a few types.
**Fix:**
| 1.21.11 | 26.1 |
|---|---|
| `ServerWorldEvents.LOAD/UNLOAD` | `ServerLevelEvents.LOAD/UNLOAD` |
| `ServerTickEvents.END_WORLD_TICK` | `END_LEVEL_TICK` |
| `PayloadTypeRegistry.playC2S()/playS2C()` | `serverboundPlay()/clientboundPlay()` |
| `ScreenEvents.beforeRender(screen)` | `beforeExtract(screen)`; same lambda arity, `GuiGraphicsExtractor` |
| `Screens.getButtons(screen)` | `Screens.getWidgets(screen)` |
| `DimensionDataStorage` | `SavedDataStorage`; `getDataFile(Identifier)` is still private, and `readTagFromDisk(Path, DataFixTypes, int)` now takes the path |
| `SavedDataType("ctcl_loaders", …)` | `SavedDataType(Identifier, …)` → `data/<ns>/<path>.dat` |
| `new ChunkPos(pos)`, `.x`, `.z`, `ChunkPos.asLong` | `ChunkPos.containing(pos)`, `.x()`, `.z()`, `ChunkPos.pack` (it's a record now) |
| `singleplayer.getClientWorld()` (client gametest) | `getClientLevel()` |
| `data/chunks.dat` (vanilla tickets) | `dimensions/<ns>/<dim>/data/minecraft/chunk_tickets.dat` |
A `null` `DataFixTypes` is still safe: Fabric API's object-builder `SavedDataStorageMixin`
null-checks before vanilla's unguarded `type.update(...)`. The corrupt-file guard was
verified end to end on 26.1 by corrupting the gametest world's `loaders.dat` by hand.
**Dead ends:** Reading Fabric API names from memory. Read them from the module jars with
`javap` (the umbrella jar nests them under `META-INF/jars/`), or from `genSources`
(`.gradle/loom-cache/minecraftMaven/.../*-sources.jar`). 26.1 is unobfuscated, so
`javap` on `~/.gradle/caches/fabric-loom/minecraftMaven/.../minecraft-common-deobf-26.1.jar` works before genSources finishes.
**Affects:** `band/26.1` everything under `src/`, `tools/uninstall_check.py`

## 2026-09-24 — Testing hotfix versions without scratch branches
**Symptom:** The plan called for a scratch branch per hotfix, which then has to be deleted (a destructive step).
**Fix:** Override on the command line: `./gradlew build -Pminecraft_version=26.1.2
-Pfabric_api_version=0.155.3+26.1.2`, and pass `--minecraft 26.1.2` to the uninstall check.
Nothing to clean up. **Afterwards run `./gradlew clean build`:** the persistent gametest
world has been upgraded to the newer version, and the older one can't load it.
**Limit:** This recompiles against the hotfix, so it proves source compatibility, not
that the jar built against 26.1 runs unchanged on 26.1.2.
**Affects:** `TESTING.md`, band rule 1 widening tests (Stage 8)

## 2026-09-24 — main → band merges conflict on TODO.md and .agent/memory/
**Symptom:** `git merge main` on `band/1.21.11` conflicted on `TODO.md` and all three memory files.
**Cause:** Memory commits reach `main` by cherry-pick (band rule 4), so they're different
commits with the same content. The merge base stays old, and both sides edited the same lines.
**Fix:** Prove the band's docs equal some `main` commit (`git diff --stat HEAD <main-sha> --
TODO.md .agent/memory/` is empty), so `main` is a strict superset. Then take `main`'s side:
`git checkout --theirs -- TODO.md .agent/memory/...`, check that `git diff --cached main --
TODO.md .agent/memory` is empty, and commit. Once merged, the base moves forward, and the
next merge is clean as long as nothing new was cherry-picked in between.
**Dead ends:** Resolving hunk by hunk. The content is identical, so hand-resolving only risks dropping lines.
**Affects:** `BANDS.md` rule 4, every band branch

## 2026-09-24 — A gametest run can die after passing when the machine runs out of commit memory
**Symptom:** `runGameTest` logged "All 14 required tests passed", then failed with `os::commit_memory ...
The paging file is too small` (errno 1455). The next run restored 0 loaders, because the crashed run never saved.
**Cause:** Another project's Gradle build and two VS Code Java language servers were running at the same
time, leaving about 8 GB of free commit on a 40 GB limit. It wasn't our code, and no JVMs were orphaned.
**Fix:** Rerun it. If "Restored 0" follows a crash, check `build/run/gameTest/logs/latest.log` from a
clean run before suspecting the restore path. Don't kill processes that belong to other projects.
**Affects:** `./gradlew build` on this machine

## 2026-09-24 — Compile-time constants make "compiles on 26.3" differ from "the 26.1 jar runs on 26.3"
**Symptom:** The client gametest built on 26.1 passed on 26.1 but timed out on 26.3 in the production
harness: every click landed nowhere. The per-version recompile passed on 26.3.
**Cause:** `InputConstants.MOUSE_BUTTON_LEFT` is a `static final int`: 0 under GLFW (26.1) and 1 under
SDL (26.3). javac copies the value into the caller's class file, so the 26.1-built jar passed 0 on 26.3.
The recompile-per-version suite can't see this.
**Fix:** The test reads the button at runtime (`options.keyAttack.getDefaultKey().getValue()`). For
the mod itself, `checkReleaseJarBytes` requires the release jar to be byte-identical to each pinned
version's compile, so any inlined value that differs between versions fails CI. On 2026-09-24, all
33 classes were identical on 26.1 through 26.3.
**Affects:** `CtclClientGameTest`, `build.gradle` (`checkReleaseJarBytes`)

## 2026-09-24 — Loom's production run tasks add the project's own jar to `mods`
**Symptom:** The production client run on 26.3 loaded `ctcl 1.0.0+26.3` (the fresh recompile), not the
prebuilt 26.1 jar. The server run happened to pick the right one.
**Cause:** The `AbstractProductionRunTask` constructor (Loom 1.17.21, bytecode) adds `jar`/`remapJar`
and `productionRuntimeMods` to `mods`. `mods.from(...)` appends, so there were two `ctcl` jars, and
Loader picked either one.
**Fix:** Use `mods.setFrom(...)`. `checkProdMods` fails the task unless the effective set is exactly
fabric-api plus one ctcl jar and one gametest jar. Mutation-checked: reverting to `from` makes it refuse.
**Affects:** `build.gradle` (`prodServerGameTest`, `prodClientGameTest`)

## 2026-09-24 — The fabric-api bundle jar doesn't contain the gametest API modules
**Symptom:** The production server refused to start: `ctcl-gametest requires fabric-gametest-api-v1, which is missing`.
**Cause:** Of the modules in the fabric-api POM, `fabric-gametest-api-v1`, `fabric-client-gametest-api-v1`, and
`fabric-api-deprecated` aren't nested in the bundle jar. Dev runs get them as separate artifacts.
**Fix:** Resolve fabric-api transitively and keep only the bundle plus the two gametest modules (a name regex).
The count is asserted to be 3.
**Affects:** `build.gradle` (`fabricApiMods`)

## 2026-09-24 — Loom parses the source fabric.mod.json, so it must stay valid JSON
**Symptom:** `Failed to parse fabric.mod.json: src/main/resources/fabric.mod.json` at configuration time
after the `minecraft` dependency became a bare `${minecraft_depends}` placeholder (for a JSON array).
**Fix:** The source keeps `"minecraft": "${minecraft_depends}"`, which is a valid string, and
`processResources` filters out the whole quoted placeholder and puts the array in before `expand`.
**Affects:** `build.gradle` `processResources`, `fabric.mod.json`

## 2026-09-24 — A production server run dir shared across versions breaks on the older version
**Symptom:** `prodServerGameTest` on 26.1 failed right after a 26.3 run: `Not a map: "minecraft:sandstone"` chunk errors.
**Cause:** Both runs used `build/run/prodServerGameTest/world`, and 26.1 can't read chunks 26.3 saved. It's the
same trap as the hotfix gotcha above.
**Fix:** One run directory per version, `run/prodServerGameTest/<mc>` (and the same for the client). No deletion code.
**Affects:** `build.gradle`

## 2026-09-24 — Python on Windows reads text as cp1252 unless told otherwise
**Symptom:** A scripted edit's `assert s.count(old) == 1` failed on a line containing `–`.
**Cause:** `Path.read_text()` uses the locale encoding (cp1252) on Windows. Round-trips happen to
preserve bytes, but matching non-ASCII text fails.
**Fix:** Always pass `encoding="utf-8"` to `read_text` and `write_text` in tooling and one-off edits.
**Affects:** any Python that edits repo files

## 2026-09-24 — Loom run tasks use xvfb by themselves on CI
**Context:** `AbstractRunTask.useXvfb` (and `ClientProductionRunTask.useXVFB`) default to true when the
`CI` env var is set and `xvfb-run` exists (Loom 1.17.21, bytecode). So `runClientGameTest` needs no
wrapper on GitHub Actions. The workflow still installs xvfb if it's missing, because without it Loom
silently runs with no display.
**Affects:** `.github/workflows/ci.yml`

## 2026-09-24 — `gradlew` was committed as 100644, so Linux CI couldn't run it
**Symptom:** The first CI runs on both bands failed in about a second: `Process completed with exit code 126` on `./gradlew`.
**Cause:** The repo was created on Windows, which doesn't track the executable bit. `git ls-tree <band> gradlew`
showed `100644`. Locally, Git Bash runs it anyway, so nothing ever showed it.
**Fix:** `git update-index --chmod=+x gradlew` on `band/1.21.11` (`71f4447`), cherry-picked to `band/26.1`.
Any new script that CI runs directly needs the same (Python tools are run as `python3 <script>`, so they don't).
**Affects:** `gradlew` on every band

## 2026-09-28 — 26.3 client on CI: no GLX visual, no Vulkan surface, then a silent hang
**Symptom:** 26.3 verify: `Failed to create window for OpenGL context: Couldn't find matching GLX visual`, then
`Vulkan is not supported: Installed Vulkan doesn't implement the VK_KHR_surface extension`, then the client **hung**,
with no crash and no exit, until the job limit. 26.1 through 26.2 (GLFW) open a GL window on the same runner.
**Dead end:** *The 8-bit Xvfb screen.* Loom runs a bare `xvfb-run -a` (Debian default `1280x1024x8`). Running Gradle inside
`xvfb-run -s "-screen 0 1920x1080x24"`, with Loom's wrapper off, still gave the same GLX error. Reverted.
**Fix:** install `mesa-vulkan-drivers` (lavapipe), as FabricMC/fabric-api's own `client_test` job does on 26.3 with Loom's
default xvfb. The runner image has no Mesa Vulkan ICD, so the Vulkan fallback had no surface extension.
**Also:** every CI step that starts a client has `timeout-minutes`. A client with no backend hangs, and the first run burned 60 minutes.
**Look first:** when a Minecraft version breaks on CI, read Fabric API's `.github/workflows/build.yml` on that version's branch.
**Affects:** `.github/workflows/ci.yml`

## 2026-09-28 — 1.21.11 client gametest "Timeout loading world" on CI was the menu blur, not terrain
**Symptom:** On the 2-vCPU runner, the 1.21.11 client gametest timed out on world load. The log shows "Preparing spawn area: 16%" and then
"Synchronizing registries" with nothing after it for 2 minutes. 26.1 on the same runner passed in seconds. Passes locally.
**Cause (thread dumps from a throwaway CI run):** the server thread was parked in Fabric's `ThreadingImpl.enterPhase`. The client
gametest harness runs **one server tick per client frame**. The render thread was in `GameRenderer.processBlurEffect` (the menu
blur behind the loading screen), and two `llvmpipe` threads used about 170% of 2 CPUs. So the login crawled through configuration one frame at a time.
**Fix:** the test sets `client.options.menuBackgroundBlurriness().set(0)` before `worldBuilder().create()`.
**Dead ends:** a 24-bit Xvfb display (no change). Superflat (a previous session's uncommitted guess, never run): the stall is before
the player's chunks load, so terrain wasn't the cost. It's kept as `stash@{0}` in `../CTCL-1.21.11`.
**How to diagnose next time:** a background loop in the CI step that runs `jstack` (the target JVM's own) and `top -b -H -p <pid>`
into `build/reports/`, which the failure artifact uploads.
**Affects:** `src/gametest/.../CtclClientGameTest.java`

## 2026-09-28 — CI Mojang asset downloads can fail transiently
**Symptom:** A verify job failed in `:downloadAssets`: `Failed download after 3 attempts` from `resources.download.minecraft.net`.
The same job had passed on the previous run, with no code change.
**Fix:** `gh run rerun <id> --failed`. It's not a code problem. If it recurs often, cache `~/.gradle/caches/fabric-loom/assets`.
**Affects:** `.github/workflows/ci.yml` (any job that runs a client)

## 2026-09-29 — Loader stability on 26.x: which versions each loader actually has
**Symptom:** "Stable only" means something different on each loader, and the release lines don't line up with Fabric's.
**Cause / facts (checked 2026-09-29):**
- **Quilt Loader:** the newest stable is `0.30.1`. `0.30.2-beta.1` and `0.31.0-beta.*` are betas. 0.30.0 is the first
  version that treats post-25 Minecraft as unobfuscated. 0.30.1 runs 26.1–26.3, and Quilt meta lists all five 26.x
  versions as stable. Quilted Fabric API is dead, so Fabric API runs on Quilt directly.
- **NeoForge:** a `-beta` suffix marks unstable builds. The first stable build is `26.1.2.71` on 26.1.2 and `26.2.0.57`
  on 26.2. **26.1, 26.1.1, and 26.3 have beta builds only**, so NeoForge skips them. MDKs pin `26.1.2.111` / `26.2.0.88`.
  ModDevGradle `2.0.147`.
- **Forge:** builds never carry a suffix. The only stability signal is the promotions file
  (`files.minecraftforge.net/.../promotions_slim.json`). `-recommended` exists only for **26.1.2 (64.1.0) and
  26.2 (65.1.0)**. 26.1, 26.1.1, and 26.3 only have `-latest`, so Forge skips them (the owner's call: recommended = stable).
  ForgeGradle `[7.0.17,8)` in the MDK, newest `7.0.40`.
- So today Fabric and Quilt cover 26.1–26.3, and Forge and NeoForge cover 26.1.2–26.2.
**How to re-check:** `meta.quiltmc.org/v3/versions/loader`,
`maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge`, and Forge's `promotions_slim.json`.
**Affects:** `gradle.properties` pins, jar grouping (`decisions.md` 2026-09-29)

## 2026-09-29 — Stable Quilt rejects our jar because of the `fabricloader >=0.19.5` floor
**Symptom:** Quilt `0.30.1` provides `fabricloader` **0.19.3**. Our `fabric.mod.json` required `>=0.19.5`, so the v1 jar
won't load on stable Quilt.
**Cause:** the floor just followed the `loader_version` pin. Nothing needs 0.19.5: Fabric API needs `>=0.18.4` (26.1/26.2)
and `>=0.19.3` (26.3).
**Fix:** the floor goes to `>=0.19.3` (the owner's call). With the floor patched in, the **released** v1 jar passes all 14 server
gametests on Quilt 0.30.1 on 26.1 and on 26.3.
**Dead ends:** `-Dfabric-api.gametest.report-file=report.xml`. A relative path has no parent, and Fabric's
`SavingXmlTestReporter` NPEs in `createDirectories(null)` after the tests pass. Pass an absolute path.
**Affects:** `src/main/resources/fabric.mod.json`

## 2026-09-29 — One jar for all four loaders works, but Forge's Mixin rejects `JAVA_25`
**Symptom:** A spike jar with `fabric.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/mods.toml`, three entrypoints,
and one shared server mixin crashed Forge 65.1.0 at launch: `Mixin config ... specifies compatibility level JAVA_25 which
is not recognised`.
**Cause:** Forge ships **upstream Mixin 0.8.7**, which only knows levels up to `JAVA_21`. Fabric, Quilt, and NeoForge ship
Fabric's `sponge-mixin 0.17.3`, which knows `JAVA_25`. Our real `ctcl.mixins.json` and `ctcl.client.mixins.json` say
`JAVA_25`.
**Fix (verified on all four, 26.2 servers):**
- `"compatibilityLevel": "JAVA_21"` plus `"minVersion": "0.8"`. Forge's Mixin logs an ERROR-level line when `minVersion`
  is missing. Mixin classes compiled `--release 25` still apply fine at `JAVA_21`.
- `pack.mcmeta` in the jar. Forge warns `Missing metadata in pack mod:<id>` without one. `min_format` 84 /
  `max_format` 121 covers the resource (84–97.1) and data (101–121) formats of 26.1–26.3.
- NeoForge mixins go in `[[mixins]]` in `neoforge.mods.toml`, and Forge's go in the manifest's `MixinConfigs`. NeoForge
  also reads `MixinConfigs`, yet the config was applied **once**, with no duplicate warning.

With those changes, `-verbose:class` shows each loader loading only its own entrypoint plus the common class.
The mixin applies once on each, and our jar causes no warnings. Neither FML complains about the foreign
`fabric.mod.json` or toml, even though both FMLs have "this is a Fabric/Forge mod" detectors.
**Not yet proven:** client-side mixins and networking in the combined jar. Stages 13–14 prove them in the real jar.
**Dead ends:** grepping `-verbose:class` for `[class,load] ` misses lines. The JVM pads the tag (`[class,load  ]`) once
the log gets wide. Match `\[class,load *\]`.
**Affects:** `ctcl.mixins.json`, `ctcl.client.mixins.json`, jar resources, `neoforge.mods.toml`, `MANIFEST.MF`

## 2026-09-29 — NeoForge/Forge facts for the port (26.2 sources)
- **Vanilla guest on a NeoForge host:** allowed only if every payload is registered `optional()`
  (`NetworkRegistry.initializeOtherConnection`), and no extended enums are used in clientbound packets.
  `BuiltInRegistries.TICKET_TYPE` is **not** in NeoForge's synced set (`NeoForgeRegistriesSetup.VANILLA_SYNC_REGISTRIES`),
  so our ticket type doesn't block vanilla guests.
- **Vanilla guest on a Forge host:** `NetworkRegistry.acceptsVanillaClientConnections()` requires every channel to
  accept `VersionTest.Status.VANILLA` (`ChannelBuilder.optional()` does this) and no synced custom datapack registries.
- **NeoForge equivalents:** `RegisterPayloadHandlersEvent` (`PayloadRegistrar.optional()`), `ScreenEvent.Init.Post`,
  `LevelEvent.Load`, `ServerStartedEvent`/`ServerStoppingEvent`, `ServerTickEvent`, `RegisterGameTestsEvent`
  (registers vanilla `GameTestInstance`s, not an annotation scan). **NeoForge has no client gametest API.**
- **Forge:** has its own `net.minecraftforge.gametest.GameTest` annotation and ships Mixin + MixinExtras.
- Neither loader remaps on 26.x: everything is Mojang names at runtime, the same as Fabric.
**Affects:** Stages 13–14 in `TODO.md`

## 2026-09-29 — Loom multi-project: three traps in the Stage 11 split
- **Unit tests in a loader-free Loom project:** they failed with `FabricLoaderLauncherSessionListener could not be
  instantiated ... ASM not detected on the classpath`. Loom adds Loader's installer libraries (ASM, Mixin) only where
  `fabric-loader` is an `implementation` dependency, and `fabric-loader-junit` pulls the loader in transitively.
  Adding `testImplementation fabric-loader` did **not** fix it. The fix was `testRuntimeOnly sponge-mixin`, which brings ASM.
- **`splitEnvironmentSourceSets()` creates `client`.** A `sourceSets { client { ... } }` block placed before `loom { }`
  fails with "a SourceSet with that name already exists". Configure `client` after the `loom` block.
- **Parallel test JVMs:** with `org.gradle.parallel=true`, `:common:test` and `:fabric:runGameTest` ran together.
  The gametest server died after "All 14 required tests passed" with `os::commit_memory` (errno 1455), the same crash
  as the 2026-09-24 entry. `runGameTest` `mustRunAfter ':common:test'` restores the old ordering, when they were one project.
**Affects:** `common/build.gradle`, `fabric/build.gradle`

## 2026-09-29 — 26.3 client: a one-off native crash (0xC0000005) on the local NVIDIA GPU
**Symptom:** `prodClientGameTest -Pminecraft_version=26.3` exited with NTSTATUS `0xC0000005` during the first resource
reload, before any test code ran. There was no `hs_err_pid*.log`, so the fault was in a native thread (SDL, LWJGL
`3.4.3-snapshot`, or the driver). The recompiled 26.3 `runClientGameTest` passed minutes earlier on the same machine.
**Fix:** Rerunning the same command passed. It isn't mod code. If it recurs, capture `-XX:+CreateCoredumpOnCrash`
and the driver version before touching anything.
**Affects:** local 26.3 client runs (Windows, RTX 2080 Ti, driver 591.86)

## 2026-09-29 — Quilt harness traps (Stage 12)
- **Quilt ignores `fabric.addMods`.** It reads `loader.addMods` (`org.quiltmc.loader.impl.util.SystemProperties`).
  Loom's prod client passes only the Fabric property, so the Quilt task adds `-Dloader.addMods=<paths>` itself.
- **Quilt Loader's POM has no dependencies.** Its libraries are listed in `quilt-loader-<v>.json` (`libraries.common`
  + `.client`/`.server`). Resolve it as `org.quiltmc:quilt-loader:<v>@json`. It bundles MixinExtras, and its
  `quilt.mod.json` declares `provides: fabricloader <v>`, which `checkQuiltFloor` reads.
- **Counting what a classpath swap removed:** Quilt re-adds the same `asm-9.10.1` jars that Fabric Loader brought, so
  `before - after` misses them. Count the files the filter excluded instead.
- **Groovy `Map.values().unique()` throws `UnsupportedOperationException`** because it mutates in place. Use `.toList().unique()`.
**Affects:** `fabric/build.gradle`

## 2026-09-29 — Loom's production run tasks can be UP-TO-DATE, so a rerun silently skips the tests
**Symptom:** A second `prodServerGameTest` with unchanged inputs printed `UP-TO-DATE`. No server started, no gametests ran,
and the new restart-restore check never ran. The build still said SUCCESSFUL.
**Fix:** `outputs.upToDateWhen { false }` on every `ServerProductionRunTask`/`ClientProductionRunTask`. The Quilt server is
an `Exec` with no outputs, so it always runs. CI wasn't affected, because it runs each task once on a fresh runner.
**Affects:** `fabric/build.gradle`

## 2026-09-29 — NeoForge runs no gametests in a real install, and vanilla's gametest server deletes its universe
**Symptom:** The plan for NeoForge production gametests assumed that `RegisterGameTestsEvent` works like Fabric's
`-Dfabric-api.gametest`. It doesn't fire in a real install.
**Cause (NeoForge 26.2.x source, FML 11.0.16 bytecode):**
- `GameTestHooks.isGametestEnabled()` returns false whenever `FMLEnvironment.isProduction()`.
- FML's `detectProduction` looks for the bytes `net/neoforged/fml` inside `SharedConstants.class` on the classpath.
  Only the dev (ModDevGradle) game jar has NeoForge's patches compiled in, so every installer-made install is production.
- FML still ships `net.neoforged.fml.startup.GameTestServer` in production. It runs vanilla's `GameTestMainUtil`.
- `GameTestMainUtil` **deletes and recreates** `--universe` (default `gametestserver` under the cwd) on every run, and
  the world is `<universe>/gametestworld`. Its exit code is the number of failed required tests, or -1 on an early exit.
  `--report <abs path>` writes a JUnit-like XML that names every test, including passing ones. A passing run's log names none.
**Fix:** Register the tests as vanilla data: `TEST_FUNCTION` entries through `DeferredRegister`, plus `test_instance` JSON in
the gametest jar. The event isn't used, so dev and production run the same code. `--universe` is always passed as an absolute
path inside the task's run dir.
**Affects:** `neoforge/build.gradle`, `neoforge/src/gametest`, `common/src/gametest`

## 2026-09-29 — Fabric's "14 server gametests" are 13 CTCL cases plus vanilla's `minecraft:always_pass`
**Symptom:** Every record said 14 CTCL server gametests, but `common/src/gametest` has 13 cases (6 loader, 6 networking,
1 dimension). NeoForge also reported 14.
**Cause:** Vanilla's own data pack ships `data/minecraft/test_instance/always_pass.json` (`required: false`), and every
gametest server runs it. "All 14 required tests passed" counts it.
**Fix:** From Stage 13 on, NeoForge asserts exactly the 13 `ctcl_gametest:` ids from the report. Read older "14/14" notes as 13 + 1.
**Affects:** `TODO.md`, `state.md`, CI log checks

## 2026-09-29 — Skipping `clean` after a 26.3 sweep failed a 26.1 networking gametest
**Symptom:** `./gradlew build` on 26.1 failed `a_cycle_from_the_open_table_menu_advances_the_size` ("first click should
give 1") with thousands of `Recoverable errors when loading section ... Not a map`.
**Cause:** The known trap (2026-09-23 and 2026-09-24 entries): `fabric/build/run/gameTest/world` was last saved by a newer
version. 26.1 decodes those chunk sections as air, so `placeTable`'s clear hits air, no removal hook fires, and last run's
stored size survives.
**Fix:** `./gradlew clean build`, as `TESTING.md` says. NeoForge's dev run dir is per version (`run/gameTestServer/<mc>`),
and vanilla resets its universe anyway.
**Affects:** local runs only (CI starts clean)

## 2026-09-29 — A production NeoForge GameTestServer places the tests and never runs them
**Symptom:** `prodServerGameTest` in a real NeoForge install logged "14 tests are now running", and then only a blank
progress bar (`[              ]`) once a second. There were no errors, and the harness's 15-minute watchdog killed it.
**Cause:** NeoForge's `MinecraftServer` patch ticks `GameTestTicker.SINGLETON` only when `GameTestHooks.isGametestEnabled()`,
which is false in production. This is the second of exactly two gates: the other is `RegisterGameTestsEvent` in
`RegistryDataLoader`, and no NeoForge class uses the hook otherwise (checked in the 26.1.2 patched sources and the universal jar).
**Fix:** The gametest mod ticks the runner on `ServerTickEvent.Post`, only on a `GameTestServer`, and only while
`isGametestEnabled()` is false. It can never tick twice, even if NeoForge changes the gate.
**Dead ends:** Trusting a "tests are running" log line. The report check, plus a timeout on every server run, is what
turns a silent hang into a failure.
**Affects:** `neoforge/src/gametest/.../CtclNeoForgeGameTests.java`, `neoforge/build.gradle`

## 2026-09-29 — A test jar built on the base must use only API that every pin shares
**Symptom:** The NeoForge client smoke test passed on 26.1.2, but the same gametest jar crashed the 26.2 client before
the test ran: `NoSuchMethodError: Minecraft.getOverlay()`.
**Cause:** The harness runs the base-built gametest jar on every pin, as it does the release jar, but no byte check
covers test code. Between 26.1.2 and 26.2:
- `Minecraft.getOverlay()` went away;
- the public `Minecraft.screen` field moved to `minecraft.gui.screen()`;
- `WorldPresets.createFlatWorldDimensions` went away.
**Fix:**
- Track the shown screen through the loader's screen events (`ScreenEvent.Init.Post` / `Closing`), as the Fabric client
  test does.
- Use `isGameLoadFinished()`.
- Take the flat preset from the registry: `WORLD_PRESET` → `WorldPresets.FLAT` → `createWorldDimensions()`.
- Then compile the gametest sources on every pin (`:neoforge:compileGametestJava -Pminecraft_version=<pin>`; each pin's
  `build` does this too), **and** run the base-built jar on every pin. A compile alone can't catch a changed return type.
**Affects:** `common/src/gametestClient`, any future Forge client test

## 2026-09-29 — Multi-loader harness traps (Stage 13)
- **A bare task name runs in every project that has it.** `prodServerGameTest` and `checkReleaseJarBytes` exist in
  `fabric/` and `neoforge/`, so a bare name with a Fabric `-Pctcl_prod_mods` dir fails in `neoforge/`. Qualify the tasks
  (`:fabric:...`, `:neoforge:...`) and give each loader its own prod-mods dir. **CI (Stage 15.1) calls bare names today.**
- **`build` doesn't make `:neoforge:gametestJar`.** Only the prod tasks depend on it. Staging release jars after `build`
  needs `./gradlew :neoforge:gametestJar` first.
- **Windows' 260-character path limit:** `mclaunch.py`'s temp download name first repeated the library's file name, and the
  deepest libraries went over the limit under the session scratchpad. Temp names are now short. Keep install roots shallow
  (`neoforge/build/run/...`).
- **The game windows take real input.** A click and a closed screen during a local mutation run changed the failure
  reason. Don't touch the windows while client tests run.
- **NeoForge refuses a vanilla client** if any payload isn't `optional()`. The server logs `<name> ... lost connection:
  You are trying to connect to a server that is running NeoForge, but you are not`. `prodVanillaGuest` fails fast on that line.
**Affects:** `neoforge/build.gradle`, `tools/mclaunch.py`, `.github/workflows/ci.yml` (Stage 15)

## 2026-09-29 — A session ending kills its background sweep, and the log reads like a test failure
**Symptom:** `sweep-results.log` said `RESULT base exit 1`. The failing step was the second `:fabric:prodServerGameTest`,
with "Gradle build daemon disappeared unexpectedly" right after `14 tests are now running`.
**Cause:** The Claude session that started the sweep ended at that moment (18:57), and the harness killed its process
tree, including the Gradle daemon. No test failed.
**Fix:** Before trusting a red result from a run that outlived its session, compare the failure time with the session's
end. Look for "daemon disappeared" with no test output after it. Start a long sweep in the same session that will read it.
One folder also means `main` can't be committed while a band sweep runs in it; record afterwards.
**Affects:** long local runs (the sweep, about 64 min)

## 2026-09-29 — ForgeGradle 7 shares a build with Loom and ModDevGradle, but it doesn't apply `java`
**Symptom:** The first spike of a `forge/` project failed with `Could not find method implementation()` and
`Extension of type 'JavaPluginExtension' does not exist`.
**Cause:** FG7 (`net.minecraftforge.gradle` 7.0.40) doesn't apply the `java` plugin. The MDK applies it itself.
**Fix:** `plugins { id 'java'; id 'net.minecraftforge.gradle' }`. With that, FG7 `apply false` at the root, next to Loom
and ModDevGradle, compiles `:fabric`, `:neoforge`, and `:forge` in one build, under `-Xlint:all -Werror`.
**Cost:** Mavenizer's first run (`minecraft.mavenizer(it)`) decompiles, patches, and recompiles Minecraft into the Gradle
user home, about 5 minutes. After that it reuses the cache. CI (15.1) should cache it.
**Affects:** `forge/build.gradle`, root `build.gradle`, `.github/workflows/ci.yml`

## 2026-09-29 — Forge dev gametest traps (Stage 14.2–14.4), and a wrong fact in the Stage 14 plan
**Symptom:** The first `:forge:runGametestGameTestServer` runs failed four different ways before 13/13 passed.
**Cause / fixes, in the order they showed up:**
- **FG7 registers its run tasks after the build script.** `tasks.named("runGametestGameTestServer")` fails with "not found".
  Match it lazily (`tasks.matching { it.name == ... }.configureEach`). FG7 makes one task per source set:
  `runGameTestServer` (main only), `runGametestGameTestServer` (the one with both mods), `runTestGameTestServer`.
- **FML treats each output dir as its own mod file.** `build/resources/main` and `build/classes/java/main` each
  "constructed 0 mods". Fix: `net.minecraftforge.gradle.merge-source-sets=true` in `gradle.properties` (the MDK's
  setting). FG7 then points classes and resources at `build/sourceSets/<name>`. Only FG7 reads it; the jar's entries
  were unchanged.
- **Forge skips a mod's datapack without `pack.mcmeta`** ("Missing data pack mod:ctcl_gametest"), so no test instance
  loaded. The drift guard caught it (13 cases with no instance JSON). NeoForge didn't need one. Fix:
  `forge/src/gametest/resources/pack.mcmeta`.
- **`MixinConfigs` is a jar manifest attribute, and a dev run has no jar.** The mixins didn't apply
  (`ClassCastException ... SavedDataStorageAccessor`). Fix: run args `--mixin.config ctcl.mixins.json --mixin.config
  ctcl.client.mixins.json` (Mixin's ModLauncher option).
- **Wrong fact in the plan:** it said Forge ticks `GameTestTicker` with no production gate. Both 64.1.0 and 65.1.0 patch
  `MinecraftServer.tickChildren` to tick it only when `ForgeGameTestHooks.isGametestEnabled()`, which is
  `!production && (IS_RUNNING_IN_IDE || forge.gameTestServer || forge.enableGameTest)`. The dev run sets
  `-Dforge.gameTestServer=true`; the production run (14.5) must tick it itself, as NeoForge's did (13.5).
**Noise, not errors:** every configure prints Mavenizer's "Cache miss! Stacktrace for Information Only" block, and the
dev server warns about `.mcassetsroot` "unexpected schema" and corrects its config files.
**Blast radius correction:** Mavenizer also writes `<repo>/.gradle/mavenizer` (~100 MB), which `.gitignore` covers.
**Affects:** `forge/build.gradle`, `gradle.properties`, `forge/src/gametest/resources/pack.mcmeta`, TODO 14.5

## 2026-09-30 — Forge production harness traps (Stage 14.5)
- **Mavenizer's repo hides Forge's installer.** `installForgeServer` failed with "Could not find
  forge-26.1.2-64.1.0-installer.jar" and searched only `.gradle/mavenizer/repo`. Mavenizer serves
  `net.minecraftforge:forge:<mc>-<v>` (the compile jar), and Gradle looks for an artifact only in the repo where it found
  the module. Fix: the harness resolves through a named `prodInstaller` configuration, and `forge/build.gradle` sets
  `minecraft.mavenizer(it).content { notForConfigurations("prodInstaller") }`.
- **26.3 changed vanilla's gametest runner API.** `GameTestBatchFactory.divideIntoBatches` and
  `GameTestRunner.Builder.fromBatches` take a `MinecraftServer` instead of a `ServerLevel`, and `StructureGridSpawner`
  takes a `Function<ResourceKey<Level>, BlockPos>` instead of a `BlockPos`. Everything in `common/src/gametest` compiles
  on every Fabric pin, 26.3 included, so code that drives the runner lives in the loader that needs it.
- **`level-type=minecraft:flat` with an empty `generator-settings`** logs `ERROR [DedicatedServerProperties]: No key
  layers in MapLike[{}]`, then falls back to the classic flat preset. The harness spells the layers out.
- **A world Forge saved fails the uninstall check with `Missing data pack mod:ctcl`** (and `mod:forge`,
  `mod:ctcl_gametest`) on vanilla's first load. Forge gives each mod its own data pack, recorded in `level.dat`, and
  vanilla drops the missing ones, so the second load is clean. It's the same uninstall noise as Fabric's `fabric-*` and
  NeoForge's `mod_data`, so `tools/uninstall_check.py` now expects `Missing data pack mod:`. The ticket, `.dat`, and
  `loaders.dat` checks all passed.
- **Git Bash `sed -i` rewrites a CRLF file with LF endings.** Git warns "LF will be replaced by CRLF". Edit tracked
  files with Python in binary mode (or the editor tools), and check `file <path>` afterwards.
- **Noise, not errors:** every Forge server boot on Windows logs `An exception occurred processing Appender DebugFile`,
  with netty `kqueue`/`epoll` `NoClassDefFoundError` stacks (the native-transport probe).
**Affects:** `gradle/harness.gradle`, `forge/build.gradle`, `forge/src/gametest`, `tools/uninstall_check.py`

## 2026-09-30 — Forge client harness traps (Stage 14.7)
**Context:** 14.7 ran `installForgeClient`, `prodClientSmoke`, and `prodVanillaGuest` for the first time.
**Trap 1 — no vanilla JSON:** Forge's `--installClient` writes `versions/<mc>/<mc>.jar` and
`versions/<mc>-forge-<v>/<...>.json` (`inheritsFrom: <mc>`), but **not** `versions/<mc>/<mc>.json`. NeoForge's installer
writes both, so mclaunch never needed it. A real launcher gets that JSON from Mojang's manifest, so `tools/mclaunch.py`
now does the same: a missing version JSON is looked up in `version_manifest_v2.json` and fetched through `ensure_file`
(sha1-checked). An id the manifest doesn't list (a loader version never installed) still fails with "install first".
Tests: `VanillaJsonTest` plus the manifest-backed missing-version test; a skipped sha1 fails exactly one of them.
**Trap 2 — a nameless refusal:** without `optional()`, Forge refuses a vanilla client in the handshake, before it has a
name: `Channels [ctcl:main] rejected vanilla connections`, then `Disconnecting VANILLA connection attempt: ...`. The
guest check only looked for a line naming the guest, so the mutant failed on the 10-minute timeout, not the refusal.
The harness spec takes `guestRefusal` (a regex), and Forge passes `/Disconnecting VANILLA connection attempt/`.
**Affects:** tools/mclaunch.py, tools/test_mclaunch.py, gradle/harness.gradle, forge/build.gradle

## 2026-09-30 — Stage 14.8 traps
- **Gradle called `releaseJars` up to date with a stranger jar in its output dir.** A file added to a declared
  `outputs.dir` doesn't make the task out of date, so the "no foreign jars" check never ran. The task now has
  `outputs.upToDateWhen { false }`. The merge takes under a second.
- **A Forge prod run died mid-batch, with no failure line.** The log stopped at `CTCL production gametests:
  [++_++++++++++]`, and Gradle only saw a non-zero exit. `hs_err_pid*.log` in the run dir said `Native memory
  allocation (mmap) failed ... G1 virtual space`, with `AvailPageFile 1922M` of 49 GB. It's the commit limit from
  2026-09-24 again: seven `java` processes were alive, including Gradle daemons from runs with different `-P` sets.
  The next two runs passed. **Tell:** a log that just stops means look for `hs_err_pid*.log` in the run dir first.
- **The `-release` run dirs keep the jars of an earlier stage.** `stageMods` refuses to add
  `...-Forge-NeoForge.jar` next to the 14.7 `...-Forge.jar` (the guard working). Move the old jar out of `mods/`;
  don't `:forge:clean`, which throws away the installs.
- **My Python edits read in text mode**, so a `'\r\n' in s` check is always false and the files came back LF. Git's
  content was unaffected (the index is LF), but read with `open(p, 'rb')` when line endings matter.
**Affects:** `build.gradle`, `gradle/harness.gradle`, the 14.9 sweep

## 2026-09-30 — VS Code's Java and Gradle extensions lock build jars and kill sweeps
**Symptom:** The first 14.9 run died 15 s into `build` with "Gradle build daemon disappeared". The rerun failed at
`:neoforge:clean` with `Unable to delete ... neoforge/build/moddev/artifacts/minecraft-patched-26.1.2.112.jar`, after
"Waiting for lock to be released" from a Gradle process whose parent was `Code.exe`.
**Cause:** A VS Code window reload ends the Claude session (which kills its sweep), and on reload `vscode-gradle`
(its `gradle-server` plus a daemon) and the Java extension's standard-mode servers import the Gradle project. That
import holds the Gradle lock and opens classpath jars, so Windows can't delete them. The local `.vscode/settings.json`
also gives jdtls `-Xmx8G`. With extra windows open, those processes held about 12 GB of commit.
**Fix:** Local, gitignored `.vscode/settings.json` (the owner, 2026-09-30): `java.server.launchMode: LightWeight`,
`java.gradle.buildServer.enabled: off`, `gradle.autoDetect: off`. They take effect after a reload. Check before a
sweep: only a syntax server, and no Gradle daemon parented by VS Code. Never reload the window while a sweep runs.
**Rejected:** skipping `:neoforge:clean`, which dodges the lock but leaves the extensions eating memory.
**Affects:** local sweeps; `.vscode/settings.json` (untracked)

## 2026-09-30 — The 26.3 client native crash recurred, and extensions still sync once on window open
- **The 26.3 crash is intermittent, not one-off.** In the 14.9 sweep, Quilt 26.3's `prodClientGameTest` exited `0xC0000005`
  during the first resource reload, with no `hs_err` and the same driver (591.86). A single rerun of that task passed in
  48 s. That's twice in two sweeps, both on 26.3 and both before mod code runs. **Rerun it once. If it fails twice in a
  row, capture `-XX:+CreateCoredumpOnCrash`** (`gotchas.md` 2026-09-29). CI (Mesa on xvfb) has never shown it.
- **With `gradle.autoDetect: off`, `vscode-gradle` still starts its server and configures the project once when the
  window opens** (about 45 s, no tasks). Before launching a sweep after a reload, wait for that daemon's
  `Command execution: completed` in `~/.gradle/daemon/<v>/daemon-<pid>.out.log`. After that it stays idle, and the full
  sweep ran with no lock.
**Affects:** local sweeps, 26.3 clients

## 2026-09-30 — `-Pctcl_prod_mods=<relative dir>` resolves per project, so CI's `release-jars` finds no jars
**Symptom (caught in review, never run):** the harness reads the dir with `fileTree(prodModsDir)` in each loader project
(`fabric/build.gradle`, `gradle/harness.gradle`). A relative path resolves against that project's dir, so CI's
`-Pctcl_prod_mods=release-jars` means `fabric/release-jars` in `:fabric` and `neoforge/release-jars` in `:neoforge`. Every
verify job would fail with "needs exactly one release jar ... found []". The old single-project CI was fine, because
there the project dir was the root. The 14.9 sweep passed an absolute path, so it never showed this.
**Fix:** CI passes `PROD_MODS: ${{ github.workspace }}/release-jars`. `tools/test_ci_workflow.py` pins it.
`-Pctcl_world` goes through `rootProject.file()`, so it may stay relative.
**Affects:** `.github/workflows/ci.yml`, anything that passes `-Pctcl_prod_mods`

## 2026-09-30 — `print("\n".join([]))` prints one empty line, so `mapfile` sees one jar
**Symptom (caught by the staging fixture):** the "plan listed no release jars" guard never fired on an empty plan.
`mapfile -t` read the lone newline as one empty element, and the script went on to `cp build/release/`.
**Fix:** `sys.stdout.writelines(j + "\n" for j in ...)`, which writes nothing for an empty list.
**Also:** locally, `python3` in Git Bash is the Microsoft Store stub, and Windows Python writes `\r\n`, which breaks
`mapfile` comparisons. To run a CI bash step on Windows, put a `python3` shim on PATH (`python "$@" | tr -d '\r'`).
**Affects:** `.github/workflows/ci.yml` "Stage exactly the planned jars"

## 2026-09-30 — Claude Code's Bash tool can reject a long quoted heredoc
**Symptom:** `python - <<'EOF' ... EOF` with ~90 lines of Python (triple-quoted strings, backticks, `'`) failed with
`unexpected EOF while looking for matching ''`, before Python ran. A quoted heredoc should pass its body through
untouched, so the tool's own wrapping is the likely cause. Nothing was written.
**Fix:** write the script to the scratchpad with the Write tool, then `python <path>`. Short heredocs still work.
**Affects:** any multi-line edit script run through the Bash tool

## 2026-09-30 — A fresh split-band checkout has no root build/ until releaseJars
**Symptom:** CI's first multi-project run (`36793729578`, `96b8780`) failed at "Uninstall check": `work.mkdir()` raised
`FileNotFoundError: .../build/uninstall-check`. Nothing was released, because `release` needs every job green.
**Cause:** after the split, Gradle writes only `common/build`, `fabric/build`, and so on. The root `build/` appears when
`releaseJars` runs, which comes later in the job. Every local run had a stale root `build/`, so the 14.9 sweep never hit this.
**Fix:** `fresh_work_dir()` keeps the guard and calls `mkdir(parents=True)` (`c9fbb20` on `band/26.1`).
`tools/test_uninstall_check.py` runs it against a missing `build/` dir.
**Lesson:** a local green sweep doesn't prove a fresh-checkout run. Anything that writes under the root `build/` must create it.
**Affects:** `tools/uninstall_check.py` (band only)

## 2026-10-01 — Forge 26.1.2 restart hung once in vanilla's shutdown loop on CI
**Symptom:** run `36794641676`, `verify (forge 26.1.2)`. The restart server (5 loaders restored, `stop` typed at `Done`) logged
"Saving worlds" and nothing after, until the harness's 15-minute kill. The first server in the same task stopped cleanly.
**Where:** vanilla `MinecraftServer.stopServer`: `while (levels.anyMatch(chunkMap.hasWork())) { deactivateTicketsOnClosing(); tick(); }`
(read in ForgeGradle's `injected-sources.jar` under `~/.gradle/caches/minecraftforge/forgegradle/mavenizer/caches/forge/`).
**Rate:** 1 hang in 7 CI restarts, 0 in 20 local restarts (2-core pinning made no difference).
**Clue:** in the hung run, the post-`Done` save took about 5.5 s with Worker-Main still generating chunks, against about 1 s in passing runs.
**Data next time:** the harness now writes `<console>-threads.txt` (2 `jstack -l` dumps) before any timeout kill (`b37e23b`).
**Safe diagnosis on CI:** a throwaway `ci/*` branch with its own workflow file. `ci.yml` triggers on `band/**` only, so it can't
plan or release. Any `push`/`workflow_dispatch` run on a band that goes green releases.
**Affects:** `gradle/harness.gradle`, the Forge restart in `forge/build.gradle`

## 2026-10-01 — Stage 16.1 spike: Forge and NeoForge compile on 26.1, 26.1.1, and 26.3 with no code changes
**Result:** with the six pins added, `:forge:compileJava :neoforge:compileJava` passes on the 26.1 base (the release-jar
build) and with `-Pminecraft_version=26.1.1` and `=26.3`. There were no API differences against 26.1.2/26.2 at compile time.
**Mixin:** every Forge pin from 62.0.9 to 66.0.9 ships `org.spongepowered:mixin:0.8.7` (userdev `config.json`), so `JAVA_21`
behaves the same everywhere. MixinExtras goes 0.5.3 (62, 63) → 0.5.4, but CTCL doesn't use MixinExtras.
**Confirmed bug, as 16.0 predicted:** the base build declares `forge [62.0.9,63),[65.1.0,66),[66.0.9,67)`, so the 26.1 line
rejects Forge 63 (26.1.1) and 64 (26.1.2). 16.2's per-version ranges fix this.
**Trap for 16.2/16.3:** `versionParts` in `gradle/pins.gradle` does `as int` on each dot part, so it throws on
`26.1.0.19-beta`. Today nothing parses a NeoForge pin, and Forge pins carry no suffix. Any new code that compares
loader pins needs a suffix-aware parse.
**Not proven yet:** compiling isn't binary compatibility. A jar compiled on 26.1 + Forge 62 that runs on 64–66 (and NeoForge
26.1 beta → 26.3) is proven only by the 16.4 runtime sweep.
**Decision (answer 28):** one combined jar, unless 16.4 shows a runtime break. No split by range.
**Affects:** `gradle.properties`, `gradle/pins.gradle` (band/26.1)

## 2026-10-01 — Forge's merged source sets: a task can't declare a processResources file as an input
**Context:** 16.2's `checkLoaderRange` declared `inputs.file(<processResources dir>/META-INF/mods.toml)`.
**Symptom:** `:forge:compileJava` failed only in a parallel multi-project build ("Task ':forge:checkLoaderRange' uses this
output of task ':forge:compileJava' without declaring an explicit or implicit dependency"). Run on its own, it passed, so
the failure looked flaky. It wasn't: `net.minecraftforge.gradle.merge-source-sets=true` puts processResources' output in
compileJava's dir (`forge/build/sourceSets/main`), so Gradle treats any file there as compileJava's output too.
**Fix:** declare no inputs or outputs. A cheap check task then just always runs. Or depend on `classes`, not only `processResources`.
**Affects:** `gradle/pins.gradle` `registerLoaderRangeCheck` (band/26.1)

## 2026-10-01 — Loader APIs: Forge's server 403s Python's default User-Agent; NeoForge "stable" isn't just "no -beta"
**Symptom:** `urllib.request.urlopen(promotions_slim.json)` got **HTTP 403** from `files.minecraftforge.net`, while curl got 200.
NeoForge's 26.1.0 version list also holds `26.1.0.0-alpha.N+snapshot-M` builds, which have no `-beta` suffix.
**Fix:** send any explicit `User-Agent` (`check_pins.py` sends `ctcl-check-pins`). Treat a NeoForge build as stable only
when it has **no `-` suffix at all**. Note the 403 is retried like any other failure, then fails closed.
**Affects:** `tools/check_pins.py` (band/26.1)

## 2026-10-01 — 16.4: the base moved to 26.1, and Forge's test code still assumed 26.1.2 and 26.2
Both bugs were in test-only code, so the shipped jar was never wrong. Both would have failed CI's Forge jobs.
- **The gametest mod's floor.** `forge/src/gametest/.../mods.toml` hardcoded `loaderVersion = "[64,)"`, so Forge 62 (26.1)
  and 63 (26.1.1) refused the base-built gametest jar ("needs language provider javafml:64"). 16.2's `checkLoaderRange` missed
  it: it checked only the `[[dependencies.ctcl]]` ranges, not `loaderVersion`, and only the mod's own toml.
  **Fix (`10e3d45`):** the gametest toml expands the same `fml_range`, and `checkLoaderRange` checks Forge's `loaderVersion` in both
  tomls against every pin's major. It named 62 and 63 before the fix.
- **26.3's gametest runner API.** `divideIntoBatches` and `GameTestRunner.Builder.fromBatches` take a `MinecraftServer` instead of a
  `ServerLevel`, and `StructureGridSpawner` takes a `Function<ResourceKey<Level>, BlockPos>` instead of a `BlockPos`. There is no shared
  form. **Fix (`1363ee1`):** `ProdGameTests` picks each overload at runtime by parameter type and fails closed. The proof is the
  base-built jar passing Forge prod gametests on 26.1 and 26.3.
**Lesson:** the 16.1 spike compiled only `compileJava`. A spike for a new pin must compile **every** source set
(`compileGametestJava` too) on every pin, and grep test code for the old floor and for comments like "builds only X and Y".
**Affects:** `forge/src/gametest/`, `forge/build.gradle`, `gradle/pins.gradle` (band/26.1)

## 2026-10-01 — A fresh 26.3 server defaults to `white-list=true`
**Symptom:** the NeoForge 26.3 `prodVanillaGuest` failed with "You are not white-listed on this server!". The CTCL client smoke test
on the same pin passed.
**Cause:** vanilla. A new 26.3 `server.properties` gets `white-list=true` (and an empty `whitelist.json`) on Fabric, NeoForge, and
Forge alike. Up to 26.2 the default was `false`. The harness wrote only `online-mode`, the port, and the motd.
**Fix (`5b04a79`):** the guest check writes `white-list=false`. The gametest servers have no joining players, so they don't need it.
**Users:** anyone starting a fresh 26.3 dedicated server has to whitelist their friends or turn it off. 16.5 puts this in the docs.
**Affects:** `gradle/harness.gradle` `prodVanillaGuest`; `README.md`/`TESTING.md` (16.5)

## 2026-10-01 — Root `clean` doesn't touch `build/release`
**Symptom:** after `./gradlew clean`, `releaseJars` refused: `build/release` held the local v1.1.0 jars. Its own guard caught this.
**Cause:** the root project registers no `clean`, so `clean` runs only in the subprojects. CI starts from a fresh checkout, so it
never sees this.
**Fix:** none in the build. Before a local sweep, move old jars out of `build/release` (16.4 moved them to the scratchpad and
checked their sha1).
**Affects:** local sweeps

## 2026-10-01 — The 26.3 client native crash, a third time, as `0xC0000409`
In 16.4, Fabric 26.3's `prodClientGameTest` died during the first resource reload, with no `hs_err`, and this time the code was
`0xC0000409` (fail-fast) instead of `0xC0000005`. The one allowed rerun passed in 47 s. That makes three in three local sweeps, all on 26.3,
all before mod code runs. Quilt's 26.3 client passed twice in the same sweep. The rule stands: rerun once, and a second failure in a row
gets `-XX:+CreateCoredumpOnCrash`.
**Affects:** local sweeps, 26.3 clients

## 2026-10-01 — Two CI flakes on the 16.6 push: a silent Fabric 26.2 dev client, and a Mojang download reset
**Run:** 36851633937 (`8391a44`), 15 of 17 jobs green. Nothing Fabric reads changed since the green `b37e23b` run.
- `verify (fabric 26.2)`, step "Full suite": `:fabric:runClientGameTest` loaded the world (`World loading finished: ClientLevel
  screen: null`, the X11 cursor GL error, then a chunk UBO resize), and then logged **nothing** until the step's 30-minute
  `timeout-minutes`. There was no exception and no exit. Fabric 26.2's dev client has never hung before, on CI or locally.
- `verify (forge 26.1.1)`, step "World move": Forge's restore passed, and then the Fabric server launcher in the second
  `:fabric:prodServerGameTest` died with `Failed to setup fabric server` / `SocketException: Connection reset` during
  "Downloading Minecraft server". Each world-move run dir is new (`<mc>-import-<stamp>`), so the launcher downloads the
  server jar again, and it has no retry.
**Choice (owner answer 41):** `gh run rerun --failed` once. If either fails again, stop. The fixes, if it recurs: a thread-dump
watchdog around the dev client gametest (as `b37e23b` did for servers), and a shared server-jar cache or retry for the
Fabric launcher.
**Affects:** `.github/workflows/ci.yml` verify job, `fabric/build.gradle` prod runs

## 2026-10-01 — Forge's early window times out on Xvfb, and a non-daemon prompt thread keeps the dead client alive
**Run:** 36851633937 attempt 2 (`8391a44`). Fabric 26.2 passed on the rerun (7 min). `verify (forge 26.1.1)` failed in a new place:
`prodClientSmoke` "didn't exit within 15 minutes". Attempt 1 and the 16.4 sweep passed the same step on the same pin.
**Cause (read from Forge 63.0.2's `fmlearlydisplay` bytecode):** `DisplayWindow.initWindow` gives `glfwCreateWindow` **10 seconds**
(`schedule(..., 10, SECONDS)`). A cold Mesa GL init on Xvfb overran it ("Timed out trying to setup the Game Window", 14 s after
launch). `crashElegantly` then submits `tinyfd_messageBox("yesno")` + `System.exit` to `Executors.newSingleThreadExecutor()` (a
**non-daemon** thread) and returns, and `initWindow` throws `IllegalStateException: We died but didn't somehow?` on main. Main
died at once. With no dialog tool on the runner, tinyfd falls back to a console `y/n:` prompt and blocks that thread, so the JVM
never exits, and the watchdog kills it 15 minutes later.
**Wrong first read (corrected the same day):** "stdin blocks the main thread". Main didn't block. It threw 0.3 s after the
prompt. Closing stdin isn't a reliable fix: tinyfd's console loop may spin on EOF instead of exiting.
**Switch:** `config/fml.toml` `earlyWindowControl = false` (`FMLConfig.EARLY_WINDOW_CONTROL`) makes `ImmediateWindowHandler` skip the
early window, and vanilla then creates the window itself, with no 10 s limit.
**Second bug:** the watchdog's `<console>-threads.txt` never reaches the CI artifact. The verify upload globs are `**/logs/`
and `**/*.log`, and `*-threads.txt` matches neither. So the advice "read `<console>-threads.txt` in the failure artifact"
(the Forge 26.1.2 restart hang entry) can't work on CI until the glob is added.
**Affects:** `gradle/harness.gradle` (`prodClientSmoke`), `.github/workflows/ci.yml` (verify artifact paths)

## 2026-10-01 — A toolchain provider's `.get()` throws off Gradle's build thread
**Symptom:** the first `checkClientDeathKill` run set `died` but never killed the JVM, and the watchdog killed it at 2 min. The
console reader thread had died with `IllegalStateException: No current build operation` in
`DefaultJavaToolchainService.emitEvent`, from `jstackPath.get()` inside `dumpThreads`.
**Cause:** resolving a `javaToolchains.launcherFor` provider emits a build-operation progress event, which needs the current
build operation. A plain `Thread.start` (the harness's console reader) has none.
**Fix:** resolve the `File` on the task thread (`startClient` does `jstackPath.get()` before it starts the process) and pass
it in. `dumpThreads` takes an optional `jstackFile`. A failed dump is logged, and the kill still happens.
**Lesson:** never touch a Provider from an `onLine` callback. Resolve everything before `startProcess`.
**Affects:** `gradle/harness.gradle`

## 2026-10-01 — Forge's `y/n: ` prompt has no newline, so the main-thread death is mid-line
**Symptom:** CI run 36914711767 (`3d18637`), `verify (forge 26.2)`: the early window timed out again, and `startClient` never
killed the client. The watchdog killed it at 15 min. 16 of 17 jobs were green.
**Cause:** the console line is `y/n: Exception in thread "main" java.lang.reflect.InvocationTargetException`, and the matcher
used `startsWith`. The same line was in attempt 2's console (26.1.1), and I misread it as two lines.
**Fix:** `contains`, and the self-test's dead JVM prints `y/n: ` with no newline before it throws (red on `startsWith`).
**Also:** on Linux, a process killed from the reader's own `onLine` closes its stdout under `eachLine` (`IOException: Stream
closed`). `startProcess` logs that when the process is dead, and rethrows it otherwise.
**Rate:** Forge's 10 s early-window timeout hit 2 of the last 2 runs (26.1.1 at 63.0.2, then 26.2 at 65.1.0), each time on a
different pin. If the one retry also times out, the next step is `earlyWindowControl = false` on CI. That needs the owner:
answer 43 kept the early window on.
**Affects:** `gradle/harness.gradle`

## 2026-10-22 — Exporting a tree on this machine: CRLF, MAX_PATH, and silent partial trees
**Symptom:** Staging the public repo from `git archive`, nearly every blob differed from the original, `git add`
failed on one gametest JSON, and a Python scan saw 139 of 144 files.
**Cause:** (1) The system gitconfig sets `core.autocrlf=true`, so `git archive` wrote CRLF and a repo with
`autocrlf=false` committed it verbatim. (2) The scratchpad path plus the longest gametest filename passes 260 chars:
`git add` needs `core.longpaths=true`, and Python's `rglob`/`is_file()` silently skips such files. (3) A helper that
chained with `;` kept going after `git add` failed and wrote a partial tree.
**Fix:** `git -c core.autocrlf=false archive`; `core.longpaths=true`; `set -euo pipefail` and an indexed-vs-on-disk
file count; scan blobs with `git ls-tree`/`git show`, not the filesystem. Prove a rebuild by comparing blob hashes
with the original (only intended files may differ).
**Dead ends:** `grep -c $''` in Git Bash reports 0 on CRLF files; use `od -c`.
**Affects:** any export, staging, or scan of this repo's trees
