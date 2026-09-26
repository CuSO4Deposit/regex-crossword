"""A human-oriented difficulty measurement for regular crosswords.

This is a *game* difficulty, not a solver profiler: it is anchored on how hard
the puzzle is for a person, so the MIT Mystery Hunt 2013 puzzle lands in
``hard`` even though the solver can crack it by propagation alone.

The score is a weighted sum of normalised factors in ``[0, 1]`` times 100:

``dimension``
    2D (rows + columns) versus 3D (the full hexagon with three interacting
    line families).  3D is a hard step up in reading effort.
``opacity``
    How far the clues are from plain literals (wildcards, classes, repeats,
    alternations, backreferences).  Opaque clues are harder to read.
``alphabet`` / ``size``
    Raw vocabulary / grid search space.
``search``
    A small corrective for actual solver effort (nodes explored, dead ends,
    residual candidates), so that genuinely under-determined puzzles are not
    rated easy.

Anchors: a literal 2D grid ≈ 16 (``easy``), a literal 3D hexagon ≈ 35-39
(``easy`` -- a fully literal 3D puzzle is trivial to read), a 3D puzzle with
~25-45% non-literal clues ≈ 50-65 (``medium``), and the MIT original ≈ 71
(``hard``).  Thresholds and weights live in :class:`DifficultyWeights`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Dict, Iterable, Mapping, Sequence


@dataclass
class DifficultyWeights:
    """Weights and band thresholds for the *human-oriented* score.

    The score is a weighted sum of normalised factors in ``[0, 1]`` multiplied
    by 100.  It is anchored on human play, not solver effort:

    * ``dimension``  -- 2D (rows + columns) vs 3D (the full hexagon); a hard
      step, because 3D puzzles have a third interacting line family.
    * ``opacity``    -- how far clues are from plain literals (wildcards,
      classes, repeats, alternations, backreferences).
    * ``alphabet`` / ``size`` -- raw vocabulary search space.
    * ``search``     -- a small solver-effort corrective.

    Anchors: the MIT Mystery Hunt 2013 puzzle scores ~71 (``hard``), a 3D
    hexagon with ~25-45% non-literal clues ~50-65 (``medium``), and a literal
    2D/3D grid ~16-39 (``easy``).
    """

    dimension: float = 0.18
    opacity: float = 0.55
    search: float = 0.07
    alphabet: float = 0.10
    size: float = 0.10
    easy_max: float = 55.0
    medium_max: float = 70.0


DEFAULT_WEIGHTS = DifficultyWeights()

_BANDS = ("easy", "medium", "hard")


def _walk(ast) -> Iterable[tuple]:
    for node in ast:
        yield node
        op = node[0]
        if op == "rep":
            yield from _walk(node[3])
        elif op == "group":
            yield from _walk(node[2])
        elif op == "alt":
            for branch in node[1]:
                yield from _walk(branch)


def clue_style(clues: Mapping[str, Sequence[str]]) -> Dict[str, float]:
    """Static style measurements of the clue set (no solving required)."""
    from .regex_engine import compile_pattern

    families = [f for f in ("x", "y", "z") if f in clues]
    counts = {
        "literals": 0,
        "anys": 0,
        "classes": 0,
        "repeats": 0,
        "alternations": 0,
        "backrefs": 0,
        "groups": 0,
    }
    lines = tokens = 0
    for family in families:
        for pattern in clues[family]:
            lines += 1
            for node in _walk(compile_pattern(pattern).ast):
                tokens += 1
                op = node[0]
                if op == "lit":
                    counts["literals"] += 1
                elif op == "any":
                    counts["anys"] += 1
                elif op == "class":
                    counts["classes"] += 1
                elif op == "rep":
                    counts["repeats"] += 1
                elif op == "alt":
                    counts["alternations"] += 1
                elif op == "ref":
                    counts["backrefs"] += 1
                elif op == "group":
                    counts["groups"] += 1
    literal_ratio = counts["literals"] / tokens if tokens else 1.0
    return {
        "dimension": len(families),
        "lines": lines,
        "tokens": tokens,
        "literal_ratio": literal_ratio,
        "wildcard_ratio": (tokens - counts["literals"]) / tokens if tokens else 0.0,
        "backref_ratio": counts["backrefs"] / tokens if tokens else 0.0,
        "tokens_per_line": tokens / lines if lines else 0.0,
        **counts,
    }


@dataclass
class DifficultyReport:
    score: float
    band: str
    components: Dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, object]:
        return {"score": self.score, "band": self.band, "components": dict(self.components)}


def measure(stats, style: Mapping[str, float], weights: DifficultyWeights = DEFAULT_WEIGHTS) -> DifficultyReport:
    """Combine solver statistics and clue style into a human-oriented report."""
    dimension = 1.0 if float(style.get("dimension", 3)) >= 3 else 0.0
    opacity = 1.0 - float(style.get("literal_ratio", 1.0))
    residual = math.log2(1.0 + max(0, getattr(stats, "residual_candidates", 0)))
    search_raw = (
        math.log2(1.0 + getattr(stats, "nodes", 0))
        + 2.0 * math.log2(1.0 + getattr(stats, "backtracks", 0))
        + residual
    )
    search = min(1.0, search_raw / 12.0)
    alphabet = min(1.0, math.log2(max(1.0, getattr(stats, "alphabet_size", 1)) + 1.0) / math.log2(27.0))
    edge = max(0.0, float(getattr(stats, "edge", 0)))
    size = min(1.0, max(0.0, (edge - 2.0) / 5.0))

    breakdown = {
        "dimension": weights.dimension * dimension,
        "opacity": weights.opacity * opacity,
        "search": weights.search * search,
        "alphabet": weights.alphabet * alphabet,
        "size": weights.size * size,
    }
    score = round(100.0 * sum(breakdown.values()), 2)
    if score < weights.easy_max:
        band = "easy"
    elif score < weights.medium_max:
        band = "medium"
    else:
        band = "hard"
    components = {k: round(v, 4) for k, v in breakdown.items()}
    components["raw"] = round(sum(breakdown.values()), 4)
    return DifficultyReport(score=score, band=band, components=components)
