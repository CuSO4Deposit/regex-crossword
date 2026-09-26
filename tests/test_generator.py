"""Generator tests: soundness, reproducibility and difficulty control."""

import re
import time
import unittest

from hexregex.generator import GenConfig, GenerationError, generate
from hexregex.geometry import HexGeometry
from hexregex.solver import Solver


def _solve(puzzle, alphabet):
    solver = Solver.from_puzzle(puzzle, alphabet=alphabet)
    solutions, stats = solver.solve(find_all=True, max_solutions=2)
    return solver, solutions, stats


def _geometry(puzzle):
    if puzzle.get("kind", "hex") == "rect":
        from hexregex.geometry import RectGeometry

        return RectGeometry(int(puzzle["rows"]), int(puzzle["cols"]))
    return HexGeometry(int(puzzle["edge"]))


def _literal_lines(puzzle):
    """Lines whose clue is still the exact literal string of the solution."""
    geo = _geometry(puzzle)
    solution = {
        (r, c): ch
        for r, row in enumerate(puzzle["solution"])
        for c, ch in enumerate(row)
    }
    out = []
    for line in geo.lines:
        word = "".join(solution[cell] for cell in line.cells)
        if puzzle[line.family][line.index] == re.escape(word):
            out.append((line.family, line.index))
    return out


class GeneratorTests(unittest.TestCase):
    def test_unique_easy_edge3(self):
        cfg = GenConfig(edge=3, alphabet="ABCDEF", difficulty="easy", seed=1, unique=True)
        puzzle, report = generate(cfg)
        solver, solutions, stats = _solve(puzzle, cfg.alphabet)
        self.assertEqual(len(solutions), 1)
        self.assertTrue(stats.solved_by_propagation)
        # Every emitted clue must match the emitted solution.
        solver.verify_solution(solutions[0])

    def test_reproducible_with_seed(self):
        cfg = GenConfig(edge=3, alphabet="ABCDEF", difficulty="easy", seed=42, unique=True)
        puzzle_a, _ = generate(cfg)
        puzzle_b, _ = generate(cfg)
        self.assertEqual(puzzle_a, puzzle_b)

    def test_different_seeds_differ(self):
        cfg = GenConfig(edge=3, alphabet="ABCDEF", difficulty="easy", seed=1, unique=True)
        puzzle_a, _ = generate(cfg)
        cfg.seed = 2
        puzzle_b, _ = generate(cfg)
        self.assertNotEqual(puzzle_a["solution"], puzzle_b["solution"])

    def test_unique_edge5_runs_quickly(self):
        cfg = GenConfig(
            edge=5,
            alphabet="CDEHIMNORST",
            difficulty="easy",
            seed=5,
            unique=True,
            loosen=80,
        )
        start = time.time()
        puzzle, _report = generate(cfg)
        self.assertLess(time.time() - start, 60.0)
        solver, solutions, stats = _solve(puzzle, cfg.alphabet)
        self.assertEqual(len(solutions), 1)
        self.assertEqual(stats.difficulty(), "easy")

    def test_message_is_hidden(self):
        cfg = GenConfig(
            edge=5,
            alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ",
            difficulty="easy",
            seed=9,
            unique=False,
            message="HELLO",
        )
        puzzle, _report = generate(cfg)
        rows = puzzle["solution"]
        joined = "".join("".join(row) for row in rows)
        self.assertIn("HELLO", joined)

    def test_easy_vs_hard(self):
        easy = GenConfig(edge=3, alphabet="ABC", difficulty="easy", seed=3, unique=True)
        puzzle_easy, _ = generate(easy)
        _solver_e, _sol_e, stats_easy = _solve(puzzle_easy, easy.alphabet)
        self.assertEqual(stats_easy.difficulty(), "easy")
        self.assertTrue(stats_easy.solved_by_propagation)

        hard = GenConfig(
            edge=3,
            alphabet="ABC",
            difficulty="hard",
            seed=3,
            unique=False,
            loosen=250,
            allow_backref=True,
        )
        puzzle_hard, _ = generate(hard)
        _solver_h, _sol_h, stats_hard = _solve(puzzle_hard, hard.alphabet)
        self.assertEqual(stats_hard.difficulty(), "hard")
        self.assertGreater(stats_hard.score, stats_easy.score)
        self.assertGreater(stats_hard.residual_candidates, stats_easy.residual_candidates)

    def test_medium_and_hard_have_no_literal_rows(self):
        for difficulty in ("medium", "hard"):
            cfg = GenConfig(
                edge=3,
                alphabet="ABC",
                difficulty=difficulty,
                seed=3,
                unique=False,
                loosen=250,
                allow_backref=True,
            )
            puzzle, _ = generate(cfg)
            self.assertEqual(
                _literal_lines(puzzle),
                [],
                msg=f"{difficulty} puzzle kept a fully literal clue",
            )

    def test_chunk_alt_quota(self):
        for seed in range(1, 6):
            cfg = GenConfig(
                edge=5,
                alphabet="CDEHIMNORST",
                difficulty="hard",
                seed=seed,
                unique=True,
                allow_backref=True,
                max_chunk_alts=2,
                loosen=600,
                max_attempts=4,
            )
            _puzzle, report = generate(cfg)
            used = report["op_usage"].get("shape_alt_star", 0)
            self.assertLessEqual(used, 2)

    def test_chunk_alt_quota_zero(self):
        cfg = GenConfig(
            edge=5,
            alphabet="CDEHIMNORST",
            difficulty="hard",
            seed=2,
            unique=True,
            allow_backref=True,
            max_chunk_alts=0,
            loosen=600,
            max_attempts=4,
        )
        _puzzle, report = generate(cfg)
        self.assertEqual(report["op_usage"].get("shape_alt_star", 0), 0)

    def test_constructive_no_unique_is_instant_and_sound(self):
        import time

        cfg = GenConfig(
            edge=5,
            alphabet="CDEHIMNORST",
            difficulty="hard",
            seed=1,
            unique=False,
            allow_backref=True,
        )
        start = time.time()
        puzzle, report = generate(cfg)
        self.assertLess(time.time() - start, 2.0)
        self.assertEqual(report.get("mode"), "constructive")
        self.assertEqual(report["difficulty"], "hard")
        # Every clue must match the emitted solution (line-by-line validation).
        solver = Solver.from_puzzle(puzzle, alphabet=cfg.alphabet)
        solution = {
            (r, c): ch
            for r, row in enumerate(puzzle["solution"])
            for c, ch in enumerate(row)
        }
        for line in solver.geo.lines:
            word = "".join(solution[cell] for cell in line.cells)
            clue = puzzle[line.family][line.index]
            self.assertIsNotNone(re.fullmatch(clue, word), msg=f"{line.family}[{line.index}]")

    def test_templates_are_still_sound(self):
        cfg = GenConfig(
            edge=3,
            alphabet="ABCDEF",
            difficulty="easy",
            seed=11,
            unique=True,
            templates=True,
        )
        puzzle, _ = generate(cfg)
        solver, solutions, _stats = _solve(puzzle, cfg.alphabet)
        self.assertEqual(len(solutions), 1)
        solver.verify_solution(solutions[0])


class GenerationErrorTests(unittest.TestCase):
    def test_impossible_window_raises(self):
        # No puzzle can score above 100, so this window is unreachable and the
        # generator must report rather than loop forever.
        cfg = GenConfig(
            edge=3,
            alphabet="ABC",
            difficulty="easy",
            seed=1,
            min_score=200,
            max_score=300,
            max_attempts=2,
            loosen=30,
        )
        with self.assertRaises(GenerationError):
            generate(cfg)


if __name__ == "__main__":
    unittest.main()
