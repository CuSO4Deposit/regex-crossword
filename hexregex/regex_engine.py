"""A small regular-expression engine that matches against *letter domains*.

The crossword solver never matches a pattern against a fixed string during
search; instead each grid position carries a set of candidate letters and we
must decide whether the line admits *some* assignment that the pattern
matches with :func:`re.fullmatch` semantics.

This module turns a pattern into a small AST (via the standard library's own
parser) and answers that feasibility question with memoised backtracking.

Supported syntax (the intersection of Python and JavaScript ``RegExp``):
literals, ``.``, character classes ``[...]`` (``RANGE``, ``NEGATE``,
``CATEGORY``), alternation ``|``, groups ``()``, the quantifiers
``* + ? {m,n}`` and backreferences ``\\1`` .. ``\\9``.  ``^`` / ``$`` and
other anchors are ignored.  Python-only constructs such as ``(?P<name>...)``
are rejected.
"""

from __future__ import annotations

import string
from functools import lru_cache
from typing import Any, Dict, FrozenSet, Iterable, List, Mapping, Optional, Sequence, Tuple

try:  # Python 3.11+
    import re._parser as _parser  # type: ignore
except ImportError:  # pragma: no cover - older interpreters
    import sre_parse as _parser  # type: ignore

__all__ = ["Compiled", "compile_pattern", "feasible", "line_possible", "RegexSyntaxError"]

INF_REPEAT = 1 << 30

# AST node shapes (all hashable tuples):
#   ("lit", ch)                          literal character
#   ("any",)                             .
#   ("class", (negate, specs))           [...] / [^...]
#   ("rep", mn, mx, body, greedy)        body is a tuple of nodes
#   ("group", gid, body)                 capturing group
#   ("ref", gid)                         backreference
#   ("alt", (seq, seq, ...))             each seq is a tuple of nodes
#   ("anchor",)                          ignored anchor
# specs: ("lit", ch) | ("range", lo, hi) | ("cat", name)

Node = tuple
Sequence_ = Tuple[Node, ...]


class RegexSyntaxError(ValueError):
    """Raised when a clue uses syntax we do not support."""


class Compiled:
    """A parsed pattern plus the group ids it back-references."""

    __slots__ = ("pattern", "ast", "refs", "ngroups")

    def __init__(self, pattern: str, ast: Sequence_, refs: FrozenSet[int], ngroups: int):
        self.pattern = pattern
        self.ast = ast
        self.refs = refs
        self.ngroups = ngroups

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"Compiled({self.pattern!r}, groups={self.ngroups})"


def _convert_in(items: Iterable[Tuple[Any, Any]]) -> Tuple[bool, Tuple[tuple, ...]]:
    negate = False
    specs: List[tuple] = []
    for op, arg in items:
        name = getattr(op, "name", str(op))
        if name == "NEGATE":
            negate = True
        elif name == "LITERAL":
            specs.append(("lit", chr(arg)))
        elif name == "RANGE":
            specs.append(("range", int(arg[0]), int(arg[1])))
        elif name == "CATEGORY":
            specs.append(("cat", getattr(arg, "name", str(arg))))
        else:  # pragma: no cover - defensive
            raise RegexSyntaxError(f"unsupported character-class item: {name}")
    return negate, tuple(specs)


def _convert_seq(seq: Iterable[Tuple[Any, Any]], refs: set) -> Sequence_:
    out: List[Node] = []
    for op, arg in seq:
        name = getattr(op, "name", str(op))
        if name == "LITERAL":
            out.append(("lit", chr(arg)))
        elif name == "NOT_LITERAL":
            out.append(("class", (True, (("lit", chr(arg)),))))
        elif name == "ANY":
            out.append(("any",))
        elif name == "IN":
            out.append(("class", _convert_in(arg)))
        elif name == "BRANCH":
            out.append(("alt", tuple(_convert_seq(branch, refs) for branch in arg[1])))
        elif name == "SUBPATTERN":
            gid = int(arg[0])
            body = _convert_seq(arg[3], refs)
            out.append(("group", gid, body))
        elif name in ("MAX_REPEAT", "MIN_REPEAT"):
            mn, mx, body = arg
            if getattr(mx, "name", str(mx)) == "MAXREPEAT":
                mx = INF_REPEAT
            else:
                mx = int(mx)
            out.append(("rep", int(mn), mx, _convert_seq(body, refs), name == "MAX_REPEAT"))
        elif name == "GROUPREF":
            gid = int(arg)
            refs.add(gid)
            out.append(("ref", gid))
        elif name == "AT":
            out.append(("anchor",))
        elif name in ("ASSERT", "ASSERT_NOT"):
            raise RegexSyntaxError("lookahead assertions are not supported")
        else:  # pragma: no cover - defensive
            raise RegexSyntaxError(f"unsupported regex construct: {name}")
    return tuple(out)


@lru_cache(maxsize=4096)
def compile_pattern(pattern: str) -> Compiled:
    """Parse ``pattern`` into a :class:`Compiled` object (cached)."""
    if not isinstance(pattern, str):
        raise TypeError("pattern must be a string")
    try:
        parsed = _parser.parse(pattern)
    except Exception as exc:  # re.error subclasses vary across versions
        raise RegexSyntaxError(f"cannot parse {pattern!r}: {exc}") from exc
    refs: set = set()
    ast = _convert_seq(parsed, refs)  # type: ignore[arg-type]
    ngroups = 0
    for node in _walk(ast):
        if node[0] == "group":
            ngroups = max(ngroups, node[1])
        elif node[0] == "ref":
            ngroups = max(ngroups, node[1])
    if refs and max(refs) > ngroups:
        ngroups = max(refs)
    return Compiled(pattern, ast, frozenset(refs), ngroups)


def _walk(seq: Sequence_) -> Iterable[Node]:
    for node in seq:
        yield node
        op = node[0]
        if op == "rep":
            yield from _walk(node[3])
        elif op == "group":
            yield from _walk(node[2])
        elif op == "alt":
            for branch in node[1]:
                yield from _walk(branch)


# ----------------------------------------------------------------------
# character classes
# ----------------------------------------------------------------------
def _category_match(name: str, ch: str) -> bool:
    negate = "NOT_" in name
    if name.endswith("DIGIT"):
        value = ch in string.digits
    elif name.endswith("WORD"):
        value = ch.isalnum() or ch == "_"
    elif name.endswith("SPACE"):
        value = ch in " \t\n\r\f\v"
    elif name.endswith("LINEBREAK"):
        value = ch in "\n\r"
    else:  # pragma: no cover - unknown category
        value = False
    return value != negate


def _class_contains(negate: bool, specs: Sequence[tuple], ch: str) -> bool:
    matched = False
    for spec in specs:
        kind = spec[0]
        if kind == "lit":
            if ch == spec[1]:
                matched = True
                break
        elif kind == "range":
            if spec[1] <= ord(ch) <= spec[2]:
                matched = True
                break
        elif kind == "cat":
            if _category_match(spec[1], ch):
                matched = True
                break
    return matched != negate


# ----------------------------------------------------------------------
# feasibility matcher
# ----------------------------------------------------------------------
def feasible(allowed: Sequence[Iterable[str]], compiled: Compiled) -> bool:
    """Return whether some assignment to ``allowed`` matches ``compiled``.

    ``allowed[i]`` is the set of letters permitted at position ``i``.  A
    successful match must consume *every* position (``re.fullmatch``).

    The matcher never enumerates concrete letters.  Consuming a literal or a
    character class simply *narrows* the set of letters associated with that
    grid position.  A backreference unifies the positions it captured with
    the positions it matches, intersecting their letter sets.  A match is
    feasible iff every narrowed set stays non-empty, which is exact because
    the only cross-position constraints are these equalities.
    """
    allowed_sets: Tuple[FrozenSet[str], ...] = tuple(frozenset(a) for a in allowed)
    L = len(allowed_sets)
    ast = compiled.ast
    refs = compiled.refs
    ngroups = compiled.ngroups
    track = bool(refs)
    groups0: Tuple[Optional[tuple], ...] = (None,) * (ngroups + 1)
    parent0: Tuple[int, ...] = tuple(range(L))
    dom0: Tuple[FrozenSet[str], ...] = allowed_sets
    memo: Dict[tuple, FrozenSet[tuple]] = {}

    def find(parent: Tuple[int, ...], x: int) -> int:
        while parent[x] != x:
            x = parent[x]
        return x

    def S(seq: Sequence_, pos: int, groups: tuple, parent: tuple, dom: tuple):
        key = (seq, pos, groups, parent, dom)
        cached = memo.get(key)
        if cached is not None:
            return cached
        if not seq:
            res: FrozenSet[tuple] = frozenset(((pos, groups, parent, dom),))
        else:
            acc = set()
            for state in N(seq[0], pos, groups, parent, dom):
                acc.update(S(seq[1:], *state))  # type: ignore[arg-type]
            res = frozenset(acc)
        memo[key] = res
        return res

    def constrain(parent: tuple, dom: tuple, pos: int, accept: Optional[FrozenSet[str]]):
        """Apply a unary constraint at ``pos``; return the new dom or None."""
        r = find(parent, pos) if track else pos
        cur = dom[r]
        if accept is None:
            return dom if cur else None
        inter = cur & accept
        if not inter:
            return None
        if not track:
            return dom
        nd = list(dom)
        nd[r] = inter
        return tuple(nd)

    def N(node: Node, pos: int, groups: tuple, parent: tuple, dom: tuple):
        op = node[0]
        if op == "lit":
            if pos >= L:
                return ()
            nd = constrain(parent, dom, pos, frozenset((node[1],)))
            return () if nd is None else ((pos + 1, groups, parent, nd),)
        if op == "any":
            if pos >= L:
                return ()
            r = find(parent, pos) if track else pos
            if not dom[r]:
                return ()
            return ((pos + 1, groups, parent, dom),)
        if op == "class":
            if pos >= L:
                return ()
            negate, specs = node[1]
            r = find(parent, pos) if track else pos
            accept = frozenset(c for c in dom[r] if _class_contains(negate, specs, c))
            nd = constrain(parent, dom, pos, accept)
            return () if nd is None else ((pos + 1, groups, parent, nd),)
        if op == "anchor":
            return ((pos, groups, parent, dom),)
        if op == "ref":
            gid = node[1]
            if gid >= len(groups):
                return ()
            value = groups[gid]
            if value is None:
                # Python treats a backreference to a group that did not
                # participate in the match as a hard failure.
                return ()
            k = len(value)
            if pos + k > L:
                return ()
            p, d = parent, dom
            if track:
                for i in range(k):
                    r1 = find(p, value[i])
                    r2 = find(p, pos + i)
                    if r1 == r2:
                        continue
                    inter = d[r1] & d[r2]
                    if not inter:
                        return ()
                    np = list(p)
                    np[r2] = r1
                    nd = list(d)
                    nd[r1] = inter
                    p = tuple(np)
                    d = tuple(nd)
            else:
                for i in range(k):
                    if not (d[value[i]] & d[pos + i]):
                        return ()
            return ((pos + k, groups, p, d),)
        if op == "group":
            gid, body = node[1], node[2]
            out = []
            for p2, g2, pa2, do2 in S(body, pos, groups, parent, dom):
                if gid in refs:
                    captured = tuple(range(pos, p2))
                    ng = list(g2)
                    ng[gid] = captured
                    g2 = tuple(ng)
                out.append((p2, g2, pa2, do2))
            return tuple(out)
        if op == "alt":
            acc = set()
            for branch in node[1]:
                acc.update(S(branch, pos, groups, parent, dom))
            return tuple(acc)
        if op == "rep":
            return _rep_states(node[1], node[2], node[3], pos, groups, parent, dom)
        raise RegexSyntaxError(f"unsupported node {op}")  # pragma: no cover

    def _rep_states(mn, mx, body, pos, groups, parent, dom):
        results: List[tuple] = []

        def rec(count, pos, groups, parent, dom, last_zero):
            if count >= mn:
                results.append((pos, groups, parent, dom))
            if count >= mx:
                return
            for st in S(body, pos, groups, parent, dom):
                p2, g2, pa2, do2 = st
                zero = p2 == pos and g2 == groups and pa2 == parent and do2 == dom
                if zero:
                    if last_zero:
                        continue
                    # A zero-width iteration leaves the state untouched, so
                    # it can be repeated freely; the minimum can always be
                    # reached by padding once we are here.
                    if count + 1 < mn:
                        results.append(st)
                    rec(count + 1, p2, g2, pa2, do2, True)
                else:
                    rec(count + 1, p2, g2, pa2, do2, False)

        rec(0, pos, groups, parent, dom, False)
        return tuple(results)

    final = S(ast, 0, groups0, parent0, dom0)
    return any(state[0] == L for state in final)


def line_possible(cells: Sequence, domains, regex) -> bool:
    """Feasibility of one line.

    ``cells`` is the ordered list of cells, ``domains`` maps each cell to an
    iterable of candidate letters (or is a sequence aligned with ``cells``),
    and ``regex`` is either a pattern string or a :class:`Compiled`.
    """
    if isinstance(domains, Mapping):
        allowed = [domains[cell] for cell in cells]
    else:
        allowed = list(domains)
    compiled = regex if isinstance(regex, Compiled) else compile_pattern(regex)
    return feasible(allowed, compiled)


@lru_cache(maxsize=1 << 16)
def _feasible_cached(pattern: str, allowed_key: Tuple[FrozenSet[str], ...]) -> bool:
    return feasible(allowed_key, compile_pattern(pattern))


def feasible_cached(allowed: Sequence[Iterable[str]], compiled: Compiled) -> bool:
    """Like :func:`feasible` but memoised globally across solver runs."""
    key = tuple(frozenset(a) for a in allowed)
    return _feasible_cached(compiled.pattern, key)
