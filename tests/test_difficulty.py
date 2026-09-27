"""Difficulty measurement tests (human-oriented anchors)."""

import unittest
from types import SimpleNamespace

from hexregex.difficulty import clue_style, measure


def _stats(**overrides):
    base = dict(
        edge=5,
        alphabet_size=11,
        residual_candidates=0,
        nodes=1,
        backtracks=0,
        forced_cells=0,
        initial_multi_cells=1,
        solved_by_propagation=True,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def _style(literal_ratio=1.0, dimension=3):
    return {"literal_ratio": literal_ratio, "dimension": dimension}


class ClueStyleTests(unittest.TestCase):
    def test_literal_clues_have_full_literal_ratio(self):
        clues = {"x": ["ABC", "AB"], "y": ["A"], "z": ["BA", "C"]}
        style = clue_style(clues)
        self.assertEqual(style["literal_ratio"], 1.0)
        self.assertEqual(style["wildcard_ratio"], 0.0)
        self.assertEqual(style["dimension"], 3)

    def test_2d_families(self):
        clues = {"x": ["A"], "y": ["B"]}
        self.assertEqual(clue_style(clues)["dimension"], 2)

    def test_wildcards_reduce_literal_ratio(self):
        clues = {"x": [".B."], "y": ["A|B"], "z": ["AB"]}
        style = clue_style(clues)
        self.assertLess(style["literal_ratio"], 1.0)
        self.assertGreater(style["wildcard_ratio"], 0.0)


class ScoreTests(unittest.TestCase):
    def test_score_increases_with_residual(self):
        scores = [
            measure(_stats(residual_candidates=r), _style(0.5)).score
            for r in (0, 5, 25, 100, 400)
        ]
        self.assertEqual(scores, sorted(scores))
        self.assertGreater(scores[-1], scores[0])

    def test_human_bands(self):
        # Literal 2D grid -> easy.
        self.assertEqual(
            measure(_stats(edge=3, alphabet_size=6), _style(1.0, dimension=2)).band,
            "easy",
        )
        # A fully literal 3D hexagon is still easy (you can read each word off).
        self.assertEqual(
            measure(_stats(edge=5, alphabet_size=11), _style(1.0, dimension=3)).band,
            "easy",
        )
        # 3D with real clue opacity -> medium.
        self.assertEqual(
            measure(_stats(edge=5, alphabet_size=11), _style(0.5, dimension=3)).band,
            "medium",
        )
        # Large, very opaque 3D puzzle -> hard.
        self.assertEqual(
            measure(
                _stats(edge=7, alphabet_size=26), _style(0.4, dimension=3)
            ).band,
            "hard",
        )

    def test_dimension_matters(self):
        flat = measure(_stats(edge=5, alphabet_size=11), _style(1.0, dimension=2))
        hexa = measure(_stats(edge=5, alphabet_size=11), _style(1.0, dimension=3))
        self.assertLess(flat.score, hexa.score)

    def test_components_reported(self):
        report = measure(
            _stats(residual_candidates=10, backtracks=3, nodes=5),
            _style(0.2, dimension=3),
        )
        for key in ("dimension", "opacity", "search", "alphabet", "size", "raw"):
            self.assertIn(key, report.components)
        self.assertGreater(report.components["opacity"], 0.0)


if __name__ == "__main__":
    unittest.main()
