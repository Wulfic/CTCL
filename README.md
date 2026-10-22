# Crafting Table Chunk Loader (CTCL)

A Fabric mod that turns every vanilla crafting table into a chunk loader. Open a crafting
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

- Minecraft **1.21.11**, Fabric Loader ≥ 0.19.5, Fabric API.
- Built for **singleplayer and Open to LAN**. A dedicated server will very likely work but
  isn't tested.

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

## Removing the mod is safe

CTCL adds no blocks, items, or block entities, and never writes into chunk or block data.

- Its chunk tickets are never saved, so nothing of CTCL's ends up in `chunks.dat`.
- The only file it writes is `data/ctcl_loaders.dat` in each dimension. Vanilla ignores it.
- Reinstalling the mod picks your sizes back up from that file.
- `tools/uninstall_check.py` proves all of this by loading a CTCL world in the vanilla
  server (see `TESTING.md`).

If `ctcl_loaders.dat` is ever unreadable, CTCL copies it to
`ctcl_loaders.dat.corrupt-<timestamp>` before the game can overwrite it. It then logs an
error and starts that dimension with every table at `0`. To recover, close the world and
restore the copy.

## Building

```sh
./gradlew build              # compile, unit tests, and the headless server gametests
./gradlew runClientGameTest  # the client UI test (opens a game window)
./gradlew runClient          # play in a dev client
```

The jar is written to `build/libs/`. Supported versions and the branch for each are listed
in `BANDS.md`. Mod code lives on the `band/*` branches; `main` holds only docs and tooling.

License: [MIT](LICENSE).
