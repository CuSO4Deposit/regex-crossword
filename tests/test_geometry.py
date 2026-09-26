"""Geometry invariants for the hexagonal grid."""

import unittest
from collections import Counter

from hexregex.geometry import HexGeometry


class GeometryTests(unittest.TestCase):
    def test_cell_count(self):
        for n in range(1, 9):
            geo = HexGeometry(n)
            self.assertEqual(len(list(geo.cells())), 3 * n * (n - 1) + 1)
            if n == 7:
                self.assertEqual(len(list(geo.cells())), 127)

    def test_partition(self):
        for n in range(1, 9):
            geo = HexGeometry(n)
            seen = {cell: set() for cell in geo.cells()}
            for line in geo.lines:
                for cell in line.cells:
                    seen[cell].add(line.family)
            for cell, families in seen.items():
                self.assertEqual(families, {"x", "y", "z"}, msg=f"n={n} cell={cell}")

    def test_line_length_multiset(self):
        expected = Counter([7, 8, 9, 10, 11, 12, 13, 12, 11, 10, 9, 8, 7])
        geo = HexGeometry(7)
        for family in (geo.x, geo.y, geo.z):
            self.assertEqual(Counter(len(line) for line in family), expected)

    def test_reverse_lookup_matches_forward(self):
        for n in range(1, 9):
            geo = HexGeometry(n)
            for line in geo.lines:
                for cell in line.cells:
                    x, y, z = geo.line_indices(cell)
                    self.assertIn(cell, geo.line("x", x).cells)
                    self.assertIn(cell, geo.line("y", y).cells)
                    self.assertIn(cell, geo.line("z", z).cells)

    def test_x_reads_bottom_up(self):
        geo = HexGeometry(4)
        # The X line through the top cell must list rows in descending order.
        for cells in geo.x:
            rows = [r for r, _c in cells]
            self.assertEqual(rows, sorted(rows, reverse=True))

    def test_y_reads_left_to_right(self):
        geo = HexGeometry(4)
        for cells in geo.y:
            cols = [c for _r, c in cells]
            self.assertEqual(cols, sorted(cols))

    def test_z_reads_top_down(self):
        geo = HexGeometry(4)
        for cells in geo.z:
            rows = [r for r, _c in cells]
            self.assertEqual(rows, sorted(rows))

    def test_validate(self):
        HexGeometry(7).validate()


if __name__ == "__main__":
    unittest.main()
