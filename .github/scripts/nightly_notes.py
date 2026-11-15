#!/usr/bin/env python3
"""Checks the nightly notes, or merges them into the nightly release body.

Each file in .github/nightly-notes/ holds one PR's user-visible changes as `## <Section>` headings
followed by `- ` bullets. The in-app update screen (ReleaseNotes.kt) shows the intro paragraph, then
one group per `##` section, so the merged body keeps exactly that shape.

Nightly releases group the notes under `# Run N`, newest run first, so the app can show only the
runs newer than the installed build. A run map (`{file name: run}`, kept as a release asset) records
the run that first shipped each note; a note keeps that run even when its file is edited later.

    nightly_notes.py --check
    nightly_notes.py --intro "Built from ..." > body.md
    nightly_notes.py --intro "Built from ..." --run 2481 --previous-run 2480 \
        --runs-in old-runs.json --runs-out nightly-note-runs.json > body.md
"""

import argparse
import json
import pathlib
import re
import sys

SECTIONS = ("New", "Improved", "Fixed")
NOTES_DIR = pathlib.Path(__file__).resolve().parents[1] / "nightly-notes"
FILE_NAME = re.compile(r"\d{4}-\d{2}-\d{2}-[a-z0-9][a-z0-9.-]*\.md")


def read_note(path):
    sections = {}
    errors = []
    current = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        where = f"{path.name}:{number}"
        if not line:
            continue
        if "—" in line:
            errors.append(f"{where}: no em-dashes in user-facing copy")
        if line.startswith("## "):
            current = line[3:].strip()
            if current not in SECTIONS:
                errors.append(f"{where}: section '{current}' must be one of {', '.join(SECTIONS)}")
            sections.setdefault(current, [])
        elif line.startswith("- "):
            if current is None:
                errors.append(f"{where}: bullet before any '## Section' heading")
            else:
                sections[current].append(line[2:].strip())
        else:
            errors.append(f"{where}: expected a '## Section' heading or a '- ' bullet")
    if not any(sections.values()):
        errors.append(f"{path.name}: has no bullets")
    return sections, errors


def update_run_map(names, old_map, run, previous_run):
    if old_map is None:
        return {name: previous_run for name in names}
    return {name: old_map.get(name, run) for name in names}


def render_sections(notes):
    merged = {section: [] for section in SECTIONS}
    for _, sections in notes:
        for section, items in sections.items():
            merged.setdefault(section, []).extend(items)
    lines = []
    for section in SECTIONS:
        if merged[section]:
            lines += ["", f"## {section}", *(f"- {item}" for item in merged[section])]
    return lines


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="only validate the notes")
    parser.add_argument("--intro", default="", help="paragraph shown above the sections")
    parser.add_argument("--run", type=int, help="this nightly's run number; groups the body by run")
    parser.add_argument("--previous-run", type=int, help="run every note gets when there is no run map yet")
    parser.add_argument("--runs-in", type=pathlib.Path, help="run map from the previous nightly, if any")
    parser.add_argument("--runs-out", type=pathlib.Path, help="where to write the updated run map")
    parser.add_argument("--notes-dir", type=pathlib.Path, default=NOTES_DIR, help=argparse.SUPPRESS)
    args = parser.parse_args()

    paths = sorted(path for path in args.notes_dir.glob("*.md") if path.name != "README.md")
    notes = []
    errors = []
    # File names start with the date, so reversed order lists the newest changes first.
    for path in reversed(paths):
        if not FILE_NAME.fullmatch(path.name):
            errors.append(f"{path.name}: name must be YYYY-MM-DD-<branch>.md in lowercase")
        sections, problems = read_note(path)
        errors += problems
        notes.append((path.name, sections))

    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    if args.check:
        print(f"{len(paths)} nightly notes are valid.")
        return 0

    lines = [args.intro] if args.intro else []
    if args.run is None:
        lines += render_sections(notes)
    else:
        old_map = None
        if args.runs_in and args.runs_in.is_file():
            old_map = json.loads(args.runs_in.read_text(encoding="utf-8"))
        previous_run = args.previous_run if args.previous_run is not None else args.run
        runs = update_run_map([name for name, _ in notes], old_map, args.run, previous_run)
        for run in sorted(set(runs.values()), reverse=True):
            lines += ["", f"# Run {run}", *render_sections([note for note in notes if runs[note[0]] == run])]
        if args.runs_out:
            args.runs_out.write_text(json.dumps(runs, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("\n".join(lines).strip())
    return 0


if __name__ == "__main__":
    sys.exit(main())
