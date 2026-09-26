---
name: hexregex-android
description: Use when working on the Android/Compose app under android/ of the hexregex repo — porting the Python geometry/generator to Kotlin, regenerating CLI fixtures, running the engine acceptance tests, byte-for-byte puzzle-JSON parity, or booting an emulator to preview the UI. Trigger on "hexregex app", "android/", "port generator", "level_id -> seed", "regex crossword mobile".
---

# hexregex Android app

How to extend and verify the Kotlin/Compose app that fronts the `hexregex`
Python tool. The decisions below are already made — keep them unless the user
explicitly changes them.

## Locked decisions

- **Kotlin + Jetpack Compose, Android only**, F-Droid friendly (no proprietary
  deps, no permissions).
- **On-device constructive generation**, not a server and not a fixed bank.
  Eventually byte-identical to `hexregex gen --seed N` (the serializer is
  already byte-identical; the generator port is the remaining piece).
- **Graphical honeycomb board**, tap-a-cell + on-screen A–Z palette.
- **Pinned presets** (never change these or level ids change):

  | difficulty | kind / size | seed                   |
  | ---------- | ----------- | ---------------------- |
  | easy       | rect 5×5    | `1_000_000 + level_id` |
  | medium     | hex edge 5  | `2_000_000 + level_id` |
  | hard       | hex edge 5  | `3_000_000 + level_id` |

  Each difficulty has its own seed space on purpose: medium and hard share a
  geometry, so a shared seed would give the same truth grid (same answer).
  Full default alphabet `A–Z`, always `--no-unique`, CLI command is
  `hexregex gen --edge 5 --difficulty <tier> --seed $((<base>+L)) --no-unique`.

## Module layout (keep the engine free of Android)

- `android/engine/` — pure `kotlin("jvm")` lib, **no Android dependency**, so it
  can be unit-tested with `./gradlew -p engine test` on a machine with no SDK.
- `android/app/` — Compose UI. `:app` depends on `:engine`.
- `android/fixtures/` — puzzle JSON produced by the CLI; shared as engine test
  resources **and** app assets. Alternate-solution files are test-only and live
  under `engine/src/test/resources/`.

## Porting rules (this is where bugs come from)

1. **Geometry is copied, not reinterpreted.** Port `hexregex/geometry.py`
   literally. X reads bottom-to-top (list reversed), Z top-to-bottom,
   Y left-to-right; reverse lookup `y=r`, `x=c+max(0,r-mid)`,
   `z=c+max(0,mid-r)`.
2. **JSON must match Python byte for byte.** `PyJson` must reproduce
   `json.dumps(value, ensure_ascii=False, indent=2)` and the CLI's trailing
   `\n`; key order follows `generator._puzzle_dict`:
   `kind, author, name, solution, edge|(rows,cols), x, y, z`.
3. **Judge with whole-string match only** (`Matcher.matches()`, the Java
   equivalent of `re.fullmatch`). **Never compare against `puzzle.solution`** —
   constructive puzzles are multi-solution, so that would reject valid answers.
4. **Only the cross-platform regex subset** the generator emits: literals, `.`,
   `[...]`, `[^...]`, `|`, `(?:...)`, `* + ? {m,n}`, `\1`–`\9`.
5. **Kotlin gotcha:** inside `buildList { ... }`, the receiver's `size` shadows a
   class `size` property. Use an explicit `ArrayList` in `cells()`-style loops.

## Regenerating fixtures

```bash
hexregex gen --edge 5 --difficulty easy   --seed 1000 --no-unique -o android/fixtures/rect_easy_1000.json
hexregex gen --edge 5 --difficulty medium --seed 1000 --no-unique -o android/fixtures/hex_medium_1000.json
hexregex gen --edge 5 --difficulty hard   --seed 1000 --no-unique -o android/fixtures/hex_hard_1000.json
```

Then regenerate the two test-only artifacts with the Python modules: a
**known single-cell mutation that breaks a line** and an **alternate valid
solution** found by the solver (`Solver.from_puzzle(...).solve(find_all=False)`,
differs from `solution`). The pattern:

```python
# for each cell, try A–Z != original; first grid a line rejects is the mutation
# alt = solver.solve(find_all=False)[0][0]; write {"solution": rows, **clues}
# assert re.fullmatch(clue, word) for every line of the alt grid
```

## Acceptance tests (`./gradlew -p engine test`, 16 tests)

Must keep passing:

1. Each fixture **round-trips byte for byte** (`Puzzle.toJson() == file text`).
2. Stored solution **passes every line**.
3. The known **single-cell mutation fails**.
4. A **different valid solution passes** (proof there is no stored-answer
   comparison).
5. Geometry: X/Z/Y directions, one line per family, shape counts.

Run without an Android SDK. Build the APK separately with
`./gradlew :app:assembleDebug` (needs `local.properties` → `sdk.dir`).

## Milestones / next work

1. Port `_generate_constructive` + CPython `random.Random` (MT19937,
   `_randbelow`, `choice`, `sample`, `shuffle`, `choices`) into `:engine`, and
   add a test that `app-generated JSON == hexregex gen --seed N` byte for byte.
2. Wire the difficulty tabs to `level_id -> seed -> generate`; prefetch
   `L+1..L+3`; cache.
3. Progress persistence, daily challenge, share by level id.

## Build / tooling notes

- Gradle wrapper 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01,
  compileSdk 35, minSdk 24.
- Gradle's JVM does **not** read `http_proxy`/`https_proxy` env vars. If the
  network needs a proxy, pass `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`
  (and `GRADLE_OPTS` for the wrapper download). Do not bake proxy hosts into
  the repo — they are machine-specific.
- Android SDK is unfree in nixpkgs; if needed, install Google's command-line
  tools under `$HOME` and point `local.properties` at it. `local.properties`
  and all build output are gitignored.
- Collaborators may not use NixOS. Do not add `flake.nix`/`shell.nix` or make
  the shared build depend on Nix.

## Local-only appendix: emulator on NixOS (this machine)

Google's emulator is not linked for NixOS. The working recipe (all outside the
repo):

1. Install `emulator` + `system-images;android-35;default;x86_64` via
   `sdkmanager`, create an AVD with `avdmanager`.
2. `steam-run`'s FHS **cannot see `/tmp`** (so no `/tmp/.X11-unix` socket) → Qt
   xcb fails. Do **not** run the emulator through steam-run for a window.
3. Instead run the emulator on the host with `LD_LIBRARY_PATH` assembled from
   nix store libs (`nss`, `nspr`, `libxcb`, `libxcb-cursor`, `libxcb-util`,
   `xcb-util-*`, `libX11`, `libXext`, `libdrm^out`, `mesa`, `libgbm`,
   `libpng`, `freetype`, `fontconfig`, `libglvnd`, …). `nix build
nixpkgs#libdrm` gives a `-bin` output; use `nixpkgs#libdrm^out` for the lib.
4. Boot windowed with `-gpu swiftshader_indirect`, wait on
   `adb shell getprop sys.boot_completed`, `adb install -r`, then
   `am start -n com.hexregex.app/.MainActivity` and
   `adb exec-out screencap -p` for screenshots.
5. Stop with `adb emu kill` or by closing the window.

This appendix is NixOS/sandbox-specific; never encode it in project files.
