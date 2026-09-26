"""Constraint solver for hexagonal regular crosswords.

The solver combines three ingredients:

1. **Arc-consistency propagation.**  For every line and every cell on it we
   ask the regex engine whether fixing that cell to each candidate letter
   still leaves the line satisfiable; impossible letters are removed until a
   fixed point is reached.
2. **MRV backtracking search.**  When propagation stalls, the cell with the
   smallest domain (> 1) is selected and each of its candidates is tried.
3. **Final verification.**  Every complete solution is re-checked against
   :func:`re.fullmatch` on all ``3 * (2n-1)`` lines, so an internal engine bug
   can never silently produce a wrong answer.
"""

from __future__ import annotations

import re
from collections import deque
from dataclasses import dataclass, field
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

from .difficulty import DEFAULT_WEIGHTS, clue_style, measure
from .geometry import Cell, HexGeometry
from .regex_engine import Compiled, RegexSyntaxError, compile_pattern, feasible_cached

DEFAULT_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

#: A puzzle that needs search but never explores a dead end is ``medium``;
#: once a branch is refuted (a backtrack) it counts as ``hard``.
EASY_MAX_BACKTRACKS = 0
MEDIUM_MAX_BACKTRACKS = 0


class SolverError(RuntimeError):
    """Raised for malformed puzzles or internal verification failures."""


@dataclass
class SolveStats:
    """Statistics collected while solving; used as the difficulty yardstick."""

    edge: int = 0
    alphabet_size: int = 0
    solved_by_propagation: bool = False
    backtracks: int = 0
    nodes: int = 0
    guesses: int = 0
    forced_cells: int = 0
    initial_multi_cells: int = 0
    max_branch_factor: int = 0
    max_line_candidates: float = 0.0
    #: cells still multi-valued after propagation, and how many decisions
    #: (sum of ``|domain| - 1``) that leaves over
    residual_cells: int = 0
    residual_candidates: int = 0
    max_residual_domain: int = 0
    propagation_passes: int = 0
    score: float = 0.0
    band: str = ""
    components: Dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, object]:
        return {
            "edge": self.edge,
            "alphabet_size": self.alphabet_size,
            "solved_by_propagation": self.solved_by_propagation,
            "backtracks": self.backtracks,
            "nodes": self.nodes,
            "guesses": self.guesses,
            "forced_cells": self.forced_cells,
            "initial_multi_cells": self.initial_multi_cells,
            "residual_cells": self.residual_cells,
            "residual_candidates": self.residual_candidates,
            "max_residual_domain": self.max_residual_domain,
            "propagation_passes": self.propagation_passes,
            "max_branch_factor": self.max_branch_factor,
            "max_line_candidates": self.max_line_candidates,
            "score": self.score,
            "difficulty": self.difficulty(),
            "components": dict(self.components),
        }

    def difficulty(self) -> str:
        """Bucket the composite score into ``easy`` / ``medium`` / ``hard``.

        The score combines residual search space, propagation power, actual
        search effort, clue opacity and raw size; see :mod:`hexregex.difficulty`.
        """
        if self.band:
            return self.band
        if self.solved_by_propagation and self.backtracks == 0:
            return "easy"
        if self.backtracks <= MEDIUM_MAX_BACKTRACKS:
            return "medium"
        return "hard"


class Solver:
    """Backtracking solver for one puzzle.

    ``geometry`` is a :class:`~hexregex.geometry.HexGeometry` (3D) or
    :class:`~hexregex.geometry.RectGeometry` (2D).  For convenience an ``int``
    is accepted and treated as a hexagonal edge length.
    """

    def __init__(
        self,
        geometry,
        clues: Mapping[str, Sequence[str]],
        alphabet: Optional[Sequence[str]] = None,
    ):
        if isinstance(geometry, int):
            geometry = HexGeometry(geometry)
        self.geo = geometry
        self.geo.validate()
        self.edge = self.geo.num_rows
        self.dimension = len(self.geo.families)
        self.alphabet: List[str] = sorted(set(alphabet or DEFAULT_ALPHABET))
        if not self.alphabet:
            raise SolverError("alphabet must not be empty")

        family_counts: Dict[str, int] = {}
        for line in self.geo.lines:
            family_counts[line.family] = family_counts.get(line.family, 0) + 1
        for family, count in family_counts.items():
            if family not in clues:
                raise SolverError(f"clues must provide a {family!r} list")
            if len(clues[family]) != count:
                raise SolverError(
                    f"clue family {family!r} must have {count} entries, got {len(clues[family])}"
                )

        self.compiled: List[Compiled] = []
        self.line_cells: List[Tuple[Cell, ...]] = []
        self.clue_of: List[str] = []
        self.cell_lines_map: Dict[Cell, List[int]] = {}
        for li, line in enumerate(self.geo.lines):
            text = clues[line.family][line.index]
            try:
                self.compiled.append(compile_pattern(text))
            except RegexSyntaxError as exc:
                raise SolverError(
                    f"bad clue {line.family}[{line.index}] = {text!r}: {exc}"
                ) from exc
            self.line_cells.append(line.cells)
            self.clue_of.append(text)
            for cell in line.cells:
                self.cell_lines_map.setdefault(cell, []).append(li)

        self.style = clue_style(clues)
        self.weights = DEFAULT_WEIGHTS
        self._prop_passes = 0

    # ------------------------------------------------------------------
    # helpers
    # ------------------------------------------------------------------
    @classmethod
    def from_puzzle(cls, puzzle: Mapping, alphabet: Optional[Sequence[str]] = None) -> "Solver":
        kind = puzzle.get("kind", "hex")
        if kind == "rect":
            from .geometry import RectGeometry

            geo = RectGeometry(int(puzzle["rows"]), int(puzzle["cols"]))
        else:
            geo = HexGeometry(int(puzzle["edge"]))
        return cls(geo, puzzle, alphabet=alphabet)

    def _initial_domains(self) -> Dict[Cell, set]:
        return {cell: set(self.alphabet) for cell in self.geo.cells()}

    def _cell_lines(self, cell: Cell) -> List[int]:
        return self.cell_lines_map.get(cell, [])

    # ------------------------------------------------------------------
    # propagation
    # ------------------------------------------------------------------
    def _revise(self, line_id: int, domains: Dict[Cell, set]) -> bool:
        cells = self.line_cells[line_id]
        compiled = self.compiled[line_id]
        allowed = [frozenset(domains[cell]) for cell in cells]
        changed = False
        for p, cell in enumerate(cells):
            dom = domains[cell]
            keep = []
            old = allowed[p]
            for ch in dom:
                allowed[p] = frozenset((ch,))
                if feasible_cached(allowed, compiled):
                    keep.append(ch)
            allowed[p] = old
            if len(keep) != len(dom):
                domains[cell] = set(keep)
                changed = True
                if not keep:
                    return True
        return changed

    def propagate(self, domains: Dict[Cell, set]) -> bool:
        """Run arc consistency to a fixed point.  Returns False on a wipe-out."""
        n = len(self.geo.lines)
        queue = deque(range(n))
        in_queue = [True] * n
        while queue:
            line_id = queue.popleft()
            in_queue[line_id] = False
            self._prop_passes += 1
            changed = self._revise(line_id, domains)
            if any(not domains[cell] for cell in self.line_cells[line_id]):
                return False
            if changed:
                for cell in self.line_cells[line_id]:
                    for other in self._cell_lines(cell):
                        if not in_queue[other]:
                            queue.append(other)
                            in_queue[other] = True
        return True

    # ------------------------------------------------------------------
    # search
    # ------------------------------------------------------------------
    def _mrv_cell(self, domains: Dict[Cell, set]) -> Optional[Cell]:
        best: Optional[Cell] = None
        best_size = 1 << 30
        for cell, dom in domains.items():
            size = len(dom)
            if 1 < size < best_size:
                best_size = size
                best = cell
                if size == 2:
                    break
        return best

    def _search(
        self,
        domains: Dict[Cell, set],
        solutions: List[Dict[Cell, str]],
        find_all: bool,
        max_solutions: int,
        stats: SolveStats,
    ) -> bool:
        """Return True if the search should stop early."""
        stats.nodes += 1
        cell = self._mrv_cell(domains)
        if cell is None:
            solution = {c: next(iter(d)) for c, d in domains.items()}
            self.verify_solution(solution)
            solutions.append(solution)
            return not find_all or len(solutions) >= max_solutions
        candidates = sorted(domains[cell])
        stats.max_branch_factor = max(stats.max_branch_factor, len(candidates))
        for ch in candidates:
            stats.guesses += 1
            branch = {c: set(d) for c, d in domains.items()}
            branch[cell] = {ch}
            if not self.propagate(branch):
                stats.backtracks += 1
                continue
            if self._search(branch, solutions, find_all, max_solutions, stats):
                return True
        return False

    # ------------------------------------------------------------------
    # public solve
    # ------------------------------------------------------------------
    def solve(
        self,
        find_all: bool = False,
        max_solutions: int = 2,
        collect_stats: bool = True,
    ) -> Tuple[List[Dict[Cell, str]], SolveStats]:
        domains = self._initial_domains()
        stats = SolveStats(edge=self.edge, alphabet_size=len(self.alphabet))
        self._prop_passes = 0
        initial_multi = sum(1 for d in domains.values() if len(d) > 1)
        stats.initial_multi_cells = initial_multi
        stats.max_line_candidates = self._max_line_candidates(domains)

        consistent = self.propagate(domains)
        if collect_stats:
            remaining = [d for d in domains.values() if len(d) > 1]
            stats.forced_cells = initial_multi - len(remaining)
            stats.solved_by_propagation = consistent and not remaining
            stats.residual_cells = len(remaining)
            stats.residual_candidates = sum(len(d) - 1 for d in remaining)
            stats.max_residual_domain = max((len(d) for d in remaining), default=0)

        solutions: List[Dict[Cell, str]] = []
        if consistent:
            self._search(domains, solutions, find_all, max_solutions, stats)
        if collect_stats:
            stats.propagation_passes = self._prop_passes
            report = measure(stats, self.style, self.weights)
            stats.score = report.score
            stats.band = report.band
            stats.components = report.components
        return solutions, stats

    def solve_propagation_only(self) -> Tuple[List[Dict[Cell, str]], SolveStats]:
        """Fast path: run arc consistency and report a solution only if it is
        fully determined by propagation.

        This is what the generator uses for its uniqueness check: it is orders
        of magnitude cheaper than enumerating solutions, and for generated
        puzzles uniqueness and propagation-solvability coincide in practice.
        Puzzles that need search are reported as non-unique here.
        """
        domains = self._initial_domains()
        stats = SolveStats(edge=self.edge, alphabet_size=len(self.alphabet))
        self._prop_passes = 0
        initial_multi = sum(1 for d in domains.values() if len(d) > 1)
        stats.initial_multi_cells = initial_multi
        stats.max_line_candidates = self._max_line_candidates(domains)
        consistent = self.propagate(domains)
        remaining = [d for d in domains.values() if len(d) > 1]
        stats.forced_cells = initial_multi - len(remaining)
        stats.solved_by_propagation = consistent and not remaining
        stats.residual_cells = len(remaining)
        stats.residual_candidates = sum(len(d) - 1 for d in remaining)
        stats.max_residual_domain = max((len(d) for d in remaining), default=0)
        stats.propagation_passes = self._prop_passes
        solution: List[Dict[Cell, str]] = []
        if stats.solved_by_propagation:
            assignment = {c: next(iter(d)) for c, d in domains.items()}
            self.verify_solution(assignment)
            solution.append(assignment)
        report = measure(stats, self.style, self.weights)
        stats.score = report.score
        stats.band = report.band
        stats.components = report.components
        return solution, stats

    def _max_line_candidates(self, domains: Dict[Cell, set]) -> float:
        best = 0.0
        for cells in self.line_cells:
            product = 1.0
            for cell in cells:
                product *= max(1, len(domains[cell]))
                if product > 1e12:
                    break
            best = max(best, product)
        return best

    @staticmethod
    def _score(stats: SolveStats) -> float:  # pragma: no cover - legacy helper
        """Deprecated: superseded by :mod:`hexregex.difficulty`."""
        import math

        score = 0.0
        if not (stats.solved_by_propagation and stats.backtracks == 0):
            score += 40.0
            score += 8.0 * math.log2(1 + stats.backtracks + stats.guesses)
        score += 2.0 * stats.edge
        score += max(0, stats.alphabet_size - 10)
        score += min(20, stats.max_branch_factor)
        return round(score, 2)

    # ------------------------------------------------------------------
    # verification
    # ------------------------------------------------------------------
    def verify_solution(self, solution: Mapping[Cell, str]) -> None:
        """Re-check a complete solution with :func:`re.fullmatch`."""
        for li, line in enumerate(self.geo.lines):
            text = self.clue_of[li]
            word = "".join(solution[cell] for cell in line.cells)
            if re.fullmatch(text, word) is None:
                raise SolverError(
                    "internal error: solution word "
                    f"{word!r} does not match {line.family}[{line.index}] = {text!r}"
                )

    def solution_rows(self, solution: Mapping[Cell, str]) -> List[List[str]]:
        return [
            [solution[(r, c)] for c in range(self.geo.row_size(r))]
            for r in range(self.geo.num_rows)
        ]
