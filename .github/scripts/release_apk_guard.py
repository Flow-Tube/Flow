#!/usr/bin/env python3
"""Checks what R8 left in a release APK for the code that is reached by reflection.

Unit tests run before R8, so they cannot see a Gson model that R8 merged away or a generic
signature it stripped (#996, #964). This reads the shrunk dex instead and compares it with
.github/release-apk-guard.json:

- gsonTargets: the classes handed to Gson.fromJson as a Class literal;
- typeTokens: the anonymous TypeToken subclasses, each of which must keep its generic signature;
- modelFieldSignatures: the generic fields of the data models, which must keep their signature;
- reflectCallers, when listed: the only methods outside kotlin.reflect that may call kotlin-reflect's full API;
- absentPackages: libraries that must not ship;
- every language the app is translated into (values-*/strings.xml) must be in resources.arsc.

Usage: release_apk_guard.py APK [APK ...]          check
       release_apk_guard.py --print APK            print the facts of one APK as JSON
       release_apk_guard.py --update APK           rewrite the expectations from APK, after an intended change
"""

import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import zipfile

REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
EXPECTED = REPOSITORY / ".github" / "release-apk-guard.json"
TYPE_TOKEN = "Lcom/google/gson/reflect/TypeToken;"
GSON_FROM_JSON = "Lcom/google/gson/Gson;.fromJson:"
REFLECT_FULL = ("Lkotlin/reflect/full/", "Lkotlin/reflect/jvm/")
# Code units between a Class literal and the fromJson call that receives it.
FROM_JSON_REACH = 24
CLASS_HEADER = re.compile(r"^Class #(\d+)\s+-\s*$")
CLASS_ANNOTATIONS = re.compile(r"^Class #(\d+) annotations:\s*$")
LANGUAGE = re.compile(r"[a-z]{2,3}|b\+[A-Za-z0-9+]+")
REGION = re.compile(r"r[A-Z]{2}")
FIELD_ANNOTATIONS = re.compile(r"^Annotations on field #\d+ '([^']+)'")
# Gson models live here; a field there that loses its generic signature deserializes raw (#996).
MODEL_PACKAGE = "Lio/github/aedev/flow/data/"


def build_tool(name: str) -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        properties = pathlib.Path("local.properties")
        if properties.exists():
            for line in properties.read_text().splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip()
    if not sdk:
        sys.exit(f"release_apk_guard: set ANDROID_HOME so {name} can be found")
    tools = sorted(
        (path for path in pathlib.Path(sdk, "build-tools").glob(f"*/{name}")),
        key=lambda path: [int(part) if part.isdigit() else 0 for part in re.split(r"[.-]", path.parent.name)],
    )
    if not tools:
        sys.exit(f"release_apk_guard: no {name} under {sdk}/build-tools")
    return str(tools[-1])


def lines(command: list, pattern: str):
    """The lines of [command]'s output that match [pattern], filtered by grep so a 200 MB dump stays fast."""
    dump = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    matches = subprocess.Popen(["grep", "-E", pattern], stdin=dump.stdout, stdout=subprocess.PIPE, text=True, errors="replace")
    dump.stdout.close()
    yield from matches.stdout
    matches.wait()
    if dump.wait() != 0:
        sys.exit(f"release_apk_guard: {' '.join(command)} failed")


def apk_locales(apk: pathlib.Path) -> set:
    """The locales resources.arsc carries resources for, as resource qualifiers (pt-rBR, b+yue+Hant)."""
    output = subprocess.run([build_tool("aapt2"), "dump", "configurations", str(apk)], capture_output=True, text=True, check=True).stdout
    found = set()
    for config in output.split():
        parts = config.split("-")
        if not LANGUAGE.fullmatch(parts[0]):
            continue
        found.add("-".join(parts[:2]) if len(parts) > 1 and REGION.fullmatch(parts[1]) else parts[0])
    return found


def app_locales() -> set:
    """The languages the app is translated into: every values-* folder Weblate writes a strings.xml to."""
    folders = (REPOSITORY / "app" / "src" / "main" / "res").glob("values-*/strings.xml")
    return {path.parent.name.removeprefix("values-") for path in folders}


def facts(apk: pathlib.Path) -> dict:
    tool = build_tool("dexdump")
    superclass = {}
    signatures = {}
    field_signatures = set()
    gson_targets = set()
    reflect_callers = set()
    classes = []
    with tempfile.TemporaryDirectory() as folder, zipfile.ZipFile(apk) as archive:
        for name in sorted(n for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)):
            dex = archive.extract(name, folder)
            by_index = {}
            index = None
            current_class = None
            current_method = ""
            recent = []
            code = r"^Class #[0-9]+ +-|^  Class descriptor  :|^  Superclass        :|\|\[|const-class|Gson;\.fromJson:|Lkotlin/reflect/(full|jvm)/"
            for line in lines([tool, "-d", dex], code):
                match = CLASS_HEADER.match(line)
                if match:
                    index = match.group(1)
                    continue
                if line.startswith("  Class descriptor  : '"):
                    current_class = line.split("'")[1]
                    classes.append(current_class)
                    by_index[index] = current_class
                    continue
                if line.startswith("  Superclass        : '"):
                    superclass[current_class] = line.split("'")[1]
                    continue
                bar = line.find("|")
                if bar < 0:
                    continue
                if line.startswith("[", bar + 1):
                    current_method = line[line.find("] ", bar) + 2 : line.find(":(", bar)]
                    recent = []
                    continue
                if line[bar + 5 : bar + 7] != ": ":
                    continue
                offset = int(line[bar + 1 : bar + 5], 16)
                instruction = line[bar + 7 :]
                if instruction.startswith("const-class"):
                    recent.append((offset, instruction.split(", ", 1)[1].split(" //")[0]))
                elif GSON_FROM_JSON in instruction:
                    gson_targets.update(target for at, target in recent if offset - at <= FROM_JSON_REACH)
                if any(prefix in instruction for prefix in REFLECT_FULL) and not current_method.startswith("kotlin.reflect."):
                    reflect_callers.add(current_method.rsplit(".", 1)[-1])
            tokens = {i for i, c in by_index.items() if superclass.get(c) == TYPE_TOKEN}
            models = {i for i, c in by_index.items() if c.startswith(MODEL_PACKAGE)}
            section = None
            target = None
            for line in lines([tool, "-a", dex], r"^Class #[0-9]+ annotations:|^Annotations on|dalvik/annotation/Signature"):
                match = CLASS_ANNOTATIONS.match(line)
                if match:
                    section, target = match.group(1), None
                    continue
                if line.startswith("Annotations on"):
                    field = FIELD_ANNOTATIONS.match(line)
                    if line.startswith("Annotations on class") and section in tokens:
                        target = by_index[section]
                    elif field and section in models:
                        target = f"field {by_index[section]}.{field.group(1)}"
                    else:
                        target = None
                    continue
                if target and target.startswith("field "):
                    field_signatures.add(target.removeprefix("field "))
                elif target:
                    signatures[target] = signatures.get(target, "") + line
    type_tokens = sorted(name for name, parent in superclass.items() if parent == TYPE_TOKEN)
    return {
        "gsonTargets": sorted(gson_targets),
        "typeTokens": type_tokens,
        "typeTokensWithoutSignature": [
            name for name in type_tokens if "Lcom/google/gson/reflect/TypeToken<" not in signatures.get(name, "")
        ],
        "modelFieldSignatures": sorted(field_signatures),
        "locales": sorted(apk_locales(apk)),
        "reflectCallers": sorted(reflect_callers),
        "classes": classes,
    }


def check(apk: pathlib.Path, expected: dict) -> list:
    found = facts(apk)
    problems = []
    if found["gsonTargets"] != expected["gsonTargets"]:
        problems.append(
            "Gson.fromJson targets changed. Missing: "
            f"{sorted(set(expected['gsonTargets']) - set(found['gsonTargets']))}, new: "
            f"{sorted(set(found['gsonTargets']) - set(expected['gsonTargets']))}. "
            "A missing one means R8 merged or removed a Gson model (#996): keep it in proguard-rules.pro."
        )
    if found["typeTokens"] != expected["typeTokens"]:
        problems.append(
            "TypeToken subclasses changed. Missing: "
            f"{sorted(set(expected['typeTokens']) - set(found['typeTokens']))}, new: "
            f"{sorted(set(found['typeTokens']) - set(expected['typeTokens']))}."
        )
    if found["typeTokensWithoutSignature"]:
        problems.append(f"TypeToken subclasses lost their generic signature (#964): {found['typeTokensWithoutSignature']}")
    if "modelFieldSignatures" in expected:
        lost = sorted(set(expected["modelFieldSignatures"]) - set(found["modelFieldSignatures"]))
        if lost:
            problems.append(f"Fields under {MODEL_PACKAGE} lost their generic signature (#996): {lost[:20]}")
    if problems:
        problems.append(f"If the change is intended, run: python3 .github/scripts/release_apk_guard.py --update {apk}")
    unexpected = sorted(set(found["reflectCallers"]) - set(expected.get("reflectCallers", found["reflectCallers"])))
    if unexpected:
        problems.append(f"New callers of kotlin-reflect's full API, which release builds do not ship: {unexpected}")
    translated = app_locales()
    missing = sorted(translated - set(found["locales"]))
    if missing:
        problems.append(f"Translations missing from the APK: {missing}")
    for package in expected.get("absentPackages", []):
        shipped = [name for name in found["classes"] if name.startswith(package)]
        if shipped:
            problems.append(f"{len(shipped)} classes from {package} are in the APK, e.g. {shipped[0]}")
    return problems


def main(arguments: list) -> int:
    if arguments[:1] == ["--print"]:
        found = facts(pathlib.Path(arguments[1]))
        found["classes"] = len(found["classes"])
        print(json.dumps(found, indent=2))
        return 0
    if arguments[:1] == ["--update"]:
        found = facts(pathlib.Path(arguments[1]))
        expected = json.loads(EXPECTED.read_text())
        for key in ("gsonTargets", "typeTokens", "modelFieldSignatures"):
            expected[key] = found[key]
        if "reflectCallers" in expected:
            expected["reflectCallers"] = found["reflectCallers"]
        EXPECTED.write_text(json.dumps(expected, indent=2) + "\n")
        return 0
    if not arguments:
        print(__doc__)
        return 2
    expected = json.loads(EXPECTED.read_text())
    failed = False
    for path in arguments:
        problems = check(pathlib.Path(path), expected)
        name = pathlib.Path(path).name
        if problems:
            failed = True
            for problem in problems:
                print(f"::error::{name}: {problem}")
        else:
            print(f"{name}: R8 output matches {EXPECTED.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
