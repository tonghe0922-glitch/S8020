#!/usr/bin/env python3
"""Validate immutable, globally ordered Flyway versions per logical database."""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
DATABASE_ROOT = ROOT / "technical-platform/database"
MIGRATION = re.compile(r"^V(?P<version>\d+(?:_\d+)*)__[a-z0-9][a-z0-9_]*\.sql$")
PREFIXES = (
    "technical-platform/database/flyway/",
    "technical-platform/database/flyway-overlays/",
)


@dataclass(frozen=True, order=True)
class Version:
    parts: tuple[int, ...]

    @classmethod
    def parse(cls, raw: str) -> "Version":
        return cls(tuple(int(part) for part in raw.split("_")))

    def __str__(self) -> str:
        return ".".join(str(part) for part in self.parts)


@dataclass(frozen=True)
class Migration:
    database: str
    version: Version
    path: str


def command(*args: str) -> str:
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def logical_database(path: str) -> str | None:
    parts = Path(path).parts
    for marker in ("flyway", "flyway-overlays"):
        if marker in parts:
            index = parts.index(marker)
            if index + 1 < len(parts):
                return parts[index + 1]
    return None


def parse_path(path: str) -> Migration | None:
    if not path.endswith(".sql") or not path.startswith(PREFIXES):
        return None
    match = MIGRATION.match(Path(path).name)
    if match is None:
        raise ValueError(
            f"invalid Flyway filename {path}; expected V<version>__<snake_case>.sql"
        )
    database = logical_database(path)
    if database is None:
        raise ValueError(f"cannot determine logical database for {path}")
    return Migration(database, Version.parse(match.group("version")), path)


def current_paths() -> list[str]:
    return sorted(
        path.relative_to(ROOT).as_posix()
        for path in DATABASE_ROOT.rglob("V*__*.sql")
        if path.is_file()
    )


def base_paths(ref: str) -> list[str]:
    return [
        path
        for path in command("git", "ls-tree", "-r", "--name-only", ref).splitlines()
        if path.endswith(".sql") and path.startswith(PREFIXES)
    ]


def changed_migrations(ref: str) -> list[tuple[str, str]]:
    rows: list[tuple[str, str]] = []
    output = command("git", "diff", "--name-status", f"{ref}...HEAD", "--", *PREFIXES)
    for line in output.splitlines():
        if not line:
            continue
        columns = line.split("\t")
        status = columns[0]
        path = columns[-1]
        if path.endswith(".sql"):
            rows.append((status, path))
    return rows


def validate_unique(migrations: list[Migration], failures: list[str]) -> None:
    grouped: dict[str, dict[Version, list[str]]] = defaultdict(lambda: defaultdict(list))
    for migration in migrations:
        grouped[migration.database][migration.version].append(migration.path)
    for database, versions in sorted(grouped.items()):
        for version, paths in sorted(versions.items()):
            if len(paths) > 1:
                failures.append(
                    f"{database}: duplicate Flyway version {version}: {', '.join(paths)}"
                )


def validate_against_base(
        ref: str, current: list[Migration], failures: list[str]) -> None:
    base = [parse_path(path) for path in base_paths(ref)]
    base_migrations = [migration for migration in base if migration is not None]
    max_base: dict[str, Version] = {}
    for migration in base_migrations:
        previous = max_base.get(migration.database)
        if previous is None or migration.version > previous:
            max_base[migration.database] = migration.version

    current_by_path = {migration.path: migration for migration in current}
    for status, path in changed_migrations(ref):
        if status.startswith(("M", "D", "R", "C")):
            failures.append(
                f"published Flyway migration is immutable ({status}): {path}; add a new migration"
            )
            continue
        if not status.startswith("A"):
            continue
        migration = current_by_path.get(path)
        if migration is None:
            failures.append(f"added migration could not be parsed: {path}")
            continue
        previous = max_base.get(migration.database)
        if previous is not None and migration.version <= previous:
            failures.append(
                f"{path}: new version {migration.version} must be greater than "
                f"current {migration.database} maximum {previous}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-ref",
        help="Git ref used to enforce immutable existing migrations and increasing new versions",
    )
    args = parser.parse_args()

    failures: list[str] = []
    migrations: list[Migration] = []
    for path in current_paths():
        try:
            parsed = parse_path(path)
        except ValueError as error:
            failures.append(str(error))
            continue
        if parsed is not None:
            migrations.append(parsed)

    validate_unique(migrations, failures)
    if args.base_ref:
        try:
            validate_against_base(args.base_ref, migrations, failures)
        except subprocess.CalledProcessError as error:
            failures.append(
                f"cannot compare Flyway migrations with {args.base_ref}: {error}"
            )

    if failures:
        print("Flyway version guard FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1

    grouped: dict[str, list[Version]] = defaultdict(list)
    for migration in migrations:
        grouped[migration.database].append(migration.version)
    summary = ", ".join(
        f"{database}={len(versions)} (max {max(versions)})"
        for database, versions in sorted(grouped.items())
    )
    print(f"Flyway version guard PASS: {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
