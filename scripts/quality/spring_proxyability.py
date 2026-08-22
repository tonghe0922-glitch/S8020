#!/usr/bin/env python3
"""Fail when Spring-managed Java classes cannot be proxied.

Spring may create class-based proxies for stereotypes such as @Repository,
@Service, @Component, @Controller and @Configuration. A final class cannot be
subclassed by CGLIB, so such declarations are prohibited across main sources.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOT = ROOT / "technical-platform" / "backend"
STEREOTYPE = re.compile(
    r"@(?:Component|Service|Repository|Controller|RestController|Configuration|"
    r"ControllerAdvice|RestControllerAdvice)\b"
)
CLASS_DECLARATION = re.compile(
    r"\b(?:(?:public|protected|private|static|abstract|sealed|non-sealed)\s+)*"
    r"(?P<final>final\s+)?class\s+(?P<name>[A-Za-z_$][\w$]*)\b"
)
TRANSACTIONAL = re.compile(r"@Transactional\b")
FINAL_METHOD = re.compile(
    r"\b(?:(?:public|protected|private|static|synchronized|native|strictfp)\s+)*"
    r"final\s+[\w<>, ?\[\].@]+\s+[A-Za-z_$][\w$]*\s*\("
)


def strip_line_comment(line: str) -> str:
    return line.split("//", 1)[0]


def scan(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    failures: list[str] = []
    stereotype_pending = False
    transactional_pending = False

    for number, raw_line in enumerate(lines, start=1):
        line = strip_line_comment(raw_line)
        stripped = line.strip()
        if not stripped:
            continue

        if STEREOTYPE.search(line):
            stereotype_pending = True
        if TRANSACTIONAL.search(line):
            transactional_pending = True

        declaration = CLASS_DECLARATION.search(line)
        if declaration:
            if stereotype_pending and declaration.group("final"):
                failures.append(
                    f"{path.relative_to(ROOT)}:{number}: Spring-managed class "
                    f"{declaration.group('name')} must not be final"
                )
            stereotype_pending = False
            transactional_pending = False
            continue

        if transactional_pending and FINAL_METHOD.search(line):
            failures.append(
                f"{path.relative_to(ROOT)}:{number}: @Transactional method must not be final"
            )
            transactional_pending = False
            continue

        if stripped.startswith("@") or stripped.startswith("*") or stripped.startswith("/*"):
            continue

        # An unrelated declaration or statement ends the annotation look-ahead.
        if ";" in stripped or "(" in stripped or "{" in stripped:
            stereotype_pending = False
            transactional_pending = False

    return failures


def main() -> int:
    failures: list[str] = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        if "/target/" in path.as_posix() or "/src/test/" in path.as_posix():
            continue
        failures.extend(scan(path))

    if failures:
        print("Spring proxyability guard failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print("Spring proxyability guard PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
