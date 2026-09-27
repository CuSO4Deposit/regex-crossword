# Generating puzzles

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
  player's grid line-by-line — see [solving.md](solving.md)).

## What each difficulty does

|                              | `easy`                     | `medium`                                       | `hard`                                           |
| ---------------------------- | -------------------------- | ---------------------------------------------- | ------------------------------------------------ |
| default grid (`--kind auto`) | 2D rectangle               | 3D hexagon                                     | 3D hexagon                                       |
| target score                 | `0.6 x` easy bound (~33)   | band middle (~62)                              | 10% into hard band (~73)                         |
| every line non-literal?      | no (literals allowed)      | **yes**                                        | **yes**                                          |
| literal-fraction cap         | none                       | `0.6`                                          | none                                             |
| headline requirement         | readable words, few blanks | real cross-referencing (~2 in 5 tokens opaque) | MIT-level looseness, backrefs/chunk alternations |

So: `easy` is a 2D grid you can mostly read off; `medium` is 3D with genuinely
opaque clues; `hard` is 3D at MIT's looseness. `--difficulty` chooses the band,
`--min-score` / `--max-score` / `--target-score` override it, and `--kind`
overrides the grid.

## Reproducibility and level ids

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

## Difficulty

This is a **human** difficulty, not a solver profiler.

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
(configurable in `DifficultyWeights`).

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
