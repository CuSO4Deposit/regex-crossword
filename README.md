# hexregex

Solver and generator for **regular crosswords** — rectangular 2D grids and the
hexagonal 3D form popularised by MIT Mystery Hunt 2013's _A Regular Crossword_
(Dan Gulotta).

Every line of the grid is a regular expression. Fill the grid so that each line
family matches its clue with `re.fullmatch` semantics. The hexagonal form has
three line families (X / Y / Z); the rectangular easy form has two (rows and
columns).

The tool is pure Python (standard library only, no third-party dependencies)
and offers six subcommands:

```
hexregex solve   puzzle.json [--show-stats] [--all] [--alphabet ABC...]
hexregex verify  puzzle.json
hexregex gen     --edge 7 --alphabet CDEHIMNORSTUVX... \
                 --difficulty medium --seed 1 --unique -o puzzle.json
hexregex gen-batch --difficulty medium --count 50 --seed 0 -o bank.json
hexregex render  puzzle.json [--solution] [--jimbly-out board.b64]
hexregex original
```

## Coordinate system and reading directions

An _edge-rich_ hexagon of edge `n` has `size = 2*n - 1` rows and
`3*n*(n-1) + 1` cells. Cells are `(r, c)` with `r = 0 .. size-1` top to
bottom and `c = 0 .. row_size(r)-1` left to right, where
`row_size(r) = n + min(r, size-1-r)`. Let `mid = n-1`.

| family       | description                                      | reading direction             |
| ------------ | ------------------------------------------------ | ----------------------------- |
| **Y line r** | the whole row `r`                                | left → right (`c` increasing) |
| **Z line i** | `c = i - max(0, mid - r)`, skip out-of-range     | top → bottom (`r` increasing) |
| **X line i** | `c = i - max(0, r - mid)`, then reverse the list | bottom → top (`r` decreasing) |

Reverse lookup (cell → line indices): `y = r`;
`x = c + max(0, r - mid)`; `z = c + max(0, mid - r)`.
Each cell lies on exactly one X, one Y and one Z line, for `3*(2n-1)` lines
in total (`n=7` → 127 cells, 39 lines).

## Solving

`hexregex solve` / `hexregex verify` run:

1. **Arc consistency.** For every line and every cell, each candidate letter is
   kept only if the line admits _some_ word with that letter. This is powered
   by a domain-aware regex engine (`regex_engine.py`) that never enumerates
   characters: consuming a literal or class _narrows_ a position's letter set,
   and a backreference _unifies_ captured positions with the positions it
   matches, intersecting their sets. A match is feasible iff no narrowed set
   becomes empty. Memoised on the token stream, this avoids catastrophic
   backtracking even for patterns such as `.*(.)(.)(.)(.)\4\3\2\1.*`.
2. **MRV backtracking.** When propagation stalls, the smallest non-singleton
   domain is chosen and each letter is tried, re-running propagation.
3. **Independent verification.** Every complete solution is re-checked with the
   standard library's `re.fullmatch` on all `3*(2n-1)` lines, so an engine bug
   can never silently produce a wrong answer.

`--show-stats` prints whether the puzzle was solved by propagation alone, the
number of backtracks/guesses/nodes, forced cells and a combined `score`.

The built-in reference puzzle:

```
$ hexregex original --show-stats
```

## Generating

There are **two generation modes**, selected by `--unique` / `--no-unique`:

| mode             | flag                 | solves?                     | cost         | typical use                            |
| ---------------- | -------------------- | --------------------------- | ------------ | -------------------------------------- |
| **unique**       | `--unique` (default) | yes, after every relaxation | seconds      | one exact answer, curated puzzle banks |
| **constructive** | `--no-unique`        | **no solving at all**       | milliseconds | infinite on-device levels              |

Both begin identically:

1. **Pick a true solution grid.** One random letter per cell, or a phrase hidden
   in the middle row with `--message`.
2. **Write literal clues.** Initially every clue is `re.escape` of its true
   word, so the true grid always satisfies all of them (the puzzle is always
   solvable by construction).
3. **Relax the clues** with a random portfolio. Per-token operators: wildcard
   `.`, class `[...]`, negated class `[^x]`, a span replaced by `.*` or wrapped
   in `(?:...)?`, a periodic segment rewritten as `(g)+` / `(...)\1*`, an
   alternation `(a|b|c)*`. Whole-line **MIT-style shapers**: meaningful classes
   `[SET]*c[SET]*`, fixed-length skeletons like `..O[CDH][CNS]NT.`,
   literal-with-gaps `.*T.*H.*O.*D.*`, negated classes `[^R]*N[^R]*`, factored
   alternations `(?:DI|NS|TH)*`, and periodic back-references
   `prefix(block)\1*`. Whole-line shapes are capped by `--max-chunk-alts`
   (default 2) so they stay a garnish, and operators carry a **diversity
   penalty** so clues do not collapse into repeated `.*X.*`. Every candidate
   still has to match the true text.
4. Optional `--templates` tries a built-in list of human-friendly patterns (in
   the style of the original) whenever one matches a line.

What differs by mode is how step 3 is accepted:

- **Unique mode** re-solves after each candidate and keeps it only if the
  puzzle is still unique _and_ its measured score moved toward the target. This
  feedback loop is what costs seconds — uniqueness cannot be maintained by
  random relaxation alone.
- **Constructive mode** never solves. It applies shapers, then flips random
  literal tokens to wildcards/classes until the estimated **opacity** reaches
  the target for the requested difficulty. Difficulty is computed statically
  from the clue style, and multiple solutions are allowed (validate the
  player's grid line-by-line — see below).

### What each difficulty does

|                              | `easy`                     | `medium`                                       | `hard`                                           |
| ---------------------------- | -------------------------- | ---------------------------------------------- | ------------------------------------------------ |
| default grid (`--kind auto`) | 2D rectangle               | 3D hexagon                                     | 3D hexagon                                       |
| target score                 | `0.6 x` easy bound (~33)   | band middle (~62)                              | 10% into hard band (~73)                         |
| every line non-literal?      | no (literals allowed)      | **yes**                                        | **yes**                                          |
| literal-fraction cap         | none                       | `0.6`                                          | none                                             |
| headline requirement         | readable words, few blanks | real cross-referencing (~2 in 5 tokens opaque) | MIT-level looseness, backrefs/chunk alternations |

So: `easy` is a 2D grid you can mostly read off; `medium` is 3D with genuinely
opaque clues; `hard` is 3D at MIT's looseness (the MIT original scores ≈71 and
is the `hard` reference). `--difficulty` chooses the band, `--min-score` /
`--max-score` / `--target-score` override it, and `--kind` overrides the grid.

### Reproducibility and level ids

All randomness comes from a **single** `random.Random(seed)`; nothing else
(time, environment, iteration order) affects the output. Therefore:

- the **same seed + same options** produces an **identical** puzzle, on any run
  and any machine;
- changing any option (`--edge`, `--alphabet`, `--difficulty`, `--kind`,
  `--unique`, `--templates`, target scores, …) yields a different puzzle even
  with the same seed.

A convenient way to get "infinite" levels is to treat the seed as a level id:

```bash
# level id L -> seed BASE+L; the same L always gives the same puzzle
hexregex gen --edge 5 --kind hex --difficulty hard --no-unique \
    --seed $((1000 + L)) -o "level_$L.json"
```

`gen-batch` just walks seeds `base, base+1, ...`, and each entry records the
seed it used under `meta.seed`, so any bank entry can be regenerated exactly
with `gen --seed <that seed>`.

```bash
hexregex gen --seed 7 --no-unique -o a.json
hexregex gen --seed 7 --no-unique -o b.json
diff a.json b.json        # no output: byte-identical
```

### Difficulty

This is a **human** difficulty, not a solver profiler. The MIT Mystery Hunt
2013 puzzle is the `hard` reference even though the solver cracks it by
propagation alone.

`hexregex.difficulty` combines five normalised factors into a `0..100`
**score**:

| component   | meaning                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------------------ |
| `opacity`   | **main factor**: how far clues are from plain literals (wildcards, classes, repeats, alternations, backreferences) |
| `dimension` | 2D (rows + columns) vs 3D (the full hexagon)                                                                       |
| `alphabet`  | vocabulary search space (size of the letter pool)                                                                  |
| `size`      | grid size (edge / rows)                                                                                            |
| `search`    | a small corrective for actual solver effort (nodes, dead ends, residual candidates)                                |

Because `opacity` dominates, a fully literal 3D puzzle is **still easy** — you
can just read each word off. Bands: `easy < 55`, `medium < 70`, `hard ≥ 70`
(configurable in `DifficultyWeights`). Anchors:

```
literal 2D grid                score ≈ 16   easy
literal 3D hexagon             score ≈ 35-39 easy
3D with ~25-45% non-literal    score ≈ 50-65 medium
MIT original                   score ≈ 71   hard
```

So `medium` requires genuinely opaque clues (roughly 2 in 5 tokens are
wildcards, classes, repeats or alternations), not merely "3D + full alphabet".
`easy` defaults to a 2D rectangle while `medium`/`hard` use the 3D hexagon
(`--kind` overrides this). Uniqueness is orthogonal.

The generator additionally caps the overall literal fraction
(`--max-literal-fraction`, default 0.6 for `medium`, none otherwise).

For `medium` and `hard`, **no line is left as a fully literal string** — a
literal clue would give away a whole row. A dedicated pass relaxes every such
line before normal tuning begins (`--allow-literal-lines` disables this).

The generator **targets the score before producing the puzzle**: you choose a
band (`--difficulty`) or an explicit window (`--min-score` / `--max-score`),
and it climbs there greedily — each micro-relaxation is measured, the score is
never allowed to go down, overshoot past the window's top is rejected, and
generation stops as soon as the measured score lands inside the window. So the
score is an **input**, not a post-hoc label. If the target cannot be reached
it raises `GenerationError` with the closest statistics.

### Knobs

| flag                          | effect                                                                         |
| ----------------------------- | ------------------------------------------------------------------------------ |
| `--edge`                      | grid size (hex edge, or rectangle side for `--kind rect`)                      |
| `--kind`                      | `auto` (easy=2D, medium/hard=3D), `hex` or `rect`                              |
| `--alphabet`                  | letter pool (bigger = harder)                                                  |
| `--difficulty`                | `easy` / `medium` / `hard` target                                              |
| `--seed`                      | reproducible output                                                            |
| `--unique` / `--no-unique`    | require exactly one solution                                                   |
| `--loosen`                    | maximum relaxation steps                                                       |
| `--literal-ratio`             | stop once the literal fraction reaches this value                              |
| `--min-score` / `--max-score` | target an explicit difficulty-score window (overrides the band)                |
| `--message`                   | hide a phrase in the middle row                                                |
| `--templates`                 | prefer human-friendly template clues                                           |
| `--allow-backref`             | enable backreference relaxation operators                                      |
| `--max-chunk-alts`            | cap multi-char alternations `(O\|RHH\|MM)*` per puzzle (default 2, chosen 0-2) |
| `--max-literal-fraction`      | reject puzzles above this literal-token fraction (default 0.6 for medium)      |
| `--target-score`              | aim for an exact score and stop (defaults to band middle / hard 30%)           |
| `--full-unique`               | use the expensive full uniqueness check instead of propagation-only            |
| `--allow-literal-lines`       | permit fully literal clues at medium/hard                                      |
| `--max-attempts`              | attempts before reporting failure                                              |

Output is puzzle JSON. Hexagonal puzzles carry `edge`, `x`, `y`, `z`;
rectangular ones carry `rows`, `cols`, `x`, `y` plus `"kind": "rect"`.
`--jimbly-out` (hex only) writes jimbly's `?puzzle=<base64>` payload.

## Infinite levels / shipping to a phone

Uniqueness is **optional**. If you don't need it (`--no-unique`), generation
needs **no solving at all**: it picks a true grid, writes clues that all match
it, and dials difficulty purely by how many clue tokens are non-literal. That
runs in **milliseconds**, so the app can generate an unbounded stream of fresh
levels on device from a seed.

```
$ hexregex gen --edge 5 --kind hex --difficulty hard --seed 42 --no-unique
  ... ~20 ms, no solver call ...
```

Two consequences for the app:

- Validate the player's grid **line by line with `re.fullmatch`**, not by
  comparing to the stored `solution` (non-unique puzzles have several valid
  grids). The stored `solution` is one valid grid, useful for hints/reveal.
- Difficulty in this mode is estimated from clue style (`opacity`) + grid
  size, which is exactly what the score is dominated by.

If you _do_ want a unique solution (`--unique`, the `gen` default), the
generator must solve after each relaxation to preserve uniqueness, which costs
seconds per puzzle. Recommended:

- **Offline (build time):** `hexregex gen-batch --difficulty medium --count 50
-o bank.json` writes a bank of unique puzzles with metadata. Ship it.
- **On device:** load the bank; for infinite fresh content, generate
  `--no-unique` levels instantly from `seed = BASE + level_id`, and/or prefetch
  unique levels in a background thread.
- **Easy 2D** unique puzzles are cheap enough (~0.1-1 s) to generate on device
  (`--fast`).

`hexregex gen --seed N` is deterministic: the same `N` always yields the same
puzzle, so level ids are reproducible and shareable with no server.

## Rendering

`hexregex render puzzle.json [--solution]` draws the hexagon with a
row-length-based indentation (`.` for unknown cells).
`--jimbly-out board.b64` writes the browser-playable base64 payload.

## Puzzle JSON format

```json
{
  "edge": 7,
  "author": "Dan Gulotta",
  "name": "original",
  "x": ["... 13 clues ..."],
  "y": ["... 13 clues ..."],
  "z": ["... 13 clues ..."],
  "solution": [["N", "H", "P", "..."], ["..."]]
}
```

## Code layout

```
hexregex/
  geometry.py       coordinates, line building, reverse lookup
  regex_engine.py   parser + domain-aware feasibility matcher
  solver.py         propagation, MRV search, statistics, verification
  difficulty.py     multi-factor difficulty measurement and bands
  generator.py      solution sampling, relaxation operators, difficulty
  data.py           built-in MIT 2013 puzzle
  cli.py            argparse front end
tests/
  test_geometry.py  test_regex_engine.py  test_solver.py
  test_difficulty.py test_generator.py test_cli.py
```

## Running the tests

On NixOS the interpreter is available ad hoc:

```bash
nix shell nixpkgs#python3 -c "python3 -m unittest discover -s tests -v"
```

(Any Python ≥ 3.8 works; the package itself has no dependencies.)
