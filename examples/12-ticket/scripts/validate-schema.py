#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""validate-schema.py NAME FILE: exit 0 when FILE (JSON) is valid against schemas/NAME.schema.json, else 1 and up to 10 error lines.
Needs the python3 `jsonschema` package (4.18 or newer, for cross-file $ref)."""
import json, pathlib, sys

try:
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource
except ImportError:
    sys.exit("validate-schema: needs python3 jsonschema >= 4.18 (pip install jsonschema)")

dir_ = pathlib.Path(__file__).resolve().parent.parent / "schemas"


def main(argv):
    if len(argv) != 2:
        sys.exit("usage: validate-schema.py NAME FILE")
    name, file = argv
    schema_file = dir_ / f"{name}.schema.json"
    if not schema_file.is_file():
        sys.exit(f"validate-schema: no schema '{name}' (have: {', '.join(sorted(p.name[:-12] for p in dir_.glob('*.schema.json')))})")
    registry = Registry().with_resources(
        (p.name, Resource.from_contents(json.loads(p.read_text()))) for p in dir_.glob("*.schema.json"))
    try:
        data = json.loads(pathlib.Path(file).read_text())
    except (OSError, ValueError) as e:
        print(f"{file}: not readable JSON: {e}")
        return 1
    errors = sorted(Draft202012Validator(json.loads(schema_file.read_text()), registry=registry).iter_errors(data),
                    key=lambda e: list(map(str, e.absolute_path)))
    for e in errors[:10]:
        print(f"{file}: /{'/'.join(map(str, e.absolute_path))}: {e.message[:200]}")
    return 1 if errors else 0


sys.exit(main(sys.argv[1:]))
