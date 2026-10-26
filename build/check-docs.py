#!/usr/bin/env python3
"""Check current-doc links and the locked decision set. No system access."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"
CURRENT = [ROOT / "README.md", *sorted(DOCS.glob("*.md"))]


def fail(msg: str) -> None:
    print(f"DOC CHECK FAIL: {msg}", file=sys.stderr)
    raise SystemExit(1)


def check_links() -> None:
    pattern = re.compile(r"\[[^\]]+\]\(([^)]+)\)")
    for path in CURRENT:
        text = path.read_text()
        for raw in pattern.findall(text):
            target = raw.split("#", 1)[0].strip()
            if not target or target.startswith(("http://", "https://", "mailto:")):
                continue
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                fail(f"{path.relative_to(ROOT)} -> missing {target}")


def check_authority() -> None:
    decisions = (DOCS / "decisions.md").read_text()
    design = (DOCS / "design-v1.5.md").read_text()
    if "1A 2B 3A 4B 5A 6B 7A 8A 9A 10A 11A 12A 13A 14B 15A" not in decisions:
        fail("decisions.md does not contain the locked combo")
    for token in [
        "1A",
        "2B",
        "3A",
        "4B",
        "5A",
        "6B",
        "7A",
        "8A",
        "9A",
        "10A",
        "11A",
        "12A",
        "13A",
        "14B",
        "15A",
    ]:
        if token not in design:
            fail(f"design-v1.5.md does not mention decision {token}")
    if "P1" not in (ROOT / "README.md").read_text():
        fail("README.md does not state P1 status")


def check_no_legacy_links() -> None:
    for path in CURRENT:
        text = path.read_text()
        if "](notes/" in text or "](../notes/" in text:
            fail(f"current doc links to archived notes/: {path.relative_to(ROOT)}")


def main() -> int:
    check_links()
    check_authority()
    check_no_legacy_links()
    print("doc check: ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
