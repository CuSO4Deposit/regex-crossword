# Solving

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

## Judge whole strings, never the stored answer

The stored `solution` in a generated puzzle is _one_ valid grid. Constructive
(`--no-unique`) puzzles have many valid grids, so a player's grid must be judged
line by line with `re.fullmatch` semantics, not compared to `solution`. The
stored solution is useful for hints and reveal.
