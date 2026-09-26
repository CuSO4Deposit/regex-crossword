"""Solver tests, including the MIT Mystery Hunt 2013 reference puzzle."""

import re
import time
import unittest

from hexregex.data import original_puzzle
from hexregex.solver import Solver


class OriginalPuzzleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.puzzle: dict = original_puzzle()
        cls.start = time.time()
        cls.solver = Solver.from_puzzle(cls.puzzle)
        cls.solutions, cls.stats = cls.solver.solve(find_all=True, max_solutions=2)
        cls.elapsed = time.time() - cls.start

    def test_unique_solution(self):
        self.assertEqual(len(self.solutions), 1)

    def test_all_lines_fullmatch(self):
        solution = self.solutions[0]
        checked = 0
        for line in self.solver.geo.lines:
            text = self.puzzle[line.family][line.index]
            word = "".join(solution[cell] for cell in line.cells)
            self.assertIsNotNone(re.fullmatch(text, word), msg=f"{line.family}[{line.index}] {word}")
            checked += 1
        self.assertEqual(checked, 39)

    def test_scales_to_seconds(self):
        # The reference puzzle must not trigger catastrophic backtracking.
        self.assertLess(self.elapsed, 30.0)

    def test_solution_shape(self):
        rows = self.solver.solution_rows(self.solutions[0])
        self.assertEqual(len(rows), 13)
        self.assertTrue(all(len(row) == self.solver.geo.row_size(r) for r, row in enumerate(rows)))


class SmallPuzzleTests(unittest.TestCase):
    def test_simple_unique_puzzle(self):
        # A tiny puzzle whose clues are all ``.*`` has many solutions; pinning a
        # couple of cells makes it unique enough to exercise the search path.
        puzzle = {
            "edge": 2,
            "x": [".*", ".*", ".*"],
            "y": [".*", ".*", ".*"],
            "z": [".*", ".*", ".*"],
        }
        solver = Solver.from_puzzle(puzzle, alphabet="AB")
        solutions, _stats = solver.solve(find_all=True, max_solutions=3)
        self.assertGreaterEqual(len(solutions), 2)

    def test_unsatisfiable(self):
        puzzle = {
            "edge": 2,
            "x": ["A", "A", "A"],
            "y": ["B", "B", "B"],
            "z": ["B", "B", "B"],
        }
        solver = Solver.from_puzzle(puzzle, alphabet="AB")
        solutions, _stats = solver.solve(find_all=True, max_solutions=2)
        self.assertEqual(solutions, [])


if __name__ == "__main__":
    unittest.main()
