# Crafting Table Chunk Loader (CTCL)

A Fabric, Quilt, NeoForge, and Forge mod that turns every vanilla crafting table into a chunk loader. Open a crafting
table: a small button in the top-right of the GUI shows the size of the square of chunks
that table keeps loaded. Click it to cycle.

| Button | Area | Chunks fully ticking |
|---|---|---|
| `0` | off (every table starts here) | 0 |
| `1` | 1×1 | 1 |
| `3` | 3×3 | 9 |
| `5` | 5×5 | 25 |
| `7` | 7×7 | 49 |
| `9` | 9×9 | 81 |

The square is centred on the chunk containing the table. There is no limit on how many
tables you run.

As with `/forceload`, vanilla also keeps two rings of chunks just outside the square
loaded, but these don't fully tick. So a `9` holds 13×13 = 169 chunks in memory, of which
81 fully tick.

## Requirements

Java 25, and one jar from the repository's GitHub Releases: `ctcl-<version>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`.
It runs on every loader and Minecraft version below.

| Minecraft | Fabric | Quilt | NeoForge | Forge |
|---|---|---|---|---|
| 26.1 | ≥ 0.19.3 | ≥ 0.30.1 | ≥ 26.1.0.19-beta (beta) | ≥ 62.0.9 (beta) |
| 26.1.1 | ≥ 0.19.3 | ≥ 0.30.1 | ≥ 26.1.1.15-beta (beta) | ≥ 63.0.2 (beta) |
| 26.1.2 | ≥ 0.19.3 | ≥ 0.30.1 | ≥ 26.1.2.112 | ≥ 64.1.0 |
| 26.2 | ≥ 0.19.3 | ≥ 0.30.1 | ≥ 26.2.0.88 | ≥ 65.1.0 |
| 26.3 | ≥ 0.19.3 | ≥ 0.30.1 | ≥ 26.3.0.39-beta (beta) | ≥ 66.0.9 (beta) |

- **Fabric and Quilt also need Fabric API.** NeoForge and Forge need nothing else.
- **(beta)** means that loader has no stable build for that Minecraft version yet (for Forge, no *recommended*
  build), so CTCL was tested on the beta build shown. Newer builds for the same Minecraft version work too. Each
  release's notes name the beta builds it was tested on.
- Built for **singleplayer and Open to LAN**. The automated tests run it on a dedicated server of every loader,
  but playing on a dedicated server hasn't been tested by hand.
- **A new 26.3 dedicated server turns its whitelist on.** It writes `white-list=true` to `server.properties`, so
  nobody can join until you add them with `/whitelist add <name>` or set it to `false`. That's vanilla 26.3, not CTCL.

## How it behaves

- **Loaded chunks fully tick.** Crops grow, furnaces smelt, and redstone and mobs already
  there keep running, just like `/forceload`.
- **Natural mob spawning still needs a player within 128 blocks.** That's vanilla's rule,
  and CTCL doesn't change it.
- **Pausing singleplayer stops everything**, loaded chunks included. Once the world is
  **opened to LAN** the game never pauses, so loaders keep running while you sit in a menu.
- **Loaders only run while the world is open.** Nothing runs after you quit to the title
  screen.
- **The setting belongs to the spot, not the block.** Breaking a table, or pushing it with
  a piston, turns its loader off. A table placed or pushed into a new spot starts at `0`.
  - If that table was the only thing keeping its own chunk loaded, a piston push stays
    frozen mid-move until a player comes near. The chunk stopped ticking the moment the
    table left.
- **Several tables can overlap.** Turning one off never unloads chunks another table
  still covers.
- **Anyone who can open the table can change it**, including LAN guests who have the mod.
- **LAN guests without the mod can still join.** They see normal crafting tables with no
  button. Guests with the mod get a working button, and everyone looking at the same
  table sees the same number.
- **Guests run the host's loader with CTCL, or no mods at all.** A guest with CTCL on a different loader (a Fabric
  client joining a NeoForge host) isn't supported.

## Removing the mod is safe

CTCL adds no blocks, items, or block entities, and never writes into chunk or block data.

- Its chunk tickets are never saved, so nothing of CTCL's ends up in vanilla's
  `chunk_tickets.dat`.
- The only file it writes is `data/ctcl/loaders.dat` in each dimension's folder
  (`dimensions/minecraft/overworld/`, and so on). Vanilla ignores it.
- Reinstalling the mod picks your sizes back up from that file.
- `tools/uninstall_check.py` proves all of this by loading a CTCL world, saved by each loader, in the vanilla
  server (see `TESTING.md`).

If `loaders.dat` is ever unreadable, CTCL copies it to
`loaders.dat.corrupt-<timestamp>` before the game can overwrite it. It then logs an
error and starts that dimension with every table at `0`. To recover, close the world and
restore the copy.

## Upgrading a world

**Between 26.x versions** (26.1 → 26.2 → 26.3), every table keeps its size. Swap in the
CTCL jar for the new version when you upgrade Minecraft.

**Between loaders**, every table keeps its size too: every loader reads the same `loaders.dat`. The tests move a
world from Fabric to NeoForge and back, and from Fabric to Forge and back. Quilt runs the Fabric jar itself.

**From 1.21.11 to any 26.x version, every table starts at `0` again.** Click each one back
to the size you want. CTCL for 1.21.11 kept sizes in `data/ctcl_loaders.dat`. The 26.x
versions never read, move, or delete that file, so it's harmless and you can leave it.

## Building

```sh
./gradlew build                      # every loader: compile, unit tests, and the headless server gametests
./gradlew :fabric:runClientGameTest  # the client UI test on Fabric (opens a game window)
./gradlew :fabric:runClient          # play in a dev client (Fabric)
./gradlew releaseJars                # the release jar, into build/release/
```

The code is split into `common/`, which is loader-neutral and has no loader imports, and one project per loader
family: `fabric/` (Fabric and Quilt), `neoforge/`, and `forge/`. Each holds its loader's entrypoints and compiles
`common/` into its own jar. `releaseJars` merges the Fabric, NeoForge, and Forge jars into one, and fails if a shared
entry differs between them.
On the band's base, 26.1, `releaseJars` writes `ctcl-<mod>+26.1-26.3-Fabric-Quilt-Forge-NeoForge.jar`, which
declares every version in its range. `-Pminecraft_version=26.2` (or any other pinned version) builds jars for that
version alone, which the tests use. Run `./gradlew clean` when switching versions. `TESTING.md` lists every test task. CI builds, proves, and
releases the jar on every push (`BANDS.md`, **CI and releases**). Supported versions and the
branch for each are listed in `BANDS.md`. Mod code lives on the `band/*` branches; `main` holds only docs and tooling.

License: [MIT](LICENSE).
