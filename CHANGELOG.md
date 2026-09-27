# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the Android app follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
The `hexregex` Python library is versioned separately (its current release is
`0.5.0`).

## [Unreleased]

## [0.1.0] - 2026-09-27

First public release of the Android app.

### Added

- Android app `io.github.cuso4deposit.regexcrossword`: an offline regex
  crossword. No accounts, no ads, no network access, no permissions.
- Three difficulties: **Easy** (rectangle 5×5), **Medium** and **Hard**
  (hexagon). Puzzles are generated on device from a seed.
- Hexagonal board with three colour-coded reading directions (X, Y, Z), plus a
  rectangle mode (rows and columns), showing each line's reading direction and
  start cell.
- On-screen A–Z keyboard, pencil-mark notes, per-cell **Hint**, **Givens**
  (letters a clue writes down literally), **Undo/Redo**, and a per-level timer.
- Progress (grid, notes, selection, solved state, elapsed time) is saved
  automatically per level.
- Hard levels come from a bundled bank of puzzles pre-generated with the solver
  to guarantee a unique solution, with background top-up on device.
- `hexregex` Python CLI with `gen`, `gen-batch`, `solve`, `verify`, `render`
  and `original` subcommands.
- Byte-for-byte parity between the Python generator and the Kotlin engine,
  enforced by tests.

### Credits

- The hexagonal "A Regular Crossword" form was popularised by Dan Gulotta
  (based on an idea by Palmer Mebane) in MIT Mystery Hunt 2013. See the
  [original puzzle](https://puzzles.mit.edu/2013/coinheist.com/rubik/a_regular_crossword/).

[Unreleased]: https://github.com/CuSO4Deposit/regex-crossword/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/CuSO4Deposit/regex-crossword/releases/tag/v0.1.0
