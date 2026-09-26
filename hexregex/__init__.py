"""Hexagonal regular crossword solver and generator."""

from .difficulty import (
    DEFAULT_WEIGHTS,
    DifficultyReport,
    DifficultyWeights,
    clue_style,
    measure,
)
from .geometry import HexGeometry, Line
from .regex_engine import RegexSyntaxError, compile_pattern, feasible, line_possible
from .solver import SolveStats, Solver, SolverError

__all__ = [
    "HexGeometry",
    "Line",
    "RegexSyntaxError",
    "compile_pattern",
    "feasible",
    "line_possible",
    "SolveStats",
    "Solver",
    "SolverError",
    "DifficultyReport",
    "DifficultyWeights",
    "DEFAULT_WEIGHTS",
    "clue_style",
    "measure",
    "__version__",
]

__version__ = "0.5.0"
