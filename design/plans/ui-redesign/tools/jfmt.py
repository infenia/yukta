# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited
import json,sys
def enc(v,ind):
    pad='  '*ind
    if isinstance(v,dict):
        if not v: return '{ }'
        items=[f'{pad}  {json.dumps(k,ensure_ascii=False)} : {enc(x,ind+1)}' for k,x in v.items()]
        return '{\n'+',\n'.join(items)+f'\n{pad}}}'
    if isinstance(v,list):
        if not v: return '[ ]'
        return '[ '+', '.join(enc(x,ind) for x in v)+' ]'
    return json.dumps(v,ensure_ascii=False)
def dump(o): return enc(o,0)+'\n'
