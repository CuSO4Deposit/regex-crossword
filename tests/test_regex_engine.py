"""Correctness of the domain-aware regex engine."""

import itertools
import random
import re
import unittest

from hexregex.regex_engine import (
    RegexSyntaxError,
    compile_pattern,
    feasible,
    line_possible,
)

BACKREF_PATTERNS = [
    r"(..)\1P+",
    r"(...?)\1*",
    r"P+(..)\1",
    r"(.)C\1X\1",
    r"(.)(.)(.)(.)\4\3\2\1",
    r".*(.)(.)(.)(.)\4\3\2\1.*",
    r"(a?){2,}",
    r"(a*)*",
    r"(|a)*",
    r"(.)\1?",
    r"(a|b)*abb",
]


def _singleton_feasible(pattern, word):
    return feasible([frozenset({c}) for c in word], compile_pattern(pattern))


class SingletonTests(unittest.TestCase):
    def test_backref_patterns_against_re(self):
        rng = random.Random(1234)
        alphabet = "abCPX"
        for pattern in BACKREF_PATTERNS:
            for length in range(0, 7):
                for _ in range(120):
                    word = "".join(rng.choice(alphabet) for _ in range(length))
                    expected = re.fullmatch(pattern, word) is not None
                    self.assertEqual(
                        _singleton_feasible(pattern, word),
                        expected,
                        msg=f"{pattern!r} vs {word!r}",
                    )

    def test_required_examples(self):
        cases = [
            (r"(...?)\1*", "ABAB", True),
            (r"(...?)\1*", "AB", True),
            (r"(...?)\1*", "ABCABC", True),
            (r"(.)C\1X\1", "ACAXA", True),
            (r"(.)C\1X\1", "ACBXA", False),
            (r"P+(..)\1", "PPXYXY", True),
            (r"P+(..)\1", "PPXYZZ", False),
            (r"(.)(.)(.)(.)\4\3\2\1", "ABCDDCBA", True),
            (r"(.)(.)(.)(.)\4\3\2\1", "ABCDDBCA", False),
        ]
        for pattern, word, expected in cases:
            self.assertEqual(_singleton_feasible(pattern, word), expected, msg=f"{pattern} {word}")

    def test_unset_group_backref_fails(self):
        self.assertFalse(_singleton_feasible(r"(a)|b\1", "b"))
        self.assertTrue(_singleton_feasible(r"(a)b\1", "aba"))


class NonSingletonTests(unittest.TestCase):
    def test_feasible_matches_brute_force(self):
        patterns = [
            r"(.)(.)(.)(.)\4\3\2\1",
            r"(..)\1P+",
            r"(a|b|c)*",
            r"[abc]{2,3}",
            r"a*b?c*",
            r"(.)\1",
            r"(ab|a)+",
            r"[^a]*a[^a]*",
            r"(a?)(b?)\1\2",
        ]
        alphabet = "abc"
        rng = random.Random(99)
        for pattern in patterns:
            compiled = compile_pattern(pattern)
            for length in range(0, 5):
                for _ in range(120):
                    domains = []
                    for _ in range(length):
                        k = rng.randint(1, 3)
                        domains.append(frozenset(rng.sample(alphabet, k)))
                    expected = False
                    for combo in itertools.product(*[sorted(d) for d in domains]):
                        if re.fullmatch(pattern, "".join(combo)):
                            expected = True
                            break
                    self.assertEqual(
                        feasible(domains, compiled),
                        expected,
                        msg=f"{pattern!r} {domains}",
                    )

    def test_line_possible_accepts_mapping_or_sequence(self):
        pattern = r"(.)(.)\2\1"
        cells = [(0, 0), (0, 1), (0, 2), (0, 3)]
        mapping = {cells[0]: "A", cells[1]: "B", cells[2]: "B", cells[3]: "A"}
        self.assertTrue(line_possible(cells, mapping, pattern))
        self.assertFalse(line_possible(cells, ["A", "B", "C", "A"], pattern))

    def test_empty_string_allowed_when_pattern_allows(self):
        self.assertTrue(feasible([], compile_pattern(r"a*")))
        self.assertFalse(feasible([], compile_pattern(r"a+")))


class SyntaxTests(unittest.TestCase):
    def test_lookahead_rejected(self):
        for pattern in (r"(?=a)", r"(?!a)"):
            with self.assertRaises(RegexSyntaxError):
                compile_pattern(pattern)

    def test_anchors_ignored(self):
        self.assertTrue(_singleton_feasible(r"^abc$", "abc"))


if __name__ == "__main__":
    unittest.main()
