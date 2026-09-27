# Architecture

## Coordinates and reading directions

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

The rectangular (`--kind rect`) form has two families: rows (`y`, left → right)
and columns (`z`, top → bottom).

## Code layout

```
hexregex/
  geometry.py       coordinates, line building, reverse lookup
  regex_engine.py   parser + domain-aware feasibility matcher
  solver.py         propagation, MRV search, statistics, verification
  difficulty.py     multi-factor difficulty measurement and bands
  generator.py      solution sampling, relaxation operators, difficulty
  cli.py            argparse front end
tests/
  test_geometry.py  test_regex_engine.py  test_solver.py
  test_difficulty.py test_generator.py test_cli.py
android/
  engine/           pure-JVM Kotlin port of geometry/generator/solver, with
                    byte-for-byte JSON parity tests
  app/              Jetpack Compose UI
```
