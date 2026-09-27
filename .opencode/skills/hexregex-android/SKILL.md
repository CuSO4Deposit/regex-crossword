---
name: hexregex-android
description: Use when working on the Android/Compose app under android/ of the hexregex repo — porting the Python geometry/generator to Kotlin, regenerating CLI fixtures, running the engine acceptance tests, byte-for-byte puzzle-JSON parity, building/installing the APK on a connected device or emulator (adb install -r, launch activity, screenshots), or previewing the UI. Trigger on "hexregex app", "android/", "port generator", "level_id -> seed", "install apk", "adb install", "regex crossword mobile".
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

  | difficulty | kind / size | seed                   | mode                                                                        |
  | ---------- | ----------- | ---------------------- | --------------------------------------------------------------------------- |
  | easy       | rect 5×5    | `1_000_000 + level_id` | constructive                                                                |
  | medium     | hex edge 5  | `2_000_000 + level_id` | constructive (`--difficulty hard --target-score 85`)                        |
  | hard       | hex edge 5  | `3_000_000 + level_id` | **unique** (`--difficulty hard --unique --allow-backref --target-score 76`) |

  Each difficulty has its own seed space on purpose: medium and hard share a
  geometry, so a shared seed would give the same truth grid (same answer).
  Constructive medium/hard apply position-free MIT-style clues (`.*c.*`,
  `[SET]*c[SET]*`, class/alt stars, backref repeats) so letters are not pinned
  to cells; `shape_dot_skeleton` (fixed-length) is excluded for them. Full
  default alphabet `A–Z`.

  **HARD is a bundled bank + background refill**: unique generation takes
  seconds-to-minutes, so 50 unique hard puzzles ship in
  `app/src/main/assets/hard_bank.json` (regenerate with the engine's
  `generateHardBank` Gradle task); on device a background effect keeps ~50
  unsolved hard levels cached ahead. `Generator.unique` is _total_ (always
  returns the most-relaxed unique puzzle for a seed, never raises), so
  `seed = base + level` is a valid index with no skips.

- **Identity, not level number.** A puzzle is `(GENERATOR_VERSION, seed)`; the
  level number is just this version's level↔seed bijection. Progress is keyed
  by `gv<version>_<difficulty>_<seed>`. Bump `GENERATOR_VERSION` (in
  `Levels.kt`) on _any_ change to the generator, presets, or seed bases, so old
  saves never silently point at a different puzzle.

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

The committed fixtures are **frozen at the generator version they were made
with**; after generator changes these commands may no longer reproduce them
(`rect_easy_1000` still does; medium/hard diverged). Never regenerate a fixture
without updating its byte-parity expectation in the same commit.

Then regenerate the two test-only artifacts with the Python modules: a
**known single-cell mutation that breaks a line** and an **alternate valid
solution** found by the solver (`Solver.from_puzzle(...).solve(find_all=False)`,
differs from `solution`). The pattern:

```python
# for each cell, try A–Z != original; first grid a line rejects is the mutation
# alt = solver.solve(find_all=False)[0][0]; write {"solution": rows, **clues}
# assert re.fullmatch(clue, word) for every line of the alt grid
```

## Acceptance tests (`./gradlew -p engine test`, 35 tests)

Must keep passing:

1. Each fixture **round-trips byte for byte** (`Puzzle.toJson() == file text`).
2. Stored solution **passes every line**.
3. The known **single-cell mutation fails**.
4. A **different valid solution passes** (proof there is no stored-answer
   comparison).
5. Geometry: X/Z/Y directions, one line per family, shape counts.

Run without an Android SDK. Build the APK separately with
`./gradlew :app:assembleDebug` (needs `local.properties` → `sdk.dir`).

## Build, install, verify on a connected device

To try a change on a phone/emulator the user already has attached (don't assume
— check first):

1. `adb devices`. A usable line ends in `device`; `unauthorized` means the user
   must accept the RSA prompt; `offline` means wait. If several are attached,
   pass `-s <serial>` to every command.
2. Build a **release** APK so it matches the installed signing key:
   `./gradlew :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`.
   (Release signing reads `android/keystore.properties`, which is gitignored —
   never read it aloud, echo it, or commit it.)
3. The installed copy can be updated **in place only if the applicationId and
   signing key match**. The Play/debug/release keys are all different. Check with
   `adb shell pm list packages | grep <applicationId>`; if the app is present
   from the same key, `adb install -r <apk>` succeeds. A signature mismatch
   forces an uninstall first, which **wipes `SharedPreferences` (all progress and
   times)** — prefer keeping one key.
4. Launch it. The namespace is `.app` while the applicationId is not, so the
   launcher class is `io.github.cuso4deposit.regexcrossword.app.MainActivity`
   (explicit `am start -n <applicationId>/.MainActivity` fails). Resolve the
   real one when unsure:
   `adb shell cmd package resolve-activity --brief <applicationId>`.
5. Verify visually: `adb exec-out screencap -p > /tmp/shot.png`. Confirm the app
   is actually foreground with `adb shell dumpsys activity activities | grep -i
resumed`.

Do this without asking only when the user's request implies on-device checking;
otherwise offer. Never hardcode a device serial into the repo.

## Milestones / next work

1. Port `_generate_constructive` + CPython `random.Random` (MT19937,
   `_randbelow`, `choice`, `sample`, `shuffle`, `choices`) into `:engine`, and
   add a test that `app-generated JSON == hexregex gen --seed N` byte for byte.
2. Wire the difficulty tabs to `level_id -> seed -> generate`; prefetch
   `L+1..L+3`; cache.
3. Progress persistence, daily challenge, share by level id.

## Expert difficulty: offline constraint-learning — SHELVED

Status: **shelved / not shipping.** The algorithm below works (unique,
0 single-clue-forced) but its output is a _per-position class skeleton_
(every line a sequence of `[^...]` classes). That is fundamentally unlike the
MIT original's style (`.*H.*H.*`, `(DI|NS|TH|OM)*`, literals, alternations,
back-references, cross-line interaction). Uniqueness + zero single-clue givens
demands many per-position constraints; expressing them in a fixed grid yields a
dense class wall, not MIT-looking clues. Kept for reference only.

The constructive/unique generators can produce a **unique** puzzle, but their
uniqueness comes from _pinning cells_: on edge 5, ~34/61 cells are decidable
from a single clue (the opaque HARD settings only hide some of them). Coaxing
them into MIT-style slack either loses uniqueness, needs 17-34 prefilled
anchors, or costs minutes per puzzle (edge 7 ≈ 10 min). So a fourth tier,
**Expert**, is produced **offline** by a different method and shipped as a
curated bank (like HARD), never generated on device.

`learn(seed)` — the constraint-learning algorithm (Python prototype; to port):

1. Pick a random truth grid.
2. Start each line as a fixed-length sequence of full-alphabet classes
   (`.....`): position-addressable, zero information.
3. Repeat: solve up to `k` solutions; if exactly one, stop. Otherwise, for every
   cell where the solutions disagree, **exclude the wrong letters** (those not
   equal to the truth) from that cell's class in one of its lines, preferring to
   keep the class size `>= 2` so **no cell is ever pinned by a single clue**.
4. It converges to clues that pin no cell individually, yet whose cross-line
   intersections force a unique solution.

Measured (edge 5, seed 3000000, `k=6`): 146 iterations, ~48 s, unique,
**0/61 single-clue-forced** cells.

Open work before porting:

- Fairness: the greedy currently picks each cell's _first_ line (always X), so
  constraints pile onto X and Z lines stay empty. Rotate over a cell's lines.
- Clue quality: render short classes as `[^...]`, keep lines varied, and
  consider mixing in `.*`/optional structure so the style looks MIT-like.
- Port to `:engine` and add a `learnBank` tool; regenerate a bank of Expert
  levels. Add a parity/uniqueness test.

## Adding a difficulty across app versions (migration)

Difficulty identity is `(GENERATOR_VERSION, difficulty, seed)` and progress is
keyed by it, so adding a tier must not disturb existing saves:

- Add the new enum value with its own `seedBase` and a `bank` asset; keep the
  existing tiers' `seedBase`, presets and `GENERATOR_VERSION` **unchanged**.
- Bump `GENERATOR_VERSION` only when an _existing_ tier's generation changes;
  that intentionally orphans only that tier's old keys.
- New tiers ship their own bank; old progress under `gv<v>_<old>_<seed>` is left
  intact and keeps loading.
- If a new version must move/rename keys, write an explicit, idempotent
  migration on first launch (read old keys, write new, keep both until done).
- Never reuse a level's identity for different content.

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
   `am start -n io.github.cuso4deposit.regexcrossword/.app.MainActivity` and
   `adb exec-out screencap -p` for screenshots.
5. Stop with `adb emu kill` or by closing the window.

This appendix is NixOS/sandbox-specific; never encode it in project files.
