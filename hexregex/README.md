# hexregex

Python reference implementation for the Regex Crossword app.

The app's Kotlin engine was ported from this code. It is used to design and
check the algorithms and to generate test fixtures. Pure Python, standard
library only; it is not published on PyPI and is versioned separately from the
app (the version lives in [`../pyproject.toml`](../pyproject.toml)).

## Usage

From the repo root:

```bash
python3 -m hexregex --help
```

Or install the local `hexregex` command with `pip install .`. The CLI generates,
batch-generates, solves, verifies, and renders puzzles. `--help` and
[`cli.py`](cli.py) are the source of truth for the current commands and flags.

## Docs

- [Architecture](../docs/architecture.md) — coordinates, reading directions,
  code layout
- [Generation](../docs/generation.md) — generation modes, relaxation operators,
  difficulty scoring, reproducibility, level ids
- [Solving](../docs/solving.md) — the solver and how a grid is judged
- [Changelog](../CHANGELOG.md)
- [License](../LICENSE)
