"""Detection of high-flow reports that are only messages (not flow observations).

Users sometimes post a GROS/TRES_GROS/CRUE report (the most visible colours) to share an
information: fire-risk closures, works, re-equipment, parking rules... Such reports must neither be
training labels nor canyon history. The rule is shared with the app (``DebitReportQuality.kt``):

    level >= GROS  and  not descended  and  ("unrelated to the flow" stated explicitly
                                           or an information motif without hydrological evidence)

Manual review decisions (``scripts/debits/observation_overrides.json``) take precedence in training.
"""
from __future__ import annotations

import re
import unicodedata

EXPLICIT_PATTERNS = [
    r"\bsans rapport avec (?:le |les )?debits?\b",
    r"\bsans lien avec (?:le |les )?debits?\b",
]

INFO_PATTERNS = [
    r"\binformations?\b",
    r"\binfos?\b",
    r"\bincendies?\b",
    r"\binterdi(?:t|te|ts|tes|ction|ctions)\b",
    r"\barretes?\b",
    r"\btravaux\b",
    r"\bchantiers?\b",
    r"\breequip\w*",
    r"\bstationnement\b",
    r"\bparkings?\b",
    r"\bproprietaires?\b",
    r"\bfermetures?\b",
    r"\bfermee?s?\b",
    r"\bsurfrequentation\b",
    r"\bprevention\b",
    r"\bbaignade\b",
    r"\bcirculation\b",
]

HYDRO_PATTERNS = [
    r"\bdebits?\b",
    r"\beaux?\b",
    r"\bcrues?\b",
    r"\bpluies?\b",
    r"\borages?\w*",
    r"\bg ?d\b",
    r"\btgd\b",
    r"\bdc\b",
    r"\bm3\b",
    r"\bpousse\w*",
    r"\bbrass\w*",
    r"\bfonte\b",
    r"\bflotte\b",
    r"\bchocolat\b",
    r"\bcaudal\b",
    r"\bportata\b",
    r"\bwater\b",
    r"\bflow\b",
    r"\bechelle\b",
    r"\bimpraticable\w*",
    r"\becume\w*",
    r"\bprecipitations?\b",
    r"\bembruns?\b",
    r"\bniveaux?\b",
    r"\bcharge\w*",
    r"\bacqua\b",
    r"\bfurieu\w*",
    r"\bdegel\w*",
    r"\bfond(?:re|u|ue|ait)\b",
    r"\bneige\b",
    r"\bl s\b",
    r"\bcascades?\b",
    r"\bmonte\b",
]

_EXPLICIT = [re.compile(pattern) for pattern in EXPLICIT_PATTERNS]
_INFO = [re.compile(pattern) for pattern in INFO_PATTERNS]
_HYDRO = [re.compile(pattern) for pattern in HYDRO_PATTERNS]


def normalize(text: str | None) -> str:
    """Lowercase, strip accents, keep letters/digits separated by single spaces."""
    if not text:
        return ""
    decomposed = unicodedata.normalize("NFD", text.lower())
    stripped = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
    return " ".join(re.sub(r"[^a-z0-9]+", " ", stripped).split())


def is_message_only_high_report(rank: int, is_descended: bool | None, comment: str | None) -> bool:
    if rank < 3 or is_descended is not False:
        return False
    text = normalize(comment)
    if not text:
        return False
    if any(p.search(text) for p in _EXPLICIT):
        return True
    return any(p.search(text) for p in _INFO) and not any(p.search(text) for p in _HYDRO)
