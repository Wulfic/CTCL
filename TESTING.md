# Testing

## Automated: run these before every commit on a band

| Command | What it proves | Time |
|---|---|---|
| `./gradlew build` | Compiles with `-Werror`, runs the unit tests (JUnit 5 + Mockito, under Fabric's classloader), and runs **all server gametests** headless | ~3 min |
| `./gradlew runClientGameTest` | Real client: open a table, click through `1,3,5,7,9,0` with the mouse, toggle the recipe book, click again, check the server's value and the layout. **Opens a window.** It passes silently and fails with `AssertionError`. | ~2 min |
| `python tools/uninstall_check.py` | Loads the gametest world, which has loaders in all three dimensions, in the **vanilla** server twice. There must be no unexpected errors, no CTCL ticket in `chunks.dat`, and `ctcl_loaders.dat` must be untouched. Run it after `build`. | ~1 min |
| `python tools/uninstall_check.py --self-test` | The script's delete guard refuses every path except `build/uninstall-check` | instant |

The server gametest world (`build/run/gameTest/world`) **persists between runs**. Every
test clears its own area first. `./gradlew clean` resets it.

## Manual: LAN with a second client

The automated suites can't run two game clients against one host. Do this once per
release, and again whenever the networking or button code changes.

Setup: build the jar and install it plus Fabric API on two machines (A = host, B =
guest). A also needs a way to test a vanilla guest: a third client, or B with the mod removed.

- [ ] **A:** create a singleplayer world, place a crafting table, open it. The button reads `0`.
- [ ] **A:** click to `5`. Open to LAN.
- [ ] **B (with the mod):** join and open the same table. It reads `5`.
- [ ] **A and B:** both keep the table open. B clicks. A's button updates to match without
      reopening.
- [ ] **A:** walk more than 300 blocks away. A furnace smelting inside the 5×5 area keeps
      smelting (check its output when you return).
- [ ] **Vanilla guest (no CTCL, no Fabric):** joins with no error, opens the table,
      crafts normally, and sees no button.
- [ ] **A:** set tables in the Nether and the End, save and quit, reopen. Every table
      shows its old number.
- [ ] **A:** a table exactly on a chunk border, and one at negative X/Z, each load the
      expected chunks. Check with F3 + G chunk borders and the F3 "Chunks" line.
- [ ] **A:** press Esc in singleplayer before opening to LAN. A farm in a loaded chunk
      stops while paused (expected, see README).

Record anything surprising in `.agent/memory/gotchas.md`.
