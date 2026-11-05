// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// walk-workflow SESSION WORKFLOW PAYLOAD.json: runs a generated workflow without a server (a test stand-in for the engine).
//
// Supports what gen-ticket.ts emits: CONSTANT_SOURCE, PROCESS_EXECUTOR (exit code routing, JSON output, ${start.x} in command and env),
// BRANCH (SELECT_KEY on payload['output'][key]; the decision expression, answered by $TEST_ASK), ASK_INPUT, SUB_WORKFLOW (payload mapping), SPLITTER with
// its parallel children, OUTCOME_TERMINAL, CONSOLE_TERMINAL. Prints one `NODE <id> <port>` line per node run and `OUTCOME <SUCCESS|FAILED> <reason>`;
// exit 0 on SUCCESS. __TICKET__ is the folder of ticketkit.
import { spawn } from 'bun'
import { type Json, KIT, readFileSync } from './lib.ts'

type Start = Record<string, unknown>

function sub(v: Json, start: Start, out?: Json, item?: Json): Json {
  if (typeof v === 'string') {
    let s = v
      .replace(/\$\{payload\.(?!output)(\w+)\}/g, (_, k: string) => String(item?.[k] ?? 'None'))
      .replace(/\$\{payload\.output\.(\w+)\}/g, (_, k: string) => String(out?.[k] ?? 'None'))
    s = s.replaceAll('__TICKET__', KIT)
    return s.replace(/\$\{start\.(\w+)\}/g, (_, k: string) => String(start[k] ?? '-'))
  }
  if (Array.isArray(v)) return v.map((x) => sub(x, start, out, item))
  return v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x, start, out, item)])) : v
}

async function walk(wfs: Json, name: string, start: Start): Promise<[string, string]> {
  const wf = wfs[name]
  const nodes: Record<string, Json> = Object.fromEntries(wf.nodes.map((n: Json) => [n.nodeId, n]))
  let out: Json = null
  let cur = 'trigger'
  for (;;) {
    const n = nodes[cur]!
    const c = n.config
    const t = n.type
    let port: string | null
    if (t === 'OUTCOME_TERMINAL') {
      console.log(`NODE ${cur} -`)
      return [c.outcome, c.reason]
    }
    if (t === 'CONSOLE_TERMINAL') {
      console.log(`NODE ${cur} -`)
      return ['SUCCESS', '']
    }
    if (t === 'PROCESS_EXECUTOR') {
      const env = { ...process.env, ...Object.fromEntries(Object.entries(sub(c.env, start)).map(([k, x]) => [k, String(x)])) } as Record<
        string,
        string
      >
      const cmd: string[] = sub(c.command, start)
      const p = spawn(cmd, { env, cwd: String(start.project), stdout: 'pipe', stderr: 'pipe', stdin: 'ignore' })
      const [stdout, stderr, code] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text(), p.exited])
      out = null
      if (c.outputFormat === 'JSON') {
        try {
          out = JSON.parse(stdout)
        } catch {
          out = null
        }
      }
      port = code === 0 ? 'success' : 'failure'
      if (code) console.error((stdout + stderr).slice(-600))
    } else if (t === 'SPLITTER') {
      // SPLITTER -> SUB_WORKFLOW (concurrency) -> AGGREGATE: the children run in parallel, the join waits for all
      const items: Json[] = out[c.itemsPath.split('.').pop()]
      const sw = wf.edges.find((e: Json) => e.source === cur).target
      const agg = wf.edges.find((e: Json) => e.source === sw).target
      const cfg = nodes[sw]!.config
      console.log(`NODE ${cur} -`)
      const res = await Promise.all(items.map((it) => walk(wfs, cfg.workflowId, { ...start, ...sub(cfg.payload, start, null, it) })))
      console.log(`NODE ${sw} ${res.every((r) => r[0] === 'SUCCESS') ? 'success' : 'failure'}\nNODE ${agg} -`)
      cur = wf.edges.find((e: Json) => e.source === agg).target
      out = null
      continue
    } else if (t === 'ASK_INPUT') {
      port = null
      console.error(`ASK ${cur}`)
    } else if (t === 'BRANCH' && c.mode === 'EXPRESSION')
      port = (process.env.TEST_ASK ?? 'approve') === 'reject' ? Object.values<string>(c.cases)[0]! : c.defaultPort // metadata['input.decision'] == 'reject', answered by $TEST_ASK
    else if (t === 'BRANCH') {
      const key = /\['output'\]\['(\w+)'\]/.exec(c.selector)![1]!
      const val = (out ?? {})[key]
      port = c.cases[typeof val === 'boolean' ? JSON.stringify(val) : String(val)] ?? c.defaultPort
    } else if (t === 'SUB_WORKFLOW') {
      const [outcome] = await walk(wfs, c.workflowId, { ...start, ...sub(c.payload, start, out) })
      port = outcome === 'SUCCESS' ? 'success' : 'failure'
    } else port = null
    console.log(`NODE ${cur} ${port ?? '-'}`)
    const nxt = wf.edges
      .filter((e: Json) => e.source === cur && (e.sourcePort === undefined || e.sourcePort === null || e.sourcePort === port))
      .map((e: Json) => e.target)
    if (!nxt.length) return ['FAILED', `${cur} ended with no edge for ${port}`]
    cur = nxt[0]
  }
}

export async function main(argv: string[]): Promise<number> {
  const wfs = JSON.parse(readFileSync(argv[0]!, 'utf8')).workflows
  const [outcome, why] = await walk(wfs, argv[1]!, JSON.parse(readFileSync(argv[2]!, 'utf8')))
  console.log(`OUTCOME ${outcome} ${why}`)
  return outcome === 'SUCCESS' ? 0 : 1
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
