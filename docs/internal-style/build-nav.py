#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# SPDX-License-Identifier: Apache-2.0

"""Regenerates the navigation and redirects of docs.json from the page map. Run from docs/: python3 internal-style/build-nav.py"""
import json, glob, os
d = json.load(open('docs.json'))
def exists(r): return os.path.exists(r + '.mdx')
def grp(name, pages, **kw):
    pages = [p for p in pages if exists(p)]
    return dict(group=name, public=True, pages=pages, **kw)
L = lambda *a: list(a)
tabs = [
 dict(tab="Start here", groups=[grp("Start here", L("index","get-started/install","learn/your-first-workflow","examples"))]),
 dict(tab="Learn", groups=[grp("Tutorials", L(
   "learn/your-first-workflow","learn/mix-rules-and-ai","learn/let-an-assistant-build-a-workflow",
   "learn/add-a-supervisor","learn/set-up-supervision-with-an-assistant","learn/ask-for-approval","learn/playground"))]),
 dict(tab="Guides", groups=[
   grp("Build workflows", L("guides/build/define-a-workflow","guides/build/route-and-branch","guides/build/loop-and-retry","guides/build/use-variables-and-expressions","guides/build/validate-and-roll-back","guides/build/schedule-a-workflow","guides/build/run-a-sub-workflow","guides/build/handle-failures","guides/build/control-a-run")),
   grp("Work with AI", L("guides/ai/connect-an-assistant","guides/ai/configure-with-an-assistant","guides/ai/call-an-llm","guides/ai/use-tools-and-mcp-servers","guides/ai/classify-gate-and-score","guides/ai/set-token-budgets","guides/ai/keep-session-memory","guides/ai/ask-for-input")),
   grp("Supervise runs", L("guides/supervise/assign-a-supervisor","guides/supervise/write-a-rules-supervisor","guides/supervise/write-an-agent-supervisor","guides/supervise/write-a-workflow-supervisor","guides/supervise/build-a-hierarchy","guides/supervise/replay-a-supervisor","guides/supervise/route-input-requests","guides/supervise/use-the-control-dashboard")),
   grp("Run and operate", L("guides/operate/deploy","guides/operate/upgrade-and-roll-back","guides/operate/persist-sessions","guides/operate/run-highly-available","guides/operate/add-remote-workers","guides/operate/secure-the-server","guides/operate/observe-runs","guides/operate/tune-capacity-and-limits","guides/operate/troubleshoot","guides/operate/use-the-web-ui","guides/operate/use-the-cli")),
   grp("Extend", L("guides/extend/write-a-plugin")),
 ]),
 dict(tab="Concepts", groups=[
   grp("The big picture", L("concepts/how-it-works","concepts/ai-and-deterministic-work","concepts/supervisors","concepts/who-decides","concepts/ai-assisted-configuration")),
   grp("How it behaves", L("concepts/sessions-and-workflows","concepts/runs","concepts/sub-workflows","concepts/delivery-and-backpressure","concepts/failure-model","concepts/time-limits","concepts/durability-and-high-availability","concepts/security-model")),
   grp("Context", L("concepts/compared-with-other-tools")),
 ]),
 dict(tab="Reference", groups=[
   grp("Workflows", L("reference/workflow-json","reference/node-options","reference/expressions","reference/messages")),
   grp("Plugins", L("reference/plugins/overview","reference/plugins/triggers","reference/plugins/routing-and-flow","reference/plugins/transform","reference/plugins/external-io","reference/plugins/ai","reference/plugins/decisions","reference/plugins/terminals","reference/plugins/contract")),
   grp("Supervisors", L("reference/supervisors/shell","reference/supervisors/engines","reference/supervisors/contract","reference/supervisors/rule-files")),
   grp("Interfaces", L("reference/mcp-tools","reference/cli","reference/client-parity")),
   grp("Server", L("reference/configuration","reference/security","reference/metrics","reference/status-values","reference/glossary","reference/licensing")),
 ]),
 dict(tab="API reference", groups=[
   grp("Overview", L("api/overview","api/errors")),
   grp("Endpoints by area", L("api/sessions","api/workflows","api/executions","api/control","api/streams","api/catalogue")),
   dict(group="All endpoints", public=True, openapi="api/openapi.json"),
 ]),
 dict(tab="Changelog", groups=[grp("Changelog", L("reference/releases","reference/whats-new-alpha-4","reference/migration-alpha-4"))]),
]
d['navigation'] = dict(tabs=tabs)
red = []
for f in sorted(glob.glob('internal-style/redirects/*.tsv')):
    for line in open(f):
        line=line.rstrip('\n')
        if '\t' in line:
            a,b = line.split('\t')[:2]
            red.append((a.strip('/'),b.strip('/')))
extra = [("building/defining-a-workflow","guides/build/define-a-workflow"),("building/variables-and-expressions","guides/build/use-variables-and-expressions"),
 ("concepts/nodes-edges-ports","concepts/how-it-works"),("concepts/messages","reference/messages"),("concepts/executions","concepts/runs"),("concepts/failure-handling","concepts/failure-model"),
 ("operations/configuration","reference/configuration"),("operations/security","guides/operate/secure-the-server"),("operations/persistence","guides/operate/persist-sessions"),
 ("operations/high-availability","guides/operate/run-highly-available"),("operations/checkpoints-and-resume","concepts/durability-and-high-availability"),
 ("operations/capacity-and-performance","guides/operate/tune-capacity-and-limits"),("running/admission-and-limits","guides/operate/tune-capacity-and-limits"),
 ("plugins/writing-a-plugin","guides/extend/write-a-plugin"),("building/ai-and-agents","concepts/ai-and-deterministic-work"),("building/input-requests","concepts/who-decides"),
 ("building/approvals-and-signals","learn/ask-for-approval"),("building/loops-and-retries","guides/build/loop-and-retry"),("building/routing-and-branching","guides/build/route-and-branch"),
 ("building/scheduling","guides/build/schedule-a-workflow"),("building/validation-and-versions","guides/build/validate-and-roll-back"),("running/supervisors","concepts/supervisors"),
 ("running/writing-a-supervisor","guides/supervise/write-an-agent-supervisor"),("running/control-dashboard","guides/supervise/use-the-control-dashboard"),
 ("running/run-controls","guides/build/control-a-run"),("running/run-modes","guides/build/control-a-run")]
seen={a for a,_ in red}
red += [e for e in extra if e[0] not in seen]
red = [(a,b) for a,b in red if exists(b) and not exists(a)]
uniq = {}
for a,b in red: uniq.setdefault(a,b)
red = list(uniq.items())
d['redirects'] = [dict(source='/'+a, destination='/'+b) for a,b in red]
json.dump(d, open('docs.json','w'), indent=2); open('docs.json','a').write('\n')
used = {p for t in tabs for g in t['groups'] for p in g.get('pages',[])}
allp = {p[:-4] for p in glob.glob('**/*.mdx', recursive=True) if not p.startswith(('contributing/','internal-style/'))}
print('orphans (page exists, not in nav):', sorted(allp-used-{p for p in allp if p.startswith('api/')} ))
print('redirects', len(red))
