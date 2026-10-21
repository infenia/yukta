#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
"""One-time move of the backlog in docs/internal/*.mdx and perf/KNOWN-ISSUES.md to GitHub issues.

Usage: migrate-backlog.py extract [--ref <git ref>] [--out backlog.json]
       migrate-backlog.py summary <backlog.json>
       migrate-backlog.py apply <backlog.json> <owner/repo> [--project <owner>/<number>]
                                [--apply] [--map id-map.json]

extract  reads the pages and writes one record per item that is still open or partly done:
         open bugs, dogfood findings (and what is still open of fixed ones), review findings,
         the feature backlog, every risk, and one epic per design plan. Fixed, dropped and
         not-a-defect items stay in git history. Links to the internal pages become links to
         those pages at --ref (default origin/main), so nothing points at a deleted page.
summary  prints counts per source, type and module, for review before anything is created.
apply    creates the issues (a dry run unless --apply). Each body carries a
         <!-- yukta-legacy-id: X --> marker; an issue that already has it is skipped, so a
         re-run creates only what is missing. With --project, each new issue is also added to
         that project with its fields set. Writes id-map.json (legacy id -> issue URL), which is
         used to rewrite the links in the Contributing pages.

Review backlog.json by hand before applying: titles, types and fields come from free text.
Needs the gh CLI (for apply, a token that can write issues and, with --project, the project).
"""
import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
REPO_URL = "https://github.com/infenia/yukta"
MARKER = "<!-- yukta-legacy-id: {} -->"
MAX_BODY = 60000
WRITE_PAUSE = 1.5  # seconds between writes, under GitHub's secondary rate limit

# Section names on the pages -> Module field value (also the area label).
MODULES = {
    "boot": "boot", "build logic": "build", "build-logic": "build", "cli": "cli", "core": "core",
    "mcp": "mcp", "messaging": "messaging", "persistence postgresql": "persistence-postgres",
    "persistence-postgres": "persistence-postgres", "plugin api": "plugin-api",
    "plugin-api": "plugin-api", "plugins": "plugins", "plus": "plus", "ui": "ui", "web": "web",
    "workers": "workers", "dogfood tooling": "build",
}
SEVERITY_TO_PRIORITY = {"high": "P1", "medium": "P2", "low": "P3"}
PLANS = {
    "remote-workers": "Remote workers",
    "remediation": "Core remediation",
    "performance": "Core performance",
    "editions": "Editions (Community, Plus, Enterprise)",
    "releases": "Release and install",
}


# --- MDX helpers ---------------------------------------------------------------------------------

def git_show(ref, path):
    return subprocess.run(["git", "-C", str(ROOT), "show", f"{ref}:{path}"],
                          capture_output=True, text=True, check=True).stdout


def sections(text, level):
    """[(heading, body)] for the headings of exactly this level."""
    marker = "#" * level + " "
    parts = re.split(rf"^{re.escape(marker)}", text, flags=re.M)
    return [(p.split("\n", 1)[0].strip(), p.split("\n", 1)[1] if "\n" in p else "")
            for p in parts[1:]]


def split_row(line):
    cells = re.split(r"(?<!\\)\|", line.strip())[1:-1]
    return [c.strip().replace("\\|", "|") for c in cells]


def tables(text):
    """Every Markdown table in text as a list of dicts keyed by the header cells."""
    found, lines = [], text.split("\n")
    i = 0
    while i < len(lines):
        # The separator row is optional: some tables on the pages were written without one.
        if lines[i].startswith("|") and (i == 0 or not lines[i - 1].startswith("|")):
            header = split_row(lines[i])
            i += 1
            if i < len(lines) and re.match(r"^\|[-| :]+\|$", lines[i]):
                i += 1
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(dict(zip(header, split_row(lines[i]))))
                i += 1
            found.append(rows)
        else:
            i += 1
    return found


class Linker:
    """Rewrites site-relative links: /internal/* to the page at the pinned ref, the rest to main."""

    def __init__(self, ref_sha):
        self.ref = ref_sha

    def page(self, path, anchor=""):
        base = path.strip("/") or "index"
        if base.startswith("internal"):
            url = f"{REPO_URL}/blob/{self.ref}/docs/{base}.mdx"
        else:
            url = f"{REPO_URL}/blob/main/docs/{base}.mdx"
        return url + (f"#{anchor}" if anchor else "")

    def __call__(self, text):
        def repl(m):
            path, _, anchor = m.group(2).partition("#")
            return f"[{m.group(1)}]({self.page(path, anchor)})"
        text = re.sub(r"\[([^\]]+)\]\((/[^)\s]*)\)", repl, text)
        return text.replace("\\{", "{").replace("\\}", "}").replace("\\<", "<")


def module_of(name):
    key = name.strip().lower()
    if key in MODULES:
        return MODULES[key]
    for token in re.split(r"[,/ ]+", key):
        if token in MODULES:
            return MODULES[token]
    return None


OPEN_PART = re.compile(r"(?:\*\*Open:\*\*|\bStill open:|\bOpen:)\s*(.+)", re.S)


def short_title(text, limit=90):
    """An issue title from a free-text summary: the first clause of what is still open, if the
    text says what is open (rows of partly fixed items start with what was done), else of all."""
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text.strip())
    open_part = OPEN_PART.search(text)
    if open_part:
        text = open_part.group(1)
    text = re.sub(r"^(Partly fixed|Partly done)\.\s*", "", text)
    first = re.split(r"(?<=[a-z0-9`)])[.;] ", text, maxsplit=1)[0].rstrip(".")
    if len(first) > limit:
        first = first[:limit].rsplit(" ", 1)[0].rstrip(",;:(") + "…"
    return first[:1].upper() + first[1:]


def priority_of(severity):
    s = severity.lower()
    m = re.match(r"p([0-3])", s)
    if m:
        return f"P{m.group(1)}"
    return SEVERITY_TO_PRIORITY.get(s.split()[0] if s else "", None)


def record(legacy_id, title, type_, kind, module, body, source, labels=(), **fields):
    labels = list(dict.fromkeys([*([module] if module else []), *labels]))
    values = {"Kind": kind, "Module": module, "Legacy ID": legacy_id}
    values.update({k.replace("_", " "): v for k, v in fields.items() if v not in (None, "")})
    return {"legacy_id": legacy_id, "title": title, "type": type_, "labels": labels,
            "fields": {k: v for k, v in values.items() if v not in (None, "")},
            "body": body, "source": source, "parent": None}


# --- Extractors ----------------------------------------------------------------------------------

def bug_items(text, link, src):
    items = []
    for heading, body in sections(text, 2):
        module = module_of(heading)
        if module is None:
            continue
        for rows in tables(body):
            for row in rows:
                if row.get("Status", "").lower() != "open" or "ID" not in row:
                    continue
                summary = link(row["Summary"])
                security = row["ID"].startswith("S") or "security" in summary.lower()
                items.append(record(
                    row["ID"], short_title(row["Summary"]), "Bug", "Defect", module,
                    f"{summary}\n\n**Source:** {link(row.get('Source', ''))}",
                    src(f"#{heading.lower().replace(' ', '-')}"),
                    labels=["security"] if security else [],
                    Severity=row["Severity"] if row["Severity"] in ("High", "Medium", "Low") else None,
                    Priority=priority_of(row["Severity"])))
        for title, entry in sections(body, 4):
            m = re.match(r"(DF-\d+):\s*(.+)", title)
            if not m:
                continue
            fields = dict(re.findall(r"^- (\w[\w ]*):\s*(.*)$", entry, flags=re.M))
            status = fields.get("Status", "").lower()
            still_open = fields.get("Still open")
            if status.startswith("open"):
                legacy, name, kind = m.group(1), m.group(2), "Dogfood"
            elif still_open:
                legacy, name, kind = f"{m.group(1)}-rest", short_title(f"Open: {still_open}"), "Improvement"
            else:
                continue
            items.append(record(
                legacy, name[:1].upper() + name[1:], "Bug" if kind == "Dogfood" else "Task",
                kind, module, link(entry.strip()), src(f"#{m.group(1).lower()}"),
                labels=["dogfood"], Priority=priority_of(fields.get("Severity", ""))))
    return items


def review_items(text, link, src, known):
    """'Open findings' tables of the module reviews, minus ids already taken from the bugs page
    and rows that repeat a risk (their id is the risk's short name; the risk issue covers them)."""
    items = []
    for heading, body in sections(text, 2):
        module = module_of(heading)
        for sub, sub_body in sections(body, 3):
            if sub.endswith(" review"):
                for rows in tables(sub_body):
                    for row in rows:
                        rid, status = row.get("ID"), row.get("Status", "")
                        if not rid or rid in known or re.match(r"(done|fixed)", status.lower()):
                            continue
                        finding = row["Finding"]
                        prio = priority_of(finding)
                        finding_text = re.sub(r"^P[0-3][^.]*\.\s*", "", finding)
                        items.append(record(
                            rid, short_title(finding_text), "Task", "Improvement", module,
                            f"{link(finding_text)}\n\n**Impact:** {link(row.get('Impact', ''))}\n\n"
                            f"**Effort / fix:** {link(row.get('Effort / Fix', ''))}\n\n"
                            f"**Status when migrated:** {status}",
                            src(f"#{sub.lower().replace(' ', '-')}"), Priority=prio))
            if sub == "Feature backlog":
                for rows in tables(sub_body):
                    for row in rows:
                        if not row.get("ID", "").startswith("F") or row["Status"].strip() == "done":
                            continue
                        feature = row["Feature"]
                        open_part = OPEN_PART.search(feature)
                        if open_part and open_part.group(1).strip().lower().startswith("none"):
                            continue
                        items.append(record(
                            row["ID"], short_title(feature), "Feature", "Improvement",
                            module_of(row.get("Module", "")) or module,
                            f"{link(feature)}\n\n**Status when migrated:** {row['Status']}\n\n"
                            + (f"**Notes:** {link(row['Notes'])}" if row.get("Notes") else ""),
                            src("#feature-backlog")))
    return items


ACCORDION = re.compile(r'<Accordion title="([^"]*)">\s*(.*?)</Accordion>', re.S)


def risk_items(text, link, src):
    items = []
    for heading, body in sections(text, 2):
        module = module_of(heading)
        if module is None:
            continue
        for title, detail in ACCORDION.findall(body):
            meta = dict(re.findall(r"\*\*(Id|Category|Impact|Effort|Components)\*\*\s*`?([^·`\n]+)`?",
                                   detail))
            meta = {k: v.strip() for k, v in meta.items()}
            rest = re.sub(r"^\*\*Id\*\*.*$", "", detail, count=1, flags=re.M).strip()
            impact = int(meta.get("Impact", "0") or 0)
            category = meta.get("Category", "")
            items.append(record(
                f"risk:{module}/{meta.get('Id', title)}", title, "Task", "Risk", module,
                f"**Category** {category} · **Impact** {meta.get('Impact')} · "
                f"**Effort** {meta.get('Effort')} · **Components** {meta.get('Components', '-')}"
                f"\n\n{link(rest)}",
                src(f"#{heading.lower().replace(' ', '-')}"),
                labels=["risk", *(["security"] if category == "Security" else [])],
                Category=category if category in CATEGORIES else None,
                Impact=impact or None, Effort=int(meta.get("Effort", "0") or 0) or None,
                Priority="P1" if impact >= 4 else "P2" if impact == 3 else "P3"))
    return items


CATEGORIES = {"Security", "Correctness", "Reliability", "Availability", "Performance",
              "Scalability", "Evolvability", "Operability", "Governance"}


def perf_items(text, src, known):
    items = []
    for title, body in sections(text, 2):
        m = re.match(r"(\d+)\.\s*(.+)", title)
        if not m or "(fixed" in title or re.search(r"\bDF-\d+", title):
            continue
        name = re.sub(r"\s*\(.*\)$", "", m.group(2))
        legacy = f"perf-{m.group(1)}"
        if legacy in known:
            continue
        items.append(record(legacy, name.replace("`", ""), "Bug", "Perf", "build", body.strip(),
                            src(f"#{m.group(1)}"), labels=["perf"], Priority="P3"))
    return items


def plan_epics(ref, link):
    items = []
    for slug, name in PLANS.items():
        text = git_show(ref, f"docs/internal/plans/{slug}.mdx")
        desc = re.search(r'^description:\s*"(.*)"', text, flags=re.M)
        status = re.search(r"^Status:\s*(.+)$", text, flags=re.M)
        body = (f"{link(desc.group(1)) if desc else ''}\n\n"
                + (f"**Status when migrated:** {link(status.group(1))}\n\n" if status else "")
                + f"**Design doc:** [design/plans/{slug}.md]({REPO_URL}/blob/main/design/plans/{slug}.md)\n\n"
                "Open phases are tracked as sub-issues of this epic.")
        body = body.replace("](#", f"]({link.page(f'/internal/plans/{slug}')}#")
        items.append(record(f"plan:{slug}", f"{name} plan", "Epic", "Improvement", None, body,
                            link.page(f"/internal/plans/{slug}"), labels=["epic"]))
    return items


def extract(args):
    sha = subprocess.run(["git", "-C", str(ROOT), "rev-parse", args.ref], capture_output=True,
                         text=True, check=True).stdout.strip()
    link = Linker(sha)

    def source(page):
        return lambda anchor="": link.page(f"/internal/{page}", anchor.lstrip("#"))

    items = bug_items(git_show(sha, "docs/internal/bugs.mdx"), link, source("bugs"))
    known = {i["legacy_id"] for i in items} | {i["legacy_id"].removesuffix("-rest") for i in items}
    # Ids on the bugs page that are fixed must not come back from the review tables either.
    bugs_text = git_show(sha, "docs/internal/bugs.mdx")
    known |= {row["ID"] for rows in tables(bugs_text) for row in rows if "ID" in row}
    risks = risk_items(git_show(sha, "docs/internal/risks.mdx"), link, source("risks"))
    known |= {r["legacy_id"].split("/", 1)[1] for r in risks}
    items += review_items(git_show(sha, "docs/internal/improvements.mdx"), link,
                          source("improvements"), known)
    items += risks
    perf_src = lambda anchor="": f"{REPO_URL}/blob/{sha}/perf/KNOWN-ISSUES.md{anchor}"  # noqa: E731
    items += perf_items(git_show(sha, "perf/KNOWN-ISSUES.md"), perf_src, known)
    epics = plan_epics(sha, link)
    if any(i["legacy_id"] == "F11" for i in items):
        next(i for i in items if i["legacy_id"] == "F11")["parent"] = "plan:remote-workers"
    items = epics + items

    seen = collections.Counter(i["legacy_id"] for i in items)
    dupes = [k for k, n in seen.items() if n > 1]
    if dupes:
        sys.exit(f"duplicate legacy ids: {dupes}")
    for i in items:
        i["body"] = re.sub(r"\n{3,}", "\n\n", i["body"].strip())
        i["body"] = (f"{i['body']}\n\n---\nMigrated from the [internal docs]({i['source']}) "
                     f"(legacy id `{i['legacy_id']}`).\n{MARKER.format(i['legacy_id'])}")
        if len(i["body"]) > MAX_BODY:
            i["body"] = i["body"][:MAX_BODY] + "\n\n…truncated, see the source link.\n" + \
                MARKER.format(i["legacy_id"])
    out = {"ref": sha, "items": items}
    pathlib.Path(args.out).write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n",
                                      encoding="utf-8")
    print(f"wrote {len(items)} items to {args.out} (pages at {sha[:12]})")
    summarize(items)


def summarize(items):
    for key in ("type", "Kind", "Module"):
        counts = collections.Counter(
            (i[key] if key == "type" else i["fields"].get(key, "-")) for i in items)
        print(f"{key:7}", ", ".join(f"{k} {n}" for k, n in sorted(counts.items(), key=str)))
    long_titles = [i["legacy_id"] for i in items if len(i["title"]) > 100]
    if long_titles:
        print("titles over 100 characters:", ", ".join(long_titles))


# --- Apply ---------------------------------------------------------------------------------------

def gh_json(*args, body=None):
    for _ in range(120):
        result = subprocess.run(["gh", *args], input=None if body is None else json.dumps(body),
                                capture_output=True, text=True)
        if "rate limit" not in result.stderr.lower():
            break
        print("  rate limited, waiting 60s", flush=True)
        time.sleep(60)
    if result.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args[:3])}: {result.stderr.strip()}")
    return json.loads(result.stdout) if result.stdout.strip() else None


def existing_issues(repo):
    """legacy id -> (number, id, url) for issues that already carry a marker."""
    found = {}
    pages = gh_json("api", "--paginate", "--slurp", f"repos/{repo}/issues?state=all&per_page=100")
    for issue in (i for page in pages for i in page):
        m = re.search(r"<!-- yukta-legacy-id: (\S+) -->", issue.get("body") or "")
        if m and "pull_request" not in issue:
            found[m.group(1)] = (issue["number"], issue["id"], issue["html_url"])
    return found


class Project:
    def __init__(self, spec):
        self.owner, number = spec.split("/")
        self.number = number
        project = gh_json("project", "view", number, "--owner", self.owner, "--format", "json")
        self.id = project["id"]
        fields = gh_json("project", "field-list", number, "--owner", self.owner,
                         "--limit", "100", "--format", "json")["fields"]
        self.fields = {f["name"]: f for f in fields}

    def items(self):
        """url -> project item, for issues the project already holds (auto-add included)."""
        listing = gh_json("project", "item-list", self.number, "--owner", self.owner,
                          "--limit", "2000", "--format", "json")["items"]
        return {i["content"]["url"]: i for i in listing if i.get("content", {}).get("url")}

    def add(self, url, values, existing=None):
        """Add the issue (or reuse the item an auto-add workflow already made) and set fields."""
        item = (existing or {}).get(url)
        if item is None:
            try:
                item = gh_json("project", "item-add", self.number, "--owner", self.owner,
                               "--url", url, "--format", "json")
            except RuntimeError as e:
                if "already exists" not in str(e):
                    raise
                for _ in range(6):  # the listing lags the auto-add; each poll costs API quota
                    time.sleep(5)
                    item = self.items().get(url)
                    if item:
                        break
                else:
                    raise
        for name, value in {"Status": "Backlog", **values}.items():
            field = self.fields.get(name)
            if field is None:
                print(f"  warning: project has no field {name}", file=sys.stderr)
                continue
            args = ["project", "item-edit", "--id", item["id"], "--project-id", self.id,
                    "--field-id", field["id"]]
            if field.get("options"):
                option = next((o for o in field["options"] if o["name"] == str(value)), None)
                if option is None:
                    print(f"  warning: {name} has no option {value!r}", file=sys.stderr)
                    continue
                args += ["--single-select-option-id", option["id"]]
            elif isinstance(value, (int, float)):
                args += ["--number", str(value)]
            else:
                args += ["--text", str(value)]
            gh_json(*args)


def apply(args):
    data = json.loads(pathlib.Path(args.backlog).read_text(encoding="utf-8"))
    items = data["items"]
    done = existing_issues(args.repo)
    project = Project(args.project) if args.project else None
    if project:
        wanted = {"Status", *(k for i in items for k in i["fields"])}
        missing_fields = sorted(wanted - project.fields.keys())
        if missing_fields:
            sys.exit(f"project has no field {', '.join(missing_fields)}: run project-setup.sh first")
    id_map = {k: v[2] for k, v in done.items()}
    created = 0
    held = project.items() if project and args.apply else {}
    for item in items:
        lid = item["legacy_id"]
        if lid in done:
            # resume: an earlier run may have died before the project fields were set
            current = held.get(done[lid][2])
            if project and args.apply and current is not None and not current.get("status"):
                project.add(done[lid][2], item["fields"], held)
            continue
        print(f"{'create' if args.apply else 'would create'} [{item['type']}] {lid}: {item['title']}")
        if not args.apply:
            continue
        issue = gh_json("api", "-X", "POST", f"repos/{args.repo}/issues", "--input", "-", body={
            "title": item["title"], "body": item["body"], "labels": item["labels"],
            "type": item["type"]})
        done[lid] = (issue["number"], issue["id"], issue["html_url"])
        id_map[lid] = issue["html_url"]
        created += 1
        if project:
            project.add(issue["html_url"], item["fields"], held)
        time.sleep(WRITE_PAUSE)
    if args.apply:
        for item in items:
            parent = item.get("parent")
            if parent and parent in done and item["legacy_id"] in done:
                try:
                    gh_json("api", "-X", "POST", f"repos/{args.repo}/issues/{done[parent][0]}/sub_issues",
                            "--input", "-", body={"sub_issue_id": done[item["legacy_id"]][1]})
                except RuntimeError as e:  # already linked
                    print(f"  sub-issue {item['legacy_id']}: {e}", file=sys.stderr)
    pathlib.Path(args.map).write_text(json.dumps(id_map, indent=2, sort_keys=True) + "\n",
                                      encoding="utf-8")
    missing = len(items) - len([i for i in items if i["legacy_id"] in done])
    print(f"{created} created, {len(items) - missing - created} already there, "
          f"{missing} {'left' if not args.apply else 'failed'}; id map in {args.map}")


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("extract")
    p.add_argument("--ref", default="origin/main")
    p.add_argument("--out", default="backlog.json")
    p = sub.add_parser("summary")
    p.add_argument("backlog")
    p = sub.add_parser("apply")
    p.add_argument("backlog")
    p.add_argument("repo")
    p.add_argument("--project", help="<owner>/<number> of the project to add the issues to")
    p.add_argument("--apply", action="store_true")
    p.add_argument("--map", default="id-map.json")
    args = parser.parse_args(argv)
    if args.cmd == "extract":
        extract(args)
    elif args.cmd == "summary":
        summarize(json.loads(pathlib.Path(args.backlog).read_text(encoding="utf-8"))["items"])
    else:
        apply(args)


if __name__ == "__main__":
    main(sys.argv[1:])
