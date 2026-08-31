#!/usr/bin/env python3
"""Fail when a checked-in Markdown link points at a missing local target."""

from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parent.parent
LINK = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
MARKDOWN = sorted(
    path
    for path in ROOT.rglob("*.md")
    if not any(part in {".git", ".gradle", "build", "run"} for part in path.parts)
)


def target_path(source: Path, raw_target: str) -> Path | None:
    target = raw_target.strip().strip("<>").split(maxsplit=1)[0]
    if not target or target.startswith(("#", "http://", "https://", "mailto:")):
        return None
    path_text = unquote(target.split("#", 1)[0])
    return (source.parent / path_text).resolve()


failures: list[str] = []
for document in MARKDOWN:
    for line_number, line in enumerate(document.read_text(encoding="utf-8").splitlines(), 1):
        for match in LINK.finditer(line):
            target = target_path(document, match.group(1))
            if target is not None and not target.exists():
                failures.append(
                    f"{document.relative_to(ROOT)}:{line_number}: missing {target.relative_to(ROOT)}"
                )

if failures:
    print("Documentation link check failed:", file=sys.stderr)
    print("\n".join(failures), file=sys.stderr)
    raise SystemExit(1)

print(f"Checked {len(MARKDOWN)} Markdown files; all local links resolve.")
