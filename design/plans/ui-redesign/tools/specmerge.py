# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
import json,sys
sys.path.insert(0, __import__('os').path.dirname(__file__)); from jfmt import dump
# Rewrite the fresh snapshot (sorted keys) in the committed snapshot's key order, so the diff is only what changed.
old=json.load(open(sys.argv[1])); new=json.load(open(sys.argv[2]))
def merge(o,n):
    if isinstance(o,dict) and isinstance(n,dict):
        out={k:merge(o[k],n[k]) for k in o if k in n}
        out.update({k:v for k,v in n.items() if k not in o})
        return out
    if isinstance(o,list) and isinstance(n,list) and len(o)==len(n):
        return [merge(a,b) for a,b in zip(o,n)]
    if isinstance(o,list) and isinstance(n,list):
        # An item was added or removed: take each item's key order from an equal old item, else from one with the same keys.
        def like(b):
            return next((a for a in o if a==b), None) or next((a for a in o if isinstance(a,dict) and isinstance(b,dict) and a.keys()==b.keys()), b)
        return [merge(like(b),b) for b in n]
    return n
m=merge(old,new); assert m==new
open(sys.argv[2],'w').write(dump(m))
