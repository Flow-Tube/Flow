#!/usr/bin/env python3
"""Fails when a function that uses NewPipeExtractor has no line covered by the unit tests.

Run:
  ./gradlew :app:createGithubDebugUnitTestCoverageReport -Pflow.unitTestCoverage
  python3 scripts/newpipe/coverage_gate.py
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io/github/aedev/flow"
DEFAULT_REPORT = ROOT / "app/build/reports/coverage/test/github/debug/report.xml"
LIST = Path(__file__).with_name("functions.txt")


def load_list():
    entries = []
    for line in LIST.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        kind, path, name = line.split()
        entries.append((kind, path, name))
    return entries


def method_matches(method_name, name):
    # Kotlin mangles internal (`name$app_githubDebug`), default-argument (`name$default`) and
    # value-class (`name-abc123`) variants; any of them running means the function ran.
    return method_name == name or method_name.startswith(name + "$") or method_name.startswith(name + "-")


def covered_lines(report, path, name):
    package = f"{PACKAGE}/{path.rsplit('/', 1)[0]}" if "/" in path else PACKAGE
    source = path.rsplit("/", 1)[-1]
    found = False
    covered = 0
    for pkg in report.iter("package"):
        if pkg.get("name") != package:
            continue
        for cls in pkg.iter("class"):
            if cls.get("sourcefilename") != source:
                continue
            for method in cls.iter("method"):
                if not method_matches(method.get("name"), name):
                    continue
                found = True
                for counter in method.iter("counter"):
                    if counter.get("type") == "LINE":
                        covered += int(counter.get("covered"))
    return found, covered


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    parser.add_argument("--all", action="store_true", help="also list covered functions")
    args = parser.parse_args()
    if not args.report.exists():
        sys.exit(f"No coverage report at {args.report}. Run the Gradle task in this file's docstring first.")
    report = ET.parse(args.report).getroot()

    missing, gone, ok = [], [], []
    entries = load_list()
    for kind, path, name in entries:
        if kind != "test":
            continue
        found, covered = covered_lines(report, path, name)
        if not found:
            gone.append((path, name))
        elif covered == 0:
            missing.append((path, name))
        else:
            ok.append((path, name, covered))

    counts = {}
    for kind, _, _ in entries:
        counts[kind] = counts.get(kind, 0) + 1
    print(
        f"{len(ok)}/{counts.get('test', 0)} tested functions covered; "
        f"not gated: {counts.get('delete', 0)} delete, {counts.get('ui', 0)} ui, {counts.get('device', 0)} device"
    )
    if args.all:
        for path, name, covered in ok:
            print(f"  ok      {path} {name} ({covered} lines)")
    for path, name in gone:
        print(f"  absent  {path} {name} (renamed, moved or deleted: update functions.txt)")
    for path, name in missing:
        print(f"  MISSING {path} {name}")
    sys.exit(1 if missing or gone else 0)


if __name__ == "__main__":
    main()
