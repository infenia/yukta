# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""Shared helpers of the ticketkit scripts: glob matching, the model table (design 3.1), run-dir access."""
import json, pathlib, re, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
KIT = HERE.parent
GUARD_PATHS = ["config/**", "build-logic/**", "gradle/**", ".github/**", ".claude/**", "examples/11-dogfood/**"]


def die(msg, code=1):
    print(msg, file=sys.stderr)
    sys.exit(code)


def load(path):
    try:
        return json.loads(pathlib.Path(path).read_text())
    except (OSError, ValueError) as e:
        die(f"cannot read {path}: {e}")


def save(path, data):
    pathlib.Path(path).write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def glob_re(glob):
    """`**` crosses directories, `*` and `?` do not; `**/` also matches nothing."""
    out, i = "", 0
    while i < len(glob):
        if glob.startswith("**/", i):
            out += "(?:.*/)?"; i += 3
        elif glob.startswith("**", i):
            out += ".*"; i += 2
        elif glob[i] == "*":
            out += "[^/]*"; i += 1
        elif glob[i] == "?":
            out += "[^/]"; i += 1
        else:
            out += re.escape(glob[i]); i += 1
    return re.compile(out + r"\Z")


def matches(path, globs):
    return any(glob_re(g).match(path) for g in globs)


def module_of(path):
    """The unit a path belongs to: plugins/<a>/<b>, plus/<a>, else the first directory (a root file is its own unit)."""
    p = path.split("/")
    n = 3 if p[0] == "plugins" else 2 if p[0] == "plus" else 1
    return "/".join(p[:n]) if len(p) > n else p[0]


RISK = ["low", "medium", "high"]


def max_risk(*rs):
    return max(rs, key=RISK.index)


def model_table(complexity, risk, tier_mode="two_tier", override=None):
    """Design 3.1. Returns {role: {model, effort} or None}. S+low is its own row; L or high risk is the strong row."""
    m = lambda model, effort: {"model": model, "effort": effort}
    if complexity == "L" or risk == "high":
        t = dict(architect=m("opus", "high"), test_writer=m("sonnet", "medium"), implementer=m("haiku", "medium"),
                 implementer_tricky=m("sonnet", "high"), reviewer=m("opus", "medium"))
    elif complexity == "S" and risk == "low":
        t = dict(architect=m("sonnet", "medium"), test_writer=None, implementer=m("haiku", "medium"),
                 implementer_tricky=m("sonnet", "medium"), reviewer=m("sonnet", "low"))
    else:
        t = dict(architect=m("sonnet", "high"), test_writer=m("sonnet", "low"), implementer=m("haiku", "medium"),
                 implementer_tricky=m("sonnet", "medium"), reviewer=m("sonnet", "medium"))
    if tier_mode == "single":
        t["implementer"] = t["implementer_tricky"] = t["architect"]
    if override:
        t["implementer"] = t["implementer_tricky"] = override
    return t


def tests_mode_for(kind, complexity, risk):
    """Design 3.3 (decided by code, from the kind and size). architect_code is allowed later by validate-spec, not chosen here."""
    if kind in ("docs", "chore"):
        return "existing"
    if kind == "bug":
        return "repro_only"
    if kind == "feature" and complexity == "S" and risk == "low":
        return "implementer_writes"
    return "cases_to_code"


def git(*args, cwd=None, check=True):
    r = subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)
    if check and r.returncode:
        die(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r


def cost_by_role(run):
    """({role: usd}, total) from RUN/events.jsonl."""
    by, total = {}, 0.0
    f = pathlib.Path(run) / "events.jsonl"
    for l in (f.read_text().splitlines() if f.is_file() else []):
        e = json.loads(l)
        if e.get("cost_usd"):
            by[e["role"]] = by.get(e["role"], 0) + e["cost_usd"]
            total += e["cost_usd"]
    return by, total
