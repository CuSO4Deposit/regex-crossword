# Regex Crossword

A **regular crossword** game: every line of the grid is a regular expression.
Fill the cells so that each line family matches its clue with `re.fullmatch`
semantics. The hexagonal form has three line families (X / Y / Z); the
rectangular easy form has two (rows and columns). The hexagonal form was
popularised by MIT Mystery Hunt 2013's _A Regular Crossword_ (Dan Gulotta).

The product is the **Android app** under [`android/`](android/README.md).

The generators and solver the app uses were prototyped in Python; that
**reference implementation** lives in `hexregex/` and is documented below. It is
not published as a package — it exists to design and validate the algorithms and
to generate test fixtures, so its version number (`0.5.0`) is independent of the
app's (`0.1.0`).

## Android app

An offline regex-crossword app: Easy (rectangle 5×5), Medium and Hard (hexagon),
generated on device from a seed. No accounts, ads, network access or
permissions.

Build, install and app-specific documentation: [`android/README.md`](android/README.md).

## Reference implementation (`hexregex`)

Pure Python, standard library only. Run it from a checkout:

```bash
python3 -m hexregex --help
```

or, for a local `hexregex` command, `pip install .` in the checkout (no PyPI
release is planned).

It offers five subcommands:

```
hexregex solve   puzzle.json [--show-stats] [--all] [--alphabet ABC...]
hexregex verify  puzzle.json
hexregex gen     --edge 7 --alphabet CDEHIMNORSTUVX... \
                 --difficulty medium --seed 1 --unique -o puzzle.json
hexregex gen-batch --difficulty medium --count 50 --seed 0 -o bank.json
hexregex render  puzzle.json [--solution] [--jimbly-out board.b64]
```

### Examples

```bash
# a unique hexagonal puzzle, then solve and draw it
hexregex gen --edge 7 --kind hex --difficulty hard --seed 1 --unique -o hard.json
hexregex solve hard.json --show-stats
hexregex render hard.json --solution
```

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

### Rendering

`hexregex render puzzle.json [--solution]` draws the hexagon with a
row-length-based indentation (`.` for unknown cells).
`--jimbly-out board.b64` writes the browser-playable base64 payload.

### Puzzle JSON format

```json
{
  "edge": 7,
  "author": "generated",
  "name": "example",
  "x": ["... 13 clues ..."],
  "y": ["... 13 clues ..."],
  "z": ["... 13 clues ..."],
  "solution": [["N", "H", "P", "..."], ["..."]]
}
```

## Documentation

- [docs/architecture.md](docs/architecture.md) — coordinates, reading
  directions, code layout
- [docs/generation.md](docs/generation.md) — relaxation operators, difficulty
  scoring, reproducibility and level ids
- [docs/solving.md](docs/solving.md) — the solver and how to judge a grid
- [CHANGELOG.md](CHANGELOG.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT — see [LICENSE](LICENSE). The hexagonal "A Regular Crossword" form was
popularised by Dan Gulotta (based on an idea by Palmer Mebane) in MIT Mystery
Hunt 2013. This project is an independent implementation and includes neither
the original puzzle nor its text.

Source: <https://github.com/CuSO4Deposit/regex-crossword>
