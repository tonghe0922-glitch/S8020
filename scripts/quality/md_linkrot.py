#!/usr/bin/env python3
"""Validate repository-local paths referenced by Markdown documentation."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[2]
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")
INLINE_CODE = re.compile(r"`([^`\n]+)`")
REPOSITORY_PREFIXES = ("technical-platform/", "scripts/", "docs/", ".github/")
ROOT_FILES = {
    "AGENT.md",
    "ARCHITECTURE.md",
    "CONTRIBUTING.md",
    "DESIGN.md",
    "MIGRATION_PROVENANCE.md",
    "README.md",
    "SECURITY.md",
    "pom.xml",
}


def is_external(value: str) -> bool:
    lower = value.lower()
    return lower.startswith(("http://", "https://", "mailto:", "tel:", "#"))


def clean(value: str) -> str | None:
    value = unquote(value.strip())
    if not value or is_external(value) or "<" in value or ">" in value:
        return None
    value = value.split("#", 1)[0].split("?", 1)[0].strip()
    if not value or value.startswith(("/api/", "${", "$")):
        return None
    return value.rstrip(".,;:")


def resolve(document: Path, value: str, from_link: bool) -> Path | None:
    candidate = clean(value)
    if candidate is None:
        return None
    if from_link:
        return (document.parent / candidate).resolve()
    if candidate in ROOT_FILES or candidate.startswith(REPOSITORY_PREFIXES):
        return (ROOT / candidate).resolve()
    return None


def inside_root(path: Path) -> bool:
    try:
        path.relative_to(ROOT)
        return True
    except ValueError:
        return False


def main() -> int:
    failures: list[str] = []
    documents = sorted(
        path for path in ROOT.rglob("*.md") if ".git" not in path.parts
    )
    for document in documents:
        text = document.read_text(encoding="utf-8")
        checks = [(value, True) for value in MARKDOWN_LINK.findall(text)]
        checks.extend((value, False) for value in INLINE_CODE.findall(text))
        for value, from_link in checks:
            resolved = resolve(document, value, from_link)
            if resolved is None:
                continue
            if not inside_root(resolved):
                failures.append(
                    f"{document.relative_to(ROOT)}: path escapes repository: {value}"
                )
            elif not resolved.exists():
                failures.append(
                    f"{document.relative_to(ROOT)}: missing repository path: {value}"
                )

    if failures:
        print("Markdown linkrot guard failed:", file=sys.stderr)
        for failure in sorted(set(failures)):
            print(f"  - {failure}", file=sys.stderr)
        return 1
    print(f"Markdown linkrot guard PASS ({len(documents)} documents)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
