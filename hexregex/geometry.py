"""Hexagonal grid geometry for the regular crossword.

Coordinate system (read this before touching directions!)
---------------------------------------------------------
An *edge-rich* hexagon of edge ``n`` has

* ``size = 2*n - 1`` rows,
* ``3*n*(n-1) + 1`` cells in total,
* ``row_size(r) = n + min(r, size-1-r)`` cells on row ``r``.

Cells are addressed ``(r, c)`` with ``r = 0 .. size-1`` running top to
bottom and ``c = 0 .. row_size(r)-1`` running left to right.

The three line families are mutually at 120 degrees.  Let ``mid = n-1``.

* **Y line r** -- the whole row ``r``, read **left to right** (``c``
  increasing).
* **Z line i** -- collected with ``r`` increasing (top to bottom) using
  ``c = i - max(0, mid - r)``, skipping cells outside the row.
* **X line i** -- collected with ``r`` increasing using
  ``c = i - max(0, r - mid)``, then the whole list is **reversed**, so
  the reading direction is **bottom to top** (``r`` decreasing).

Reverse lookup (cell -> line index)::

    y = r
    x = c + max(0, r - mid)
    z = c + max(0, mid - r)

Every cell belongs to exactly one X, one Y and one Z line, giving
``3 * (2*n - 1)`` lines in total.
"""

from dataclasses import dataclass
from typing import Dict, Iterator, List, Sequence, Tuple

Cell = Tuple[int, int]


@dataclass(frozen=True)
class Line:
    """A single crossword line.

    ``family`` is one of ``"x"``, ``"y"``, ``"z"`` and ``index`` is the
    line index within that family.  ``cells`` is the ordered list of
    ``(r, c)`` cells in *reading direction*.
    """

    family: str
    index: int
    cells: Tuple[Cell, ...]

    @property
    def length(self) -> int:
        return len(self.cells)


class HexGeometry:
    """Geometry helper for a hexagon of a given edge length."""

    kind = "hex"
    families = ("x", "y", "z")

    def __init__(self, edge: int):
        if edge < 1:
            raise ValueError("edge must be >= 1")
        self.edge = edge
        self.size = 2 * edge - 1
        self.mid = edge - 1

        self.x = self._build_x()
        self.y = self._build_y()
        self.z = self._build_z()
        self.lines: List[Line] = []
        for family, table in (("x", self.x), ("y", self.y), ("z", self.z)):
            for index, cells in enumerate(table):
                self.lines.append(Line(family, index, tuple(cells)))

        self._cell_lines: Dict[Cell, List[int]] = {}
        for li, line in enumerate(self.lines):
            for cell in line.cells:
                self._cell_lines.setdefault(cell, []).append(li)

    # ------------------------------------------------------------------
    # basic shape helpers
    # ------------------------------------------------------------------
    def row_size(self, r: int) -> int:
        """Number of cells on row ``r``."""
        return self.edge + min(r, self.size - 1 - r)

    def cells(self) -> Iterator[Cell]:
        """Yield every cell of the grid in row-major order."""
        for r in range(self.size):
            for c in range(self.row_size(r)):
                yield (r, c)

    def cell_count(self) -> int:
        return 3 * self.edge * (self.edge - 1) + 1

    @property
    def num_rows(self) -> int:
        return self.size

    # ------------------------------------------------------------------
    # line construction
    # ------------------------------------------------------------------
    def _build_y(self) -> List[List[Cell]]:
        return [
            [(r, c) for c in range(self.row_size(r))]
            for r in range(self.size)
        ]

    def _build_z(self) -> List[List[Cell]]:
        lines: List[List[Cell]] = []
        for i in range(self.size):
            cells: List[Cell] = []
            for r in range(self.size):
                c = i - max(0, self.mid - r)
                if 0 <= c < self.row_size(r):
                    cells.append((r, c))
            lines.append(cells)
        return lines

    def _build_x(self) -> List[List[Cell]]:
        lines: List[List[Cell]] = []
        for i in range(self.size):
            cells: List[Cell] = []
            for r in range(self.size):
                c = i - max(0, r - self.mid)
                if 0 <= c < self.row_size(r):
                    cells.append((r, c))
            cells.reverse()  # X clues read bottom to top
            lines.append(cells)
        return lines

    # ------------------------------------------------------------------
    # reverse lookup
    # ------------------------------------------------------------------
    def line_indices(self, cell: Cell) -> Tuple[int, int, int]:
        """Return ``(x, y, z)`` line indices for ``cell``."""
        r, c = cell
        x = c + max(0, r - self.mid)
        y = r
        z = c + max(0, self.mid - r)
        return x, y, z

    def lines_for_cell(self, cell: Cell) -> Tuple[Line, Line, Line]:
        """Return the ``(x, y, z)`` :class:`Line` objects for ``cell``."""
        xi, yi, zi = self.line_indices(cell)
        return self.x_line(xi), self.y_line(yi), self.z_line(zi)

    def x_line(self, i: int) -> Line:
        return self.lines[i]

    def y_line(self, i: int) -> Line:
        return self.lines[self.size + i]

    def z_line(self, i: int) -> Line:
        return self.lines[2 * self.size + i]

    def line(self, family: str, index: int) -> Line:
        return {"x": self.x_line, "y": self.y_line, "z": self.z_line}[family](index)

    # ------------------------------------------------------------------
    # validation
    # ------------------------------------------------------------------
    def validate(self) -> None:
        """Assert the structural invariants of the grid."""
        cells = list(self.cells())
        assert len(cells) == self.cell_count(), "cell count mismatch"
        seen: Dict[Cell, set] = {cell: set() for cell in cells}
        for line in self.lines:
            for cell in line.cells:
                seen[cell].add(line.family)
        for cell, fams in seen.items():
            assert fams == {"x", "y", "z"}, f"cell {cell} on {fams}"


class RectGeometry:
    """A plain 2D rectangular regex crossword (rows + columns).

    This is the "not three-dimensional" easy form: every cell belongs to one
    across line (``y``, read left to right) and one down line (``x``, read top
    to bottom).  It exposes the same interface as :class:`HexGeometry` so the
    solver, generator and renderer work unchanged.
    """

    kind = "rect"
    families = ("x", "y")

    def __init__(self, rows: int, cols: int):
        if rows < 1 or cols < 1:
            raise ValueError("rows and cols must be >= 1")
        self.rows = rows
        self.cols = cols
        self.mid = rows // 2
        self.x = [[(r, c) for r in range(rows)] for c in range(cols)]
        self.y = [[(r, c) for c in range(cols)] for r in range(rows)]
        self.lines: List[Line] = []
        for index, cells in enumerate(self.y):
            self.lines.append(Line("y", index, tuple(cells)))
        for index, cells in enumerate(self.x):
            self.lines.append(Line("x", index, tuple(cells)))
        self._cell_lines: Dict[Cell, List[int]] = {}
        for li, line in enumerate(self.lines):
            for cell in line.cells:
                self._cell_lines.setdefault(cell, []).append(li)

    @property
    def num_rows(self) -> int:
        return self.rows

    def row_size(self, r: int) -> int:
        return self.cols

    def cells(self) -> Iterator[Cell]:
        for r in range(self.rows):
            for c in range(self.cols):
                yield (r, c)

    def cell_count(self) -> int:
        return self.rows * self.cols

    def line(self, family: str, index: int) -> Line:
        table = {"x": self.x, "y": self.y}
        return Line(family, index, tuple(table[family][index]))

    def validate(self) -> None:
        cells = list(self.cells())
        assert len(cells) == self.cell_count(), "cell count mismatch"
        seen: Dict[Cell, set] = {cell: set() for cell in cells}
        for line in self.lines:
            for cell in line.cells:
                seen[cell].add(line.family)
        for cell, fams in seen.items():
            assert fams == {"x", "y"}, f"cell {cell} on {fams}"
