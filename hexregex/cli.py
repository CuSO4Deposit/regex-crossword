"""Command line interface: ``hexregex``.

Subcommands
-----------
``solve``    solve a puzzle JSON (optionally enumerate all / show statistics)
``verify``   report whether a puzzle is uniquely solvable and re-check every line
``gen``      generate a new puzzle with a controlled difficulty
``gen-batch``  generate a bank of puzzles in one go
``render``   draw a puzzle (and optionally its solution) as an ASCII hexagon

Run ``hexregex <command> --help`` for the full option list.
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from typing import Dict, List, Optional

from . import __version__
from .generator import DIFFICULTIES, GenConfig, GenerationError, generate
from .geometry import HexGeometry
from .solver import DEFAULT_ALPHABET, Solver, SolverError

EXAMPLES = """examples:
  hexregex solve puzzle.json --show-stats
  hexregex solve puzzle.json --all --max-solutions 20
  hexregex verify puzzle.json
  hexregex gen --edge 5 --alphabet CDEHIMNORST --difficulty easy --seed 1 \\
      --unique -o easy.json
  hexregex gen --edge 5 --kind hex --alphabet CDEHIMNORST --difficulty medium \\
      --seed 1 --unique -o medium.json
  hexregex gen --edge 7 --kind hex --alphabet CDEHIMNORSTUVX --difficulty hard \\
      --seed 7 --no-unique --allow-backref --templates -o hard.json
  hexregex gen --edge 5 --kind hex --alphabet CDEHIMNORST --seed 3 --no-unique \\
      --min-score 60 --max-score 66 -o tuned.json
  hexregex gen-batch --difficulty medium --count 50 --edge 5 --seed 0 -o bank.json
  hexregex render puzzle.json --solution
  hexregex render puzzle.json --jimbly-out board.b64
"""


# ----------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------
def load_puzzle(path: str) -> Dict:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except OSError as exc:
        raise SystemExit(f"error: cannot read {path}: {exc}")
    except json.JSONDecodeError as exc:
        raise SystemExit(f"error: {path} is not valid JSON: {exc}")
    if not isinstance(data, dict) or ("edge" not in data and "rows" not in data):
        raise SystemExit(f"error: {path} does not look like a puzzle")
    return data


def hex_text(geo, letters: Optional[Dict] = None) -> str:
    """Render a hexagon or rectangle; ``letters`` maps cells to characters."""
    width = max(geo.row_size(r) for r in range(geo.num_rows))
    lines = []
    for r in range(geo.num_rows):
        row = geo.row_size(r)
        indent = " " * (width - row)
        cells = " ".join(
            (letters[(r, c)] if letters and (r, c) in letters else ".")
            for c in range(row)
        )
        lines.append((indent + cells).rstrip())
    return "\n".join(lines)


def output_puzzle(puzzle: Dict) -> None:
    print(json.dumps(puzzle, ensure_ascii=False, indent=2))


def puzzle_for_jimbly(puzzle: Dict) -> str:
    """Return the ``?puzzle=<base64>`` payload used by jimbly's player."""
    if puzzle.get("kind", "hex") != "hex":
        raise SystemExit("error: jimbly export only supports hexagonal puzzles")
    payload = {
        "size": int(puzzle["edge"]) * 2 - 1,
        "author": puzzle.get("author", ""),
        "name": puzzle.get("name", ""),
        "x": puzzle["x"],
        "y": puzzle["y"],
        "z": puzzle["z"],
    }
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    return "?puzzle=" + base64.b64encode(raw).decode("ascii")


def _solve(puzzle: Dict, alphabet: Optional[str], find_all: bool, max_solutions: int):
    solver = Solver.from_puzzle(puzzle, alphabet=alphabet)
    return solver, solver.solve(find_all=find_all, max_solutions=max_solutions)


# ----------------------------------------------------------------------
# subcommands
# ----------------------------------------------------------------------
def cmd_solve(args) -> int:
    puzzle = load_puzzle(args.puzzle)
    try:
        solver, (solutions, stats) = _solve(
            puzzle, args.alphabet, args.all, args.max_solutions
        )
    except SolverError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if not solutions:
        print("no solution")
        return 1
    for i, solution in enumerate(solutions, 1):
        if len(solutions) > 1:
            print(f"solution {i}/{len(solutions)}:")
        print(hex_text(solver.geo, solution))
    if args.show_stats:
        print(json.dumps(stats.to_dict(), indent=2))
    return 0


def cmd_verify(args) -> int:
    puzzle = load_puzzle(args.puzzle)
    try:
        solver, (solutions, stats) = _solve(puzzle, args.alphabet, True, 2)
    except SolverError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if not solutions:
        print("UNSATISFIABLE: no assignment matches all clues")
        return 1
    for solution in solutions:
        solver.verify_solution(solution)
    if len(solutions) == 1:
        print("UNIQUE: exactly one solution; all lines pass re.fullmatch")
        print(hex_text(solver.geo, solutions[0]))
        return 0
    print(f"MULTIPLE: at least {len(solutions)} solutions")
    return 1


def cmd_gen(args) -> int:
    config = GenConfig(
        edge=args.edge,
        alphabet=args.alphabet,
        difficulty=args.difficulty,
        kind=args.kind,
        seed=args.seed,
        unique=args.unique,
        message=args.message,
        templates=args.templates,
        allow_backref=args.allow_backref,
        loosen=args.loosen,
        literal_ratio=args.literal_ratio,
        require_all_relaxed=not args.allow_literal_lines,
        max_chunk_alts=args.max_chunk_alts,
        max_literal_fraction=args.max_literal_fraction,
        target_score=args.target_score,
        full_unique=args.full_unique,
        fast=args.fast,
        min_score=args.min_score,
        max_score=args.max_score,
        max_attempts=args.max_attempts,
        author=args.author,
        name=args.name,
    )
    try:
        puzzle, report = generate(config)
    except GenerationError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    puzzle.pop("_stats", None)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            json.dump(puzzle, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    else:
        output_puzzle(puzzle)
    if args.show_stats:
        print(json.dumps(report, indent=2), file=sys.stderr)
    if args.jimbly_out:
        with open(args.jimbly_out, "w", encoding="ascii") as handle:
            handle.write(puzzle_for_jimbly(puzzle) + "\n")
    return 0


def geometry_from_puzzle(puzzle: Dict):
    kind = puzzle.get("kind", "hex")
    if kind == "rect":
        from .geometry import RectGeometry

        return RectGeometry(int(puzzle["rows"]), int(puzzle["cols"]))
    return HexGeometry(int(puzzle["edge"]))


def cmd_render(args) -> int:
    puzzle = load_puzzle(args.puzzle)
    geo = geometry_from_puzzle(puzzle)
    letters = None
    if args.solution:
        if "solution" in puzzle:
            letters = _letters_from_rows(puzzle)
        else:
            try:
                solver, (solutions, _stats) = _solve(puzzle, args.alphabet, False, 1)
            except SolverError as exc:
                print(f"error: {exc}", file=sys.stderr)
                return 2
            if not solutions:
                print("error: puzzle has no solution", file=sys.stderr)
                return 1
            letters = solutions[0]
    print(hex_text(geo, letters))
    if args.jimbly_out:
        with open(args.jimbly_out, "w", encoding="ascii") as handle:
            handle.write(puzzle_for_jimbly(puzzle) + "\n")
    return 0


def _letters_from_rows(puzzle: Dict) -> Dict:
    rows = puzzle["solution"]
    out = {}
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            out[(r, c)] = ch
    return out


def cmd_gen_batch(args) -> int:
    """Generate a bank of puzzles offline (the mobile-friendly path)."""
    puzzles = []
    seed = args.seed
    max_tries = args.count * args.tries_per_puzzle
    tried = 0
    while len(puzzles) < args.count and tried < max_tries:
        tried += 1
        config = GenConfig(
            edge=args.edge,
            alphabet=args.alphabet,
            difficulty=args.difficulty,
            kind=args.kind,
            seed=seed,
            unique=args.unique,
            allow_backref=args.allow_backref,
            templates=args.templates,
            loosen=args.loosen,
            fast=args.fast,
            max_attempts=args.inner_attempts,
        )
        try:
            puzzle, report = generate(config)
        except GenerationError:
            seed += 1
            continue
        puzzle.pop("_stats", None)
        puzzle["meta"] = {
            "difficulty": args.difficulty,
            "band": report.get("difficulty"),
            "score": report.get("score"),
            "literal_fraction": report.get("literal_fraction"),
            "seed": seed,
            "kind": puzzle.get("kind"),
            "unique": report.get("unique"),
        }
        puzzles.append(puzzle)
        seed += 1
    bank = {
        "generator": "hexregex",
        "version": __version__,
        "difficulty": args.difficulty,
        "count": len(puzzles),
        "puzzles": puzzles,
    }
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(bank, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"wrote {len(puzzles)}/{args.count} puzzles to {args.output}")
    return 0 if len(puzzles) == args.count else 1


# ----------------------------------------------------------------------
# argument parsing
# ----------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hexregex",
        description="Solver and generator for hexagonal regular crosswords.",
        epilog=EXAMPLES,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--version", action="version", version=f"hexregex {__version__}")
    sub = parser.add_subparsers(dest="command", metavar="command")

    def add_alphabet(p):
        p.add_argument(
            "--alphabet",
            default=None,
            help="candidate letters (default: %s)" % DEFAULT_ALPHABET,
        )

    p = sub.add_parser("solve", help="solve a puzzle JSON")
    p.add_argument("puzzle", help="path to puzzle JSON")
    p.add_argument("--show-stats", action="store_true", help="print difficulty statistics")
    p.add_argument("--all", action="store_true", help="enumerate all solutions")
    p.add_argument("--max-solutions", type=int, default=10, help="cap for --all (default 10)")
    add_alphabet(p)
    p.set_defaults(func=cmd_solve)

    p = sub.add_parser("verify", help="check uniqueness and re-check every line")
    p.add_argument("puzzle", help="path to puzzle JSON")
    add_alphabet(p)
    p.set_defaults(func=cmd_verify)

    p = sub.add_parser("gen", help="generate a new puzzle")
    p.add_argument("--edge", type=int, default=7,
                   help="hexagon edge, or side length for --kind rect (default 7)")
    p.add_argument("--alphabet", default=DEFAULT_ALPHABET, help="letters to use")
    p.add_argument("--difficulty", choices=DIFFICULTIES, default="easy", help="target difficulty")
    p.add_argument("--kind", choices=("auto", "hex", "rect"), default="auto",
                   help="auto (easy=2D rect, medium/hard=3D hex), hex or rect")
    p.add_argument("--seed", type=int, default=0, help="random seed (reproducible)")
    p.add_argument("--unique", dest="unique", action="store_true", default=True,
                   help="require a unique solution (default)")
    p.add_argument("--no-unique", dest="unique", action="store_false",
                   help="allow puzzles with several solutions")
    p.add_argument("--message", default=None, help="hide this text in the middle row")
    p.add_argument("--templates", action="store_true", help="prefer human-friendly templates")
    p.add_argument("--allow-backref", action="store_true", help="allow backreference clues")
    p.add_argument("--loosen", type=int, default=250, help="max relaxation steps (default 250)")
    p.add_argument("--literal-ratio", type=float, default=None,
                   help="stop relaxing once the literal fraction reaches this value")
    p.add_argument("--allow-literal-lines", action="store_true",
                   help="allow fully literal clues for medium/hard (off by default)")
    p.add_argument("--max-chunk-alts", type=int, default=2,
                   help="max multi-char alternations (O|RHH|MM)* per puzzle (default 2)")
    p.add_argument("--max-literal-fraction", type=float, default=None,
                   help="reject puzzles above this literal-token fraction "
                        "(default 0.6 for medium, none otherwise)")
    p.add_argument("--min-score", type=float, default=None,
                   help="override the lower difficulty-score bound")
    p.add_argument("--max-score", type=float, default=None,
                   help="override the upper difficulty-score bound")
    p.add_argument("--target-score", type=float, default=None,
                   help="aim for this score and stop (default: band middle)")
    p.add_argument("--full-unique", action="store_true",
                   help="use the expensive full uniqueness check (slower)")
    p.add_argument("--fast", action="store_true",
                   help="fast constructive mode (fewer solves; on-device friendly)")
    p.add_argument("--max-attempts", type=int, default=8, help="attempts before giving up")
    p.add_argument("--author", default="generated", help="author field")
    p.add_argument("--name", default="generated", help="name field")
    p.add_argument("-o", "--output", default=None, help="write JSON here instead of stdout")
    p.add_argument("--jimbly-out", default=None, help="also write a jimbly base64 payload")
    p.add_argument("--show-stats", action="store_true", help="print solver statistics")
    p.set_defaults(func=cmd_gen)

    p = sub.add_parser(
        "gen-batch", help="generate a bank of puzzles offline (for shipping)"
    )
    p.add_argument("--edge", type=int, default=5, help="grid size (default 5)")
    p.add_argument("--alphabet", default=DEFAULT_ALPHABET, help="letters to use")
    p.add_argument("--difficulty", choices=DIFFICULTIES, default="medium")
    p.add_argument("--kind", choices=("auto", "hex", "rect"), default="auto")
    p.add_argument("--seed", type=int, default=0, help="first seed")
    p.add_argument("--count", type=int, default=10, help="number of puzzles")
    p.add_argument("--tries-per-puzzle", type=int, default=4,
                   help="seed attempts allowed per wanted puzzle")
    p.add_argument("--inner-attempts", type=int, default=3,
                   help="max_attempts inside one generate call")
    p.add_argument("--unique", dest="unique", action="store_true", default=True)
    p.add_argument("--no-unique", dest="unique", action="store_false")
    p.add_argument("--allow-backref", action="store_true")
    p.add_argument("--templates", action="store_true")
    p.add_argument("--fast", action="store_true",
                   help="fast constructive mode (recommended for big batches)")
    p.add_argument("--loosen", type=int, default=250)
    p.add_argument("-o", "--output", default="bank.json", help="output bank JSON")
    p.set_defaults(func=cmd_gen_batch)

    p = sub.add_parser("render", help="draw a puzzle as an ASCII hexagon")
    p.add_argument("puzzle", help="path to puzzle JSON")
    p.add_argument("--solution", action="store_true", help="print letters (solve if needed)")
    p.add_argument("--jimbly-out", default=None, help="write a jimbly base64 payload")
    add_alphabet(p)
    p.set_defaults(func=cmd_render)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "command", None):
        parser.print_help()
        return 0
    return args.func(args)


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
