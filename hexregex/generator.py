"""Generator for hexagonal regular crosswords.

The generator is deliberately *generate-and-test*:

1. Pick a true solution grid (random, or with a hidden ``message`` written
   into one line).
2. Start with the most restrictive possible clue for every line: its literal
   text, escaped with :func:`re.escape`.
3. Repeatedly apply a random *relaxation* operator to a random line.  An
   operator only has to keep the true text matching (checked with
   :func:`re.fullmatch`); it is kept only if the puzzle stays unique (when
   requested) and if it moves the solver statistics toward the requested
   difficulty.
4. Refuse to loop forever: after ``max_attempts`` failures it raises
   :class:`GenerationError` together with the closest statistics seen.

Because every accepted clue still matches the pre-selected solution, the
puzzle is always satisfiable; uniqueness and difficulty come from the solver.
"""

from __future__ import annotations

import math
import random
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Sequence, Tuple

from .difficulty import DEFAULT_WEIGHTS, clue_style
from .geometry import HexGeometry
from .solver import SolveStats, Solver

LineKey = Tuple[str, int]

#: Human-friendly templates in the style of the MIT original.  Each is tried
#: against a line's true text and adopted when it matches.
TEMPLATES: Tuple[str, ...] = (
    r".*H.*H.*",
    r"(DI|NS|TH|OM)*",
    r"[^C]*[^R]*III.*",
    r"F.*[AO].*[AO].*",
    r"(...?)\1*",
    r"[CHMNOR]*I[CHMNOR]*",
    r"P+(..)\1.*",
    r".*MCC.*DD.*",
    r"(.)(.)(.)(.)\4\3\2\1",
    r"(.)C\1X\1",
    r"[^M]*M[^M]*",
    r"[RC]*",
    r"(S|MM|HHH)*",
    r".*X.*RCHX.*",
)

DIFFICULTIES = ("easy", "medium", "hard")


class GenerationError(RuntimeError):
    """Raised when no puzzle matching the requested difficulty is found."""


@dataclass
class GenConfig:
    edge: int = 7
    alphabet: str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    difficulty: str = "medium"
    #: "hex" (3D), "rect" (2D, ``edge`` rows/cols) or "auto"
    #: (easy -> rect, medium/hard -> hex)
    kind: str = "auto"
    seed: int = 0
    unique: bool = True
    message: Optional[str] = None
    templates: bool = False
    allow_backref: bool = False
    loosen: int = 250
    literal_ratio: Optional[float] = None
    require_all_relaxed: bool = True
    #: at most this many multi-character chunk alternations ``(O|RHH|MM)*``
    #: per puzzle (they are a garnish, not the main motif)
    max_chunk_alts: int = 2
    #: optional explicit score window (overrides the difficulty-band bounds)
    min_score: Optional[float] = None
    max_score: Optional[float] = None
    #: aim for this score (defaults to the middle of a band / window, or 30%
    #: into the hard band); generation stops as soon as it is reached
    target_score: Optional[float] = None
    #: reject a puzzle whose literal fraction is above this (None = derive a
    #: default per difficulty: easy none, medium 0.6, hard none)
    max_literal_fraction: Optional[float] = None
    #: use the expensive full uniqueness check instead of propagation-only
    full_unique: bool = False
    #: fast constructive mode: build a relaxation ladder without solving, then
    #: probe it (much fewer solves -> viable for on-device generation)
    fast: bool = False
    fast_steps: int = 400
    fast_probes: int = 100
    max_attempts: int = 8
    op_tries: int = 12
    easy_max_steps: int = 40
    author: str = "generated"
    name: str = "generated"


@dataclass
class Frag:
    """One clue fragment.  ``lo``/``hi`` record the true-text span it covers."""

    body: str
    lo: int
    hi: int
    single: bool
    kind: str = "plain"
    suffix: str = ""


def render_tokens(tokens: Sequence[Frag]) -> str:
    """Render clue fragments to a regex.

    Backreference fragments get their capturing-group number assigned here, in
    left-to-right order, so removing or inserting fragments elsewhere never
    leaves a dangling ``\\1``.
    """
    out: List[str] = []
    group = 0
    for tok in tokens:
        if tok.kind == "backref":
            group += 1
            suffix = tok.suffix.replace("\\#", "\\" + str(group))
            out.append("(" + tok.body + ")" + suffix)
        else:
            out.append(tok.body)
    return "".join(out)


def literal_tokens(text: str) -> List[Frag]:
    """The most restrictive clue for ``text``: every character escaped."""
    return [
        Frag(body=re.escape(ch), lo=i, hi=i + 1, single=True)
        for i, ch in enumerate(text)
    ]


def _render_plain(tokens: Sequence[Frag]) -> str:
    return "".join(t.body for t in tokens if t.kind == "plain")


def _has_backref(tokens: Sequence[Frag]) -> bool:
    return any(t.kind == "backref" for t in tokens)


def _smallest_period(text: str) -> Optional[int]:
    n = len(text)
    for p in range(1, n):
        if n % p == 0 and text == text[:p] * (n // p):
            return p
    return None


# ----------------------------------------------------------------------
# operators
# ----------------------------------------------------------------------
def _op_wildcard(tokens, text, rng, cfg):
    singles = [i for i, t in enumerate(tokens) if t.single and t.hi - t.lo == 1]
    if not singles:
        return None
    i = rng.choice(singles)
    out = list(tokens)
    out[i] = Frag(".", out[i].lo, out[i].hi, True)
    return out


def _op_class(tokens, text, rng, cfg):
    singles = [i for i, t in enumerate(tokens) if t.single and t.hi - t.lo == 1]
    if not singles:
        return None
    i = rng.choice(singles)
    true_ch = text[tokens[i].lo]
    extra = rng.sample(
        [c for c in cfg.alphabet if c != true_ch], k=min(3, len(cfg.alphabet) - 1)
    )
    body = "[" + "".join(re.escape(c) for c in sorted(set([true_ch] + extra))) + "]"
    out = list(tokens)
    out[i] = Frag(body, out[i].lo, out[i].hi, True)
    return out


def _op_negclass(tokens, text, rng, cfg):
    singles = [i for i, t in enumerate(tokens) if t.single and t.hi - t.lo == 1]
    others = [c for c in cfg.alphabet if c != text[tokens[singles[0]].lo]] if singles else []
    if not singles or not others:
        return None
    i = rng.choice(singles)
    true_ch = text[tokens[i].lo]
    x = rng.choice([c for c in cfg.alphabet if c != true_ch])
    out = list(tokens)
    out[i] = Frag("[^" + re.escape(x) + "]", out[i].lo, out[i].hi, True)
    return out


def _random_run(tokens, rng, allow_backref=False):
    n = len(tokens)
    if n == 0:
        return None
    i = rng.randrange(n)
    j = rng.randrange(i, n)
    run = tokens[i : j + 1]
    if _has_backref(run) and not allow_backref:
        return None
    return i, j, run


def _op_dotstar(tokens, text, rng, cfg):
    run = _random_run(tokens, rng, allow_backref=True)
    if run is None:
        return None
    i, j, _ = run
    out = list(tokens)
    out[i : j + 1] = [Frag(".*", tokens[i].lo, tokens[j].hi, False)]
    return out


def _op_optional(tokens, text, rng, cfg):
    run = _random_run(tokens, rng, allow_backref=False)
    if run is None:
        return None
    i, j, group = run
    # Don't wrap an already-loose single fragment again (avoids (?:(?:...)?)?).
    if len(group) == 1 and not group[0].single:
        return None
    body = "(?:" + _render_plain(group) + ")?"
    out = list(tokens)
    out[i : j + 1] = [Frag(body, tokens[i].lo, tokens[j].hi, False)]
    return out


def _op_repeat(tokens, text, rng, cfg):
    run = _random_run(tokens, rng, allow_backref=False)
    if run is None:
        return None
    i, j, group = run
    true = text[tokens[i].lo : tokens[j].hi]
    if len(true) < 2:
        return None
    p = _smallest_period(true)
    if p is None:
        return None
    body = "(?:" + re.escape(true[:p]) + ")+"
    out = list(tokens)
    out[i : j + 1] = [Frag(body, tokens[i].lo, tokens[j].hi, False)]
    return out


def _op_repeat_backref(tokens, text, rng, cfg):
    run = _random_run(tokens, rng, allow_backref=False)
    if run is None:
        return None
    i, j, group = run
    true = text[tokens[i].lo : tokens[j].hi]
    if len(true) < 2:
        return None
    p = _smallest_period(true)
    if p is None:
        return None
    out = list(tokens)
    out[i : j + 1] = [
        Frag(re.escape(true[:p]), tokens[i].lo, tokens[j].hi, False, "backref", "\\#*")
    ]
    return out


def _op_altstar(tokens, text, rng, cfg):
    singles = [i for i, t in enumerate(tokens) if t.single and t.hi - t.lo == 1]
    if not singles:
        return None
    i = rng.choice(singles)
    true_ch = text[tokens[i].lo]
    extra = rng.sample(cfg.alphabet, k=min(3, len(cfg.alphabet)))
    body = "(?:" + "|".join(re.escape(c) for c in [true_ch] + extra) + ")*"
    out = list(tokens)
    out[i] = Frag(body, out[i].lo, out[i].hi, False)
    return out


def _op_contains(tokens, text, rng, cfg):
    """Replace a span with ``.*c.*`` for a character ``c`` it contains."""
    run = _random_run(tokens, rng, allow_backref=True)
    if run is None:
        return None
    i, j, _ = run
    true = text[tokens[i].lo : tokens[j].hi]
    if not true:
        return None
    c = rng.choice(true)
    body = ".*" + re.escape(c) + ".*"
    out = list(tokens)
    out[i : j + 1] = [Frag(body, tokens[i].lo, tokens[j].hi, False)]
    return out


# ----------------------------------------------------------------------
# MIT-style "shapers": whole-line clue generators in the style of the
# original puzzle (meaningful classes, fixed-length skeletons, alternation
# of fragments, negated classes, literal skeletons with gaps).
# ----------------------------------------------------------------------
def _shape_class_word(text, alphabet, rng, cfg):
    """``[SET]*c[SET]*`` -- e.g. ``[RONMHC]*I[RONMHC]*``."""
    chars = set(text)
    pool = [c for c in alphabet if c not in chars]
    rng.shuffle(pool)
    extra = pool[: rng.randint(0, min(6, len(pool)))] if pool else []
    items = sorted(chars | set(extra))
    cls = "[" + "".join(re.escape(c) for c in items) + "]"
    return [Frag(cls + "*" + re.escape(rng.choice(text)) + cls + "*", 0, len(text), False)]


def _shape_class_star(text, alphabet, rng, cfg):
    """``[SET]*`` -- e.g. ``[RC]*`` / ``[ROMEA]*HO[UMIEC]*``."""
    chars = set(text)
    pool = [c for c in alphabet if c not in chars]
    rng.shuffle(pool)
    extra = pool[: rng.randint(0, min(6, len(pool)))] if pool else []
    items = sorted(chars | set(extra))
    cls = "[" + "".join(re.escape(c) for c in items) + "]"
    return [Frag(cls + "*", 0, len(text), False)]


def _shape_literal_skel(text, alphabet, rng, cfg):
    """``.*c1.*c2.*...`` -- e.g. ``.*CDD.*RRP.*``."""
    n = len(text)
    if n < 2:
        return None
    k = rng.randint(2, min(4, n))
    positions = sorted(rng.sample(range(n), k))
    body = "".join(".*" + re.escape(text[p]) for p in positions) + ".*"
    return [Frag(body, 0, n, False)]


def _shape_dot_skeleton(text, alphabet, rng, cfg):
    """Fixed-length skeleton with literals, ``.`` and small classes."""
    out = []
    for ch in text:
        roll = rng.random()
        if roll < 0.5:
            out.append(re.escape(ch))
        elif roll < 0.85:
            out.append(".")
        else:
            others = [c for c in alphabet if c != ch]
            extra = rng.sample(others, k=min(2, len(others))) if others else []
            out.append("[" + "".join(re.escape(c) for c in sorted(set([ch] + extra))) + "]")
    if "".join(out) == re.escape(text):
        return None
    return [Frag("".join(out), 0, len(text), False)]


def _shape_negclass_word(text, alphabet, rng, cfg):
    """``[^x]*c[^x]*`` -- e.g. ``[^C]*MMM[^C]*``."""
    outside = [c for c in alphabet if c not in set(text)]
    if not outside:
        return None
    x = rng.choice(outside)
    c = rng.choice(text)
    body = "[^" + re.escape(x) + "]*" + re.escape(c) + "[^" + re.escape(x) + "]*"
    return [Frag(body, 0, len(text), False)]


def _shape_alt_star(text, alphabet, rng, cfg):
    """``(t1|t2|...)*`` where the pieces tile the true text.

    Tries random tilings (piece lengths 1-3, biased to multi-char) and keeps
    the one with the fewest distinct pieces, so the result looks like MIT's
    ``(O|RHH|MM)*`` / ``(DI|NS|TH|OM)*`` rather than ``(?:T|E|I|H)*``.
    """
    n = len(text)
    if n < 2:
        return None
    best = None
    for _ in range(160):
        pieces = []
        i = 0
        while i < n:
            length = min(rng.choice((2, 2, 3, 3, 1)), n - i)
            pieces.append(text[i : i + length])
            i += length
        distinct = set(pieces)
        multi = sum(1 for p in distinct if len(p) >= 2)
        key = (len(distinct), -multi)
        if best is None or key < best[0]:
            best = (key, distinct)
    if best is None:  # pragma: no cover
        return None
    _key, distinct = best
    if len(distinct) > 6 or not any(len(p) >= 2 for p in distinct):
        return None
    ordered = sorted(distinct, key=lambda p: (-len(p), p))
    body = "(?:" + "|".join(re.escape(p) for p in ordered) + ")*"
    return [Frag(body, 0, n, False)]


def _shape_repeat_block(text, alphabet, rng, cfg):
    """``prefix + (block)+`` / ``(block)\\1*`` -- e.g. ``(...?)\\1*``."""
    n = len(text)
    for i in range(n):
        for bl in range(1, (n - i) // 2 + 1):
            if (n - i) % bl != 0:
                continue
            block = text[i : i + bl]
            if text[i:] == block * ((n - i) // bl):
                prefix = re.escape(text[:i]) if i else ""
                suffix = "\\#*" if rng.random() < 0.5 else "\\#+"
                toks = []
                if prefix:
                    toks.append(Frag(prefix, 0, i, False))
                toks.append(Frag(block, i, n, False, "backref", suffix))
                return toks
    return None


_OPERATORS = {
    "wildcard": (_op_wildcard, 2.0),
    "class": (_op_class, 2.0),
    "negclass": (_op_negclass, 1.5),
    "dotstar": (_op_dotstar, 1.5),
    "optional": (_op_optional, 1.5),
    "repeat": (_op_repeat, 1.5),
    "altstar": (_op_altstar, 1.5),
    "contains": (_op_contains, 1.0),
    "repeat_backref": (_op_repeat_backref, 2.0),
}

_SHAPERS = {
    "shape_class_word": (_shape_class_word, 4.0),
    "shape_class_star": (_shape_class_star, 2.5),
    "shape_literal_skel": (_shape_literal_skel, 3.5),
    "shape_dot_skeleton": (_shape_dot_skeleton, 3.5),
    "shape_negclass_word": (_shape_negclass_word, 2.5),
    "shape_alt_star": (_shape_alt_star, 2.5),
    "shape_repeat_block": (_shape_repeat_block, 3.0),
}


def _operator_weight(name: str) -> float:
    if name in _SHAPERS:
        return _SHAPERS[name][1]
    return _OPERATORS[name][1]


def _choose_operator(rng, cfg, usage=None, alt_quota=None, light_set=False) -> str:
    """Pick an operator with diversity weighting (rarely used ones favoured)."""
    usage = usage or {}
    if light_set:
        names = ["wildcard", "class", "negclass", "optional", "repeat"]
        return rng.choices(names, weights=[1.0 / (1.0 + usage.get(n, 0)) for n in names], k=1)[0]
    names = [n for n in _OPERATORS if n != "repeat_backref"]
    if cfg.allow_backref:
        names.append("repeat_backref")
    names.extend(_SHAPERS)
    quota = cfg.max_chunk_alts if alt_quota is None else alt_quota
    names = [
        n
        for n in names
        if not (n == "shape_alt_star" and usage.get(n, 0) >= quota)
    ]
    weights = [_operator_weight(n) / (1.0 + usage.get(n, 0)) for n in names]
    return rng.choices(names, weights=weights, k=1)[0]


# ----------------------------------------------------------------------
# solution generation
# ----------------------------------------------------------------------
def random_solution(geo, alphabet: str, rng: random.Random) -> Dict:
    return {cell: rng.choice(alphabet) for cell in geo.cells()}


def _line_texts(geo, solution: Dict) -> Dict[LineKey, str]:
    return {
        (line.family, line.index): "".join(solution[cell] for cell in line.cells)
        for line in geo.lines
    }


def message_solution(
    geo, alphabet: str, rng: random.Random, message: str
) -> Dict:
    """Random solution with ``message`` written into the middle Y line."""
    message = "".join(ch.upper() for ch in message)
    row = geo.mid
    width = geo.row_size(row)
    if len(message) > width:
        raise GenerationError(
            f"message of length {len(message)} does not fit the middle row ({width})"
        )
    missing = [ch for ch in message if ch not in alphabet]
    if missing:
        raise GenerationError(f"message uses letters outside the alphabet: {sorted(set(missing))}")
    solution = random_solution(geo, alphabet, rng)
    start = (width - len(message)) // 2
    for offset, ch in enumerate(message):
        solution[(row, start + offset)] = ch
    return solution


# ----------------------------------------------------------------------
# generation
# ----------------------------------------------------------------------
def _clues_from_tokens(
    tokens_by_line: Dict[LineKey, List[Frag]], families: Sequence[str]
) -> Dict[str, List[str]]:
    out: Dict[str, List[str]] = {family: [] for family in families}
    for (family, index), tokens in tokens_by_line.items():
        out[family].append(render_tokens(tokens))
    return out


def _puzzle_dict(geo, clues, solution, cfg: GenConfig, rows) -> Dict:
    puzzle: Dict = {
        "kind": geo.kind,
        "author": cfg.author,
        "name": cfg.name,
        "solution": rows,
    }
    if geo.kind == "rect":
        puzzle["rows"] = geo.rows
        puzzle["cols"] = geo.cols
    else:
        puzzle["edge"] = cfg.edge
    for family in geo.families:
        puzzle[family] = clues[family]
    return puzzle


def _literal_fraction(tokens_by_line: Dict[LineKey, List[Frag]], texts) -> float:
    total = 0
    literals = 0
    for key, tokens in tokens_by_line.items():
        text = texts[key]
        for tok in tokens:
            total += 1
            if tok.single and tok.body == re.escape(text[tok.lo]) and tok.hi - tok.lo == 1:
                literals += 1
    return literals / total if total else 0.0


def _line_is_literal(tokens: Sequence[Frag], text: str) -> bool:
    """True when a line is still its exact literal string (nothing relaxed)."""
    return all(
        tok.single
        and tok.hi - tok.lo == 1
        and tok.body == re.escape(text[tok.lo])
        for tok in tokens
    )


def _solve(solver: Solver, full: bool = False):
    """Solve with enumeration, so uniqueness can actually be detected."""
    if full:
        return solver.solve(find_all=True, max_solutions=2)
    return solver.solve_propagation_only()


def _band_bounds(difficulty: str, weights=DEFAULT_WEIGHTS):
    if difficulty == "easy":
        return 0.0, weights.easy_max
    if difficulty == "medium":
        return weights.easy_max, weights.medium_max
    return weights.medium_max, 100.0


def _distance(stats: SolveStats, lo: float, hi: float) -> float:
    """How far the composite score is from the requested ``[lo, hi]`` window."""
    if stats.score < lo:
        return lo - stats.score
    if stats.score > hi:
        return stats.score - hi
    return 0.0


def _setup(cfg: GenConfig):
    """Shared geometry/band setup for both generators."""
    if cfg.kind == "auto":
        kind = "rect" if cfg.difficulty == "easy" else "hex"
    else:
        kind = cfg.kind
    if kind == "rect":
        from .geometry import RectGeometry

        geo = RectGeometry(cfg.edge, cfg.edge)
    else:
        geo = HexGeometry(cfg.edge)
    geo.validate()
    families = list(geo.families)
    base_lo, base_hi = _band_bounds(cfg.difficulty)
    custom_window = cfg.min_score is not None or cfg.max_score is not None
    lo = cfg.min_score if cfg.min_score is not None else base_lo
    hi = cfg.max_score if cfg.max_score is not None else base_hi
    if cfg.target_score is not None:
        target = float(cfg.target_score)
    elif cfg.difficulty == "hard":
        target = lo + 0.1 * (hi - lo)
    elif cfg.difficulty == "medium":
        target = lo + 0.5 * (hi - lo)
    else:
        target = 0.6 * hi
    default_cap = {"easy": None, "medium": 0.6, "hard": None}[cfg.difficulty]
    literal_cap = (
        cfg.max_literal_fraction if cfg.max_literal_fraction is not None else default_cap
    )
    return geo, families, lo, hi, target, literal_cap, custom_window


def _generate_fast(cfg: GenConfig):
    """Constructive generation: fewer solves, suitable for on-device use.

    Instead of solving after every relaxation, we record a ladder of valid
    relaxations (each still matches the true text), then *probe* the ladder at
    a modest number of points and keep the unique puzzle whose score is
    closest to the target.  That turns hundreds of solves into a few dozen.
    """
    rng = random.Random(cfg.seed)
    geo, families, lo, hi, target, literal_cap, _custom = _setup(cfg)
    require_relax = cfg.require_all_relaxed and cfg.difficulty != "easy"

    for _attempt in range(cfg.max_attempts):
        if cfg.message is not None:
            solution = message_solution(geo, cfg.alphabet, rng, cfg.message)
        else:
            solution = random_solution(geo, cfg.alphabet, rng)
        texts = _line_texts(geo, solution)
        current: Dict[LineKey, List[Frag]] = {
            key: literal_tokens(text) for key, text in texts.items()
        }
        steps: List[Tuple[LineKey, List[Frag]]] = []
        usage: Dict[str, int] = {}
        alt_quota = rng.randint(0, cfg.max_chunk_alts)

        # 1. de-literalise every line (cheap, no solving)
        keys = list(current.keys())
        rng.shuffle(keys)
        for key in keys:
            for _ in range(8):
                op = rng.choice(("wildcard", "class", "negclass"))
                new_tokens = _OPERATORS[op][0](current[key], texts[key], rng, cfg)
                if new_tokens is None:
                    continue
                rendered = render_tokens(new_tokens)
                if rendered == render_tokens(current[key]):
                    continue
                if re.fullmatch(rendered, texts[key]) is None:
                    continue
                steps.append((key, new_tokens))
                current[key] = new_tokens
                break

        # 2. append a portfolio ladder (cheap, no solving).  Mostly fine-grained
        #    token ops so the score rises smoothly; shapers only occasionally.
        for _ in range(cfg.fast_steps):
            key = rng.choice(list(current.keys()))
            if rng.random() < 0.85:
                op = rng.choice(("wildcard", "class", "negclass"))
            else:
                op = _choose_operator(rng, cfg, usage, alt_quota)
            if op in _SHAPERS:
                new_tokens = _SHAPERS[op][0](texts[key], cfg.alphabet, rng, cfg)
            else:
                new_tokens = _OPERATORS[op][0](current[key], texts[key], rng, cfg)
            if new_tokens is None:
                continue
            rendered = render_tokens(new_tokens)
            if rendered == render_tokens(current[key]):
                continue
            if re.fullmatch(rendered, texts[key]) is None:
                continue
            steps.append((key, new_tokens))
            current[key] = new_tokens
            usage[op] = usage.get(op, 0) + 1

        if not steps:
            continue

        def eval_at(r: int):
            tokens = {k: literal_tokens(t) for k, t in texts.items()}
            for key, new_tokens in steps[:r]:
                tokens[key] = new_tokens
            clues = _clues_from_tokens(tokens, families)
            solver = Solver(geo, clues, cfg.alphabet)
            solved, stats = _solve(solver, full=cfg.full_unique)
            return tokens, len(solved) == 1, stats

        n = len(steps)
        probes = sorted({0, n} | {round(i * n / cfg.fast_probes) for i in range(cfg.fast_probes + 1)})
        candidate = None
        for r in probes:
            tokens, unique, stats = eval_at(r)
            if not unique:
                continue
            if require_relax and any(
                _line_is_literal(tokens[k], texts[k]) for k in tokens
            ):
                continue
            if literal_cap is not None and _literal_fraction(tokens, texts) > literal_cap:
                continue
            if not (lo <= stats.score <= hi):
                continue
            distance = abs(stats.score - target)
            if candidate is None or distance < candidate[0]:
                candidate = (distance, tokens, stats)
        if candidate is not None:
            _, tokens, stats = candidate
            report = _report(cfg, stats, True, 0, texts, tokens, usage)
            report["mode"] = "fast"
            return _finish(geo, tokens, solution, cfg), report

    raise GenerationError(
        f"fast generation could not reach difficulty {cfg.difficulty!r} "
        f"after {cfg.max_attempts} attempt(s)"
    )


def _static_score(stats_edge: int, alphabet: int, dimension: int, opacity: float) -> float:
    """Score estimate from clue style only (no solving)."""
    alpha_norm = min(1.0, math.log2(alphabet + 1) / math.log2(27.0))
    size_norm = min(1.0, max(0.0, (stats_edge - 2.0) / 5.0))
    dim = 1.0 if dimension >= 3 else 0.0
    search_norm = math.log2(2.0) / 12.0  # assumes propagation-only (nodes=1)
    raw = (
        DEFAULT_WEIGHTS.dimension * dim
        + DEFAULT_WEIGHTS.opacity * opacity
        + DEFAULT_WEIGHTS.search * search_norm
        + DEFAULT_WEIGHTS.alphabet * alpha_norm
        + DEFAULT_WEIGHTS.size * size_norm
    )
    return round(100.0 * raw, 2)


def _loosen_token(tok: "Frag", true_ch: str, alphabet: str, rng: random.Random) -> "Frag":
    roll = rng.random()
    if roll < 0.7:
        body = "."
    elif roll < 0.9:
        extras = [c for c in alphabet if c != true_ch]
        extra = rng.sample(extras, k=min(2, len(extras))) if extras else []
        body = "[" + "".join(re.escape(c) for c in sorted(set([true_ch] + extra))) + "]"
    else:
        outside = [c for c in alphabet if c != true_ch]
        body = "[^" + re.escape(rng.choice(outside)) + "]" if outside else "."
    return Frag(body, tok.lo, tok.hi, True)


def _generate_constructive(cfg: GenConfig):
    """Zero-solve generation for non-unique puzzles.

    Because a player's grid is validated line-by-line with ``re.fullmatch``,
    the puzzle does **not** need a unique solution.  So we can skip solving
    entirely: pick a true grid, write clues that all match it, and dial the
    difficulty purely by how many clue tokens are non-literal.  This runs in
    milliseconds and is what makes infinite on-device levels practical.
    """
    rng = random.Random(cfg.seed)
    geo, families, lo, hi, target, literal_cap, _custom = _setup(cfg)
    alphabet = sorted(set(cfg.alphabet))
    dimension = len(families)
    default_cap = {"easy": None, "medium": 0.6, "hard": None}[cfg.difficulty]
    cap = cfg.max_literal_fraction if cfg.max_literal_fraction is not None else default_cap
    # figure out the opacity that lands on the target score
    floor = _static_score(geo.num_rows, len(alphabet), dimension, 0.0)
    opacity_needed = min(0.95, max(0.05, (target - floor) / 55.0)) if target else 0.3
    desired_literal = 1.0 - opacity_needed
    if cap is not None:
        desired_literal = min(desired_literal, cap)

    for _attempt in range(cfg.max_attempts):
        if cfg.message is not None:
            solution = message_solution(geo, cfg.alphabet, rng, cfg.message)
        else:
            solution = random_solution(geo, cfg.alphabet, rng)
        texts = _line_texts(geo, solution)
        tokens: Dict[LineKey, List[Frag]] = {
            key: literal_tokens(text) for key, text in texts.items()
        }

        def is_literal_frag(tok, text):
            return (
                tok.single
                and tok.hi - tok.lo == 1
                and tok.body == re.escape(text[tok.lo])
            )

        # Whole-line structural clues.  Easy keeps the light garnish; medium and
        # hard lean on position-free MIT-style clues (``.*c.*`` spans,
        # ``[SET]*c[SET]*``, class/alternation stars, backref repeats) so letters
        # are not pinned to individual cells.  ``shape_dot_skeleton`` fixes a
        # position per character, so it is deliberately not used here.
        structural = [name for name in _SHAPERS if name != "shape_dot_skeleton"]
        keys = list(tokens)
        rng.shuffle(keys)
        if cfg.difficulty == "easy":
            n_structural = rng.randint(0, min(3, cfg.max_chunk_alts + 1))
            candidates = list(_SHAPERS)
        elif cfg.difficulty == "medium":
            n_structural = rng.randint(0, max(2, len(keys) // 5))
            candidates = structural + ["contains", "contains", "shape_literal_skel"]
        else:
            # MIT-grade looseness: cover most lines with position-free clues,
            # weighted towards ``.*``/``.*c.*`` and literal skeletons.
            n_structural = rng.randint((len(keys) * 3) // 4, len(keys))
            candidates = (
                ["contains"] * 4
                + ["shape_literal_skel"] * 3
                + ["shape_alt_star"] * 2
                + ["shape_class_star"] * 2
                + [
                    "shape_class_word",
                    "shape_negclass_word",
                    "shape_repeat_block",
                    "dotstar",
                ]
            )

        def _acceptable(key, new_tokens):
            rendered = render_tokens(new_tokens)
            if rendered == render_tokens(tokens[key]):
                return False
            if re.search(r"[A-Za-z]", rendered) is None:
                return False  # a clue with no letter carries no information
            return re.fullmatch(rendered, texts[key]) is not None

        for key in keys[:n_structural]:
            op = rng.choice(candidates)
            if op in _SHAPERS:
                new_tokens = _SHAPERS[op][0](texts[key], cfg.alphabet, rng, cfg)
            else:
                new_tokens = _OPERATORS[op][0](tokens[key], texts[key], rng, cfg)
            if new_tokens is None:
                continue
            if _acceptable(key, new_tokens):
                tokens[key] = new_tokens

        if cfg.difficulty == "hard":
            # No cell may be pinned by a single line: replace any line that is
            # still literal with a position-free whole-line clue.
            depot = (
                "dotstar_word",
                "dotstar_word",
                "shape_class_star",
                "shape_class_star",
                "shape_alt_star",
                "shape_class_word",
                "shape_negclass_word",
            )
            for key in list(tokens):
                text = texts[key]
                toks = tokens[key]
                if not any(tok.single and tok.hi - tok.lo == 1 for tok in toks):
                    continue
                for _try in range(4):
                    op = rng.choice(depot)
                    if op == "dotstar_word":
                        new_tokens = [
                            Frag(".*" + re.escape(rng.choice(text)) + ".*", 0, len(text), False)
                        ]
                    else:
                        new_tokens = _SHAPERS[op][0](text, cfg.alphabet, rng, cfg)
                    if new_tokens is not None and _acceptable(key, new_tokens):
                        tokens[key] = new_tokens
                        break

        # ensure every line is non-literal, then dial the overall literal ratio
        def literal_fraction() -> float:
            total = nonliteral = 0
            for key, toks in tokens.items():
                text = texts[key]
                for tok in toks:
                    total += 1
                    if not is_literal_frag(tok, text):
                        nonliteral += 1
            return 1.0 - (nonliteral / total) if total else 1.0

        def convert_random_literal() -> bool:
            candidates = [
                (key, i)
                for key, toks in tokens.items()
                for i, tok in enumerate(toks)
                if is_literal_frag(tok, texts[key])
            ]
            if not candidates:
                return False
            key, i = rng.choice(candidates)
            tok = tokens[key][i]
            tokens[key][i] = _loosen_token(tok, texts[key][tok.lo], cfg.alphabet, rng)
            return True

        for key in list(tokens):
            if _line_is_literal(tokens[key], texts[key]) and not convert_random_literal():
                pass
        guard = 0
        while literal_fraction() > desired_literal and guard < 100000:
            if not convert_random_literal():
                break
            guard += 1

        clues = _clues_from_tokens(tokens, families)
        for line in geo.lines:
            if re.fullmatch(clues[line.family][line.index], texts[(line.family, line.index)]) is None:
                break
        else:
            style = clue_style(clues)
            score = _static_score(geo.num_rows, len(alphabet), dimension, 1.0 - style["literal_ratio"])
            band = (
                "easy"
                if score < DEFAULT_WEIGHTS.easy_max
                else ("medium" if score < DEFAULT_WEIGHTS.medium_max else "hard")
            )
            report = {
                "score": score,
                "difficulty": band,
                "requested_difficulty": cfg.difficulty,
                "unique": False,
                "literal_fraction": round(style["literal_ratio"], 4),
                "opacity": round(1.0 - style["literal_ratio"], 4),
                "mode": "constructive",
                "seed": cfg.seed,
            }
            return _finish(geo, tokens, solution, cfg), report

    raise GenerationError(
        f"constructive generation failed for difficulty {cfg.difficulty!r}"
    )


def generate(cfg: GenConfig):
    """Generate a puzzle.  Returns ``(puzzle_dict, report_dict)``."""
    if not cfg.unique:
        # No uniqueness required -> skip solving entirely (instant).
        return _generate_constructive(cfg)
    if cfg.fast:
        # Fast constructive mode reaches easy puzzles quickly; for medium/hard
        # uniqueness needs per-step feedback, so fall back to the full search.
        import dataclasses

        try:
            return _generate_fast(cfg)
        except GenerationError:
            return generate(dataclasses.replace(cfg, fast=False))
    if cfg.difficulty not in DIFFICULTIES:
        raise GenerationError(f"unknown difficulty {cfg.difficulty!r}")
    rng = random.Random(cfg.seed)
    kind = cfg.kind
    if kind == "auto":
        # Easy is a plain 2D grid; medium/hard use the full 3D hexagon.
        kind = "rect" if cfg.difficulty == "easy" else "hex"
    if kind == "rect":
        from .geometry import RectGeometry

        geo = RectGeometry(cfg.edge, cfg.edge)
    else:
        geo = HexGeometry(cfg.edge)
    geo.validate()
    families = list(geo.families)
    base_lo, base_hi = _band_bounds(cfg.difficulty)
    custom_window = cfg.min_score is not None or cfg.max_score is not None
    lo = cfg.min_score if cfg.min_score is not None else base_lo
    hi = cfg.max_score if cfg.max_score is not None else base_hi

    def in_band(s: SolveStats) -> bool:
        return lo <= s.score <= hi

    # Aim at the middle of the band rather than its top, so ``medium`` does not
    # drift into ``hard`` territory.
    if cfg.target_score is not None:
        target = cfg.target_score
    elif cfg.difficulty == "hard":
        target = lo + 0.1 * (hi - lo)
    elif cfg.difficulty == "medium":
        target = lo + 0.5 * (hi - lo)
    else:  # easy: relax a little but stay clearly easy
        target = 0.6 * hi
    if target is not None:
        target = float(target)

    def reached(s: SolveStats) -> bool:
        if target is None:
            return in_band(s)
        return s.score >= target

    default_cap = {"easy": None, "medium": 0.6, "hard": None}[cfg.difficulty]
    literal_cap = cfg.max_literal_fraction if cfg.max_literal_fraction is not None else default_cap

    best_report = None
    best_distance = float("inf")

    for _attempt in range(cfg.max_attempts):
        if cfg.message is not None:
            solution = message_solution(geo, cfg.alphabet, rng, cfg.message)
        else:
            solution = random_solution(geo, cfg.alphabet, rng)
        texts = _line_texts(geo, solution)
        tokens_by_line: Dict[LineKey, List[Frag]] = {
            key: literal_tokens(text) for key, text in texts.items()
        }

        def style_ok() -> bool:
            return (
                literal_cap is None
                or _literal_fraction(tokens_by_line, texts) <= literal_cap
            )

        def evaluate(tokens):
            clues = _clues_from_tokens(tokens, families)
            solver = Solver(geo, clues, cfg.alphabet)
            solutions, stats = _solve(solver, full=cfg.full_unique)
            return solver, solutions, stats

        solver, solutions, stats = evaluate(tokens_by_line)
        is_unique = len(solutions) == 1

        # ---- optional template phase -----------------------------------
        if cfg.templates:
            keys = list(tokens_by_line.keys())
            rng.shuffle(keys)
            for key in keys:
                text = texts[key]
                candidates = [t for t in TEMPLATES if re.fullmatch(t, text)]
                rng.shuffle(candidates)
                for cand in candidates:
                    trial = dict(tokens_by_line)
                    trial[key] = [Frag(cand, 0, len(text), False)]
                    trial_solver, trial_solutions, trial_stats = evaluate(trial)
                    unique_ok = (not cfg.unique) or len(trial_solutions) == 1
                    if unique_ok and reached(trial_stats):
                        tokens_by_line = trial
                        solver, solutions, stats = (
                            trial_solver,
                            trial_solutions,
                            trial_stats,
                        )
                        is_unique = len(trial_solutions) == 1
                        break

        # ---- relaxation phase ------------------------------------------
        accepted = 0
        target_reached = False
        usage: Dict[str, int] = {}
        alt_quota = rng.randint(0, cfg.max_chunk_alts)

        def build(op: str, key):
            text = texts[key]
            if op in _SHAPERS:
                return _SHAPERS[op][0](text, cfg.alphabet, rng, cfg)
            return _OPERATORS[op][0](tokens_by_line[key], text, rng, cfg)

        def try_apply(key, light=False) -> bool:
            """Relax one line with a diversity-aware, score-controlled step.

            Operators the puzzle has already used are down-weighted, so the
            clues stay varied instead of collapsing into repeated ``.*X.*``.
            Among sampled candidates we keep the one that climbs closest to the
            top of the score window without exceeding it.  With ``light=True``
            we use only cheap per-token operators and take the *smallest*
            increase, so a line can be de-literalised without eating the whole
            score budget.
            """
            nonlocal solver, solutions, stats, is_unique, accepted
            current = render_tokens(tokens_by_line[key])
            base_score = stats.score
            # Close to the target? use fine-grained operators to avoid overshoot.
            close = target is not None and (target - base_score) <= 12.0
            best = None
            best_key = None
            for _try in range(cfg.op_tries):
                if light:
                    op = rng.choice(("wildcard", "class", "negclass"))
                else:
                    op = _choose_operator(rng, cfg, usage, alt_quota, light_set=close)
                new_tokens = build(op, key)
                if new_tokens is None:
                    continue
                rendered = render_tokens(new_tokens)
                if rendered == current:
                    continue
                if re.fullmatch(rendered, texts[key]) is None:
                    continue
                trial = dict(tokens_by_line)
                trial[key] = new_tokens
                trial_solver, trial_solutions, trial_stats = evaluate(trial)
                trial_unique = len(trial_solutions) == 1
                if cfg.unique and not trial_unique:
                    continue
                if trial_stats.score > hi:
                    continue
                if not light and trial_stats.score < base_score:
                    continue
                candidate = (
                    trial_stats.score,
                    new_tokens,
                    trial_solver,
                    trial_solutions,
                    trial_stats,
                    trial_unique,
                    op,
                )
                if light:
                    if best is None or trial_stats.score < best_key:
                        best, best_key = candidate, trial_stats.score
                else:
                    goal = target if target is not None else hi
                    over = 0 if trial_stats.score <= goal else 1
                    key_dist = (over, abs(trial_stats.score - goal))
                    if best is None or key_dist < best_key:
                        best, best_key = candidate, key_dist
                    if abs(trial_stats.score - goal) <= 1.0:
                        break
            if best is None:
                return False
            _, new_tokens, trial_solver, trial_solutions, trial_stats, trial_unique, op = best
            tokens_by_line[key] = new_tokens
            solver, solutions, stats = trial_solver, trial_solutions, trial_stats
            is_unique = trial_unique
            usage[op] = usage.get(op, 0) + 1
            accepted += 1
            return True

        # For medium/hard, first make sure *no* line is still a plain literal
        # string: a fully literal clue would simply give away a whole row.
        require_relax = cfg.require_all_relaxed and cfg.difficulty != "easy"
        if require_relax:
            keys = list(tokens_by_line.keys())
            rng.shuffle(keys)
            for key in keys:
                if _line_is_literal(tokens_by_line[key], texts[key]):
                    try_apply(key, light=True)
            all_relaxed = all(
                not _line_is_literal(tokens_by_line[k], texts[k]) for k in tokens_by_line
            )
        else:
            all_relaxed = True

        if all_relaxed:
            for _ in range(cfg.loosen):
                key = rng.choice(list(tokens_by_line.keys()))
                applied = try_apply(key)
                if not applied:
                    others = list(tokens_by_line.keys())
                    rng.shuffle(others)
                    applied = any(try_apply(k) for k in others)
                    if not applied:
                        break
                if reached(stats) and style_ok():
                    target_reached = True
                    break
                if (
                    not custom_window
                    and cfg.difficulty == "easy"
                    and accepted >= cfg.easy_max_steps
                ):
                    break
                if (
                    cfg.literal_ratio is not None
                    and _literal_fraction(tokens_by_line, texts) <= cfg.literal_ratio
                ):
                    break

        target_reached = target_reached or (reached(stats) and style_ok())
        if require_relax and not all_relaxed:
            target_reached = False

        # Re-confirm uniqueness against the *final* clues.
        _, solutions, stats = evaluate(tokens_by_line)
        is_unique = len(solutions) == 1
        report = _report(cfg, stats, is_unique, accepted, texts, tokens_by_line, usage)
        report["distance"] = _distance(stats, lo, hi)
        if target_reached and (is_unique or not cfg.unique):
            return _finish(geo, tokens_by_line, solution, cfg), report
        if report["distance"] < best_distance:
            best_distance = report["distance"]
            best_report = report

    closest = best_report or {}
    target = (
        f"score in [{lo}, {hi}]"
        if custom_window
        else f"difficulty {cfg.difficulty!r}"
    )
    raise GenerationError(
        f"could not reach {target} after {cfg.max_attempts} attempt(s); "
        f"closest statistics: {closest}"
    )


def _report(cfg: GenConfig, stats: SolveStats, unique: bool, steps: int, texts, tokens, usage=None) -> Dict:
    data = stats.to_dict()
    data.update(
        {
            "requested_difficulty": cfg.difficulty,
            "unique": unique,
            "accepted_steps": steps,
            "literal_fraction": round(_literal_fraction(tokens, texts), 4),
            "op_usage": dict(usage or {}),
            "seed": cfg.seed,
        }
    )
    return data


def _finish(geo, tokens_by_line, solution, cfg):
    clues = _clues_from_tokens(tokens_by_line, list(geo.families))
    solver = Solver(geo, clues, cfg.alphabet)
    rows = solver.solution_rows(solution)
    # The emitted rows must satisfy every emitted clue.
    solver.verify_solution(solution)
    return _puzzle_dict(geo, clues, solution, cfg, rows)
