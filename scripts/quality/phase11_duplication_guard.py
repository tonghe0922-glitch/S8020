#!/usr/bin/env python3
"""Enforce the P011-P016 HTTP-adapter duplicate-code reduction target.

The original six controllers repeated authorization, projection, audit,
idempotency and CRUD/action plumbing.  The remediation moves that plumbing into
Phase11ApiSupport and leaves each controller as a thin policy/permission map.

The metric deliberately tokenizes Java and ignores whitespace, comments, string
contents and process-specific type names, so formatting changes cannot game the
result.  It counts repeated 20-token windows across the six controllers.  The
accepted target is at least a 40% reduction from the pre-remediation baseline.
"""

from __future__ import annotations

import collections
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTROLLER_ROOT = (
    ROOT
    / "technical-platform"
    / "backend"
    / "apps"
    / "api"
    / "src"
    / "main"
    / "java"
    / "cn"
    / "shangjingu"
    / "platform"
    / "api"
    / "phase11"
)
CONTROLLERS = tuple(
    CONTROLLER_ROOT / f"P0{number}{name}Controller.java"
    for number, name in (
        (11, "Performance"),
        (12, "Promotion"),
        (13, "Reward"),
        (14, "Discipline"),
        (15, "Points"),
        (16, "CareCase"),
    )
)
WINDOW_SIZE = 20
BASELINE_DUPLICATED_WINDOWS = 4_843
MAX_DUPLICATED_WINDOWS = 2_905  # floor(4_843 * 0.60): >= 40% reduction
TOKEN = re.compile(
    r"[A-Za-z_$][A-Za-z0-9_$]*|\d+(?:\.\d+)?|==|!=|<=|>=|->|::|&&|\|\||"
    r"\+\+|--|[{}()\[\];,.?:+\-*/%<>=!]"
)
PROCESS_TYPE = re.compile(
    r"(?:Performance|Promotion|Reward|Discipline|PointLedger|Phase11CareCase)"
    r"[A-Za-z0-9_]*"
)


def strip_comments_and_strings(source: str) -> str:
    source = re.sub(r"/\*.*?\*/", " ", source, flags=re.DOTALL)
    source = re.sub(r"//[^\n]*", " ", source)
    source = re.sub(r'""".*?"""', " STRING ", source, flags=re.DOTALL)
    return re.sub(r'"(?:\\.|[^"\\])*"', " STRING ", source)


def normalized_tokens(path: Path) -> list[str]:
    source = strip_comments_and_strings(path.read_text(encoding="utf-8"))
    normalized: list[str] = []
    for token in TOKEN.findall(source):
        if re.fullmatch(r"P01[1-6]|p01[1-6]", token, flags=re.IGNORECASE):
            normalized.append("PROCESS")
        elif re.fullmatch(r"\d+(?:\.\d+)?", token):
            normalized.append("NUMBER")
        elif PROCESS_TYPE.fullmatch(token):
            normalized.append("PROCESS_TYPE")
        elif re.fullmatch(r"P01[1-6][A-Za-z0-9_]*Controller", token):
            normalized.append("PROCESS_CONTROLLER")
        else:
            normalized.append(token)
    return normalized


def duplicated_window_count() -> tuple[int, int]:
    windows: collections.Counter[tuple[str, ...]] = collections.Counter()
    for path in CONTROLLERS:
        if not path.is_file():
            raise FileNotFoundError(path.relative_to(ROOT))
        tokens = normalized_tokens(path)
        for index in range(0, len(tokens) - WINDOW_SIZE + 1):
            windows[tuple(tokens[index : index + WINDOW_SIZE])] += 1
    duplicated = sum(count for count in windows.values() if count > 1)
    return duplicated, sum(windows.values())


def main() -> int:
    duplicated, total = duplicated_window_count()
    reduction = 1 - (duplicated / BASELINE_DUPLICATED_WINDOWS)
    print(
        "PHASE-11 duplicate guard: "
        f"duplicated_windows={duplicated}, total_windows={total}, "
        f"baseline={BASELINE_DUPLICATED_WINDOWS}, reduction={reduction:.1%}"
    )
    if duplicated > MAX_DUPLICATED_WINDOWS:
        print(
            "P011-P016 duplicate burden exceeds the remediation threshold: "
            f"expected <= {MAX_DUPLICATED_WINDOWS}, got {duplicated}",
            file=sys.stderr,
        )
        return 1
    print("PHASE-11 duplicate guard PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
