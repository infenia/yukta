#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2026 Infenia Private Limited
#
# SPDX-License-Identifier: Apache-2.0

"""Link, banned-word, front-matter and nav checks for the published docs. Run from docs/: python3 internal-style/check-docs.py"""
import re, glob, os, json, sys
pages = {p[:-4] for p in glob.glob('**/*.mdx', recursive=True) if not p.startswith(('contributing/','internal-style/'))}
red = {r['source'].strip('/') for r in json.load(open('docs.json')).get('redirects', [])}
nav = json.dumps(json.load(open('docs.json'))['navigation'])
bad, warn = [], []
BANNED = re.compile(r"\b(simply|(?<!you )just|obviously|trivial(?:ly)?|clearly|of course)\b", re.I)
for p in sorted(pages):
    t = open(p + '.mdx').read()
    body = re.sub(r"```.*?```", "", t, flags=re.S)
    for m in re.finditer(r"\]\((/[^)\s#]*)(#[^)]*)?\)|href=\"(/[^\"#]*)", body):
        l = (m.group(1) or m.group(3)).strip('/')
        if l in ('',) or l.startswith(('images/','api/openapi')): continue
        if l in pages or os.path.exists(l): continue
        bad.append(f"{p}: broken link /{l}" + (" (has redirect, point at the new page)" if l in red else ""))
    for m in BANNED.finditer(re.sub(r"`[^`]*`", "", body)):
        warn.append(f"{p}: banned word '{m.group(0)}'")
    if not t.startswith('---') or 'title:' not in t.split('---')[1] or 'description:' not in t.split('---')[1]:
        bad.append(f"{p}: front matter needs title and description")
    if re.search(r"0\.1\.0-alpha\.\d", body) and p not in ('index','get-started/install','reference/releases','reference/whats-new-alpha-4','reference/migration-alpha-4') and not p.startswith('api/'):
        warn.append(f"{p}: hand-typed version")
    quad = p.split('/')[0]
    if quad in ('learn','guides','concepts') and 'ReaderState' not in t and p not in ('learn/playground',):
        warn.append(f"{p}: no ReaderState")
    if p not in nav and not p.startswith('api/'): warn.append(f"{p}: not in nav")
for x in bad: print('ERROR', x)
for x in warn: print('warn ', x)
print(len(pages), 'pages;', len(bad), 'errors;', len(warn), 'warnings'); sys.exit(1 if bad else 0)
