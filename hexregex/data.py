"""Reference data: MIT Mystery Hunt 2013, *A Regular Crossword* (Dan Gulotta).

The clue lists are given in line-index order for the X, Y and Z families.
Remember the reading directions:

* ``y[i]`` reads row ``i`` left to right,
* ``z[i]`` reads top to bottom,
* ``x[i]`` reads bottom to top.
"""

from __future__ import annotations

from typing import Dict

ORIGINAL_EDGE = 7

ORIGINAL_X = [
    r"[^X]*(DN|TE|NI)",
    r"[RONMHC]*I[RONMHC]*",
    r".*(..)\1P+",
    r"(E|RC|NM)*",
    r"([^MC]|MM|CC)*",
    r"R?(CR)*MC[MA]*",
    r".*",
    r".*CDD.*RRP.*",
    r"(XHH|[^XH])*",
    r"([^CME]|ME)*",
    r".*RXO.*",
    r".*LR.*RL.*",
    r".*EU.*ES.*",
]

ORIGINAL_Y = [
    r".*H.*H.*",
    r"(DI|NS|TH|OM)*",
    r"F.*[AO].*[AO].*",
    r"(O|RHH|MM)*",
    r".*",
    r"C*MC(CCC|MM)*",
    r"[^C]*[^R]*III.*",
    r"(...?)\1*",
    r"([^X]|XCC)*",
    r"(RR|HHH)*.?",
    r"N.*X.X.X.*E",
    r"R*D*M*",
    r".(C|HH)*",
]

ORIGINAL_Z = [
    r".*H.*V.*G.*",
    r"[RC]*",
    r"M*XEX.*",
    r".*MCC.*DD.*",
    r".*X.*RCHX.*",
    r".*(.)(.)(.)(.)\4\3\2\1.*",
    r"(NI|ES|IH).*",
    r"[^C]*MMM[^C]*",
    r".*(.)X\1C\1.*",
    r"[ROMEA]*HO[UMIEC]*",
    r"(XR|[^R])*",
    r"[^M]*M[^M]*",
    r"(S|MM|HHH)*",
]


def original_puzzle() -> Dict[str, object]:
    """Return the original puzzle as a plain puzzle dictionary."""
    return {
        "edge": ORIGINAL_EDGE,
        "author": "Dan Gulotta",
        "name": "original",
        "x": list(ORIGINAL_X),
        "y": list(ORIGINAL_Y),
        "z": list(ORIGINAL_Z),
    }
