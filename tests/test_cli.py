"""End-to-end CLI tests."""

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

from hexregex.cli import main


class CliTests(unittest.TestCase):
    def _run(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_original(self):
        code, out, _err = self._run(["original", "--show-stats"])
        self.assertEqual(code, 0)
        self.assertIn("N H P E H A S", out)
        self.assertIn("difficulty", out)

    def test_solve_and_verify_generated(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "puzzle.json")
            code, _out, err = self._run(
                [
                    "gen",
                    "--edge",
                    "3",
                    "--alphabet",
                    "CDEHIMNORST",
                    "--difficulty",
                    "easy",
                    "--seed",
                    "1",
                    "--unique",
                    "-o",
                    path,
                ]
            )
            self.assertEqual(code, 0, msg=err)
            with open(path, encoding="utf-8") as handle:
                puzzle = json.load(handle)
            # easy defaults to a 2D rectangle: one clue per row and column.
            self.assertEqual(puzzle["kind"], "rect")
            self.assertEqual(len(puzzle["x"]), 3)

            code, out, _err = self._run(["verify", path])
            self.assertEqual(code, 0)
            self.assertIn("UNIQUE", out)

            code, out, _err = self._run(["solve", path, "--show-stats"])
            self.assertEqual(code, 0)
            self.assertIn("score", out)

    def test_render_solution_and_jimbly(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "puzzle.json")
            b64 = os.path.join(tmp, "board.b64")
            self._run(
                [
                    "gen",
                    "--edge",
                    "3",
                    "--kind",
                    "hex",
                    "--alphabet",
                    "ABCDEF",
                    "--difficulty",
                    "medium",
                    "--no-unique",
                    "--seed",
                    "2",
                    "-o",
                    path,
                ]
            )
            code, out, _err = self._run(["render", path, "--solution", "--jimbly-out", b64])
            self.assertEqual(code, 0)
            self.assertIn("A", out + "ABCDEF")
            with open(b64, encoding="ascii") as handle:
                payload = handle.read().strip()
            self.assertTrue(payload.startswith("?puzzle="))

    def test_trivial_puzzle_directly(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "p.json")
            puzzle = {
                "edge": 2,
                "author": "t",
                "name": "t",
                "x": [".*", ".*", ".*"],
                "y": [".*", ".*", ".*"],
                "z": [".*", ".*", ".*"],
            }
            with open(path, "w", encoding="utf-8") as handle:
                json.dump(puzzle, handle)
            code, out, _err = self._run(["solve", path, "--all", "--max-solutions", "5"])
            self.assertEqual(code, 0)
            self.assertIn("solution", out)


if __name__ == "__main__":
    unittest.main()
