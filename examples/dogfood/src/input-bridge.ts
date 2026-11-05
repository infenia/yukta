// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// input-bridge: pushes the questions of a ticket run to the Claude Code session that works the ticket (design/plans/ticket-input-routing.md 3.3, #1920).
//   Subscribes to /api/events/stream (InputRequested, InputEscalated) with the read-only fixer key. A request on the route `ticket` that reached the
//   session tier (or, escalated, the human tier) is POSTed as one event to the pickup channel that is busy with the request's issue
//   (spec.context.issue). The event is a short notification (prompt cut to 1 KB, request id): the session reads and answers over the Yukta MCP.
//   Never answers and holds no write key. No owning session: nothing is sent (the CLI and UI still answer). The feed is not replayed, so a request
//   raised while the bridge was down is not announced; its tier timer escalates it.
// Env: YUKTA_BRIDGE_ROUTE (ticket), YUKTA_BRIDGE_SESSION_TIER (session), YUKTA_BRIDGE_HUMAN_TIER (human), YUKTA_SERVER_URL, YUKTA_DEV_HOME.
import { DEV_HOME, DEV_URL, type Json, alive, fixerKey, http, join, readdirSync, text, tryJson } from './lib.ts'

const ROUTE = process.env.YUKTA_BRIDGE_ROUTE ?? 'ticket'
const SESSION_TIER = process.env.YUKTA_BRIDGE_SESSION_TIER ?? 'session'
const HUMAN_TIER = process.env.YUKTA_BRIDGE_HUMAN_TIER ?? 'human'
const MAX_PROMPT = 1024
// read per call: lib's DEV_HOME is fixed when lib.ts is first imported, which a test process shares between files
const home = (): string => process.env.YUKTA_DEV_HOME || DEV_HOME

/** The channel session busy with `issue`: its port, or undefined. */
export async function owner(issue: string): Promise<{ name: string; port: number } | undefined> {
  const dir = join(home(), 'pickup')
  let files: string[] = []
  try {
    files = readdirSync(dir).filter((f) => f.endsWith('.json'))
  } catch {
    return undefined
  }
  for (const f of files) {
    const j = tryJson(text(join(dir, f)))
    if (!j || !Number.isInteger(j.port) || !Number.isInteger(j.pid) || !alive(j.pid)) continue
    const st = await http('GET', `http://127.0.0.1:${j.port}/v1/status`, { timeoutMs: 3000 })
    const s = st.code === 200 ? tryJson(st.body) : undefined
    if (s?.busy === true && String(s.meta?.issue ?? '') === issue) return { name: j.name, port: j.port }
  }
  return undefined
}

/** What to tell the session about an envelope, or undefined when it is not for a session. */
export function notice(env: Json): { issue: string; idempotency_key: string; content: string; meta: Record<string, string> } | undefined {
  const req = env?.event?.request
  if (!req || (env.type !== 'InputRequested' && env.type !== 'InputEscalated')) return undefined
  const spec = req.spec ?? {}
  if (spec.routeName !== ROUTE || (req.tier !== SESSION_TIER && req.tier !== HUMAN_TIER)) return undefined
  const issue = String(spec.context?.issue ?? '')
  if (!issue) return undefined
  const human = req.tier === HUMAN_TIER
  const prompt = String(spec.prompt ?? '').slice(0, MAX_PROMPT)
  const content = human
    ? `Ask the person at this terminal (ticket #${issue}, ${spec.risk}): ${prompt}\nThen answer request ${req.id} with answer_input.`
    : `Question for ticket #${issue} (${spec.risk}, tier ${req.tier}): ${prompt}\nRead it with list_pending_inputs and answer request ${req.id} with answer_input, or ask the person.\nanswer_input needs session_id and execution_id from this event's attributes.`
  return {
    issue,
    idempotency_key: `input-${req.id}-${req.tier}`,
    content,
    meta: {
      kind: 'input',
      request_id: String(req.id),
      session_id: String(req.sessionId),
      execution_id: String(req.executionId),
      run: String(spec.context?.run_id ?? ''),
      issue,
      risk: String(spec.risk),
      tier: String(req.tier),
      phase: human ? 'human' : 'session',
    },
  }
}

/** Sends one envelope to the owning session; returns what happened. */
export async function deliver(env: Json, retryMs = 1000): Promise<'sent' | 'skipped' | 'no-owner' | 'failed'> {
  const n = notice(env)
  if (!n) return 'skipped'
  const o = await owner(n.issue)
  if (!o) return 'no-owner'
  const token = text(join(home(), 'pickup-token')).trim()
  const post = () =>
    http('POST', `http://127.0.0.1:${o.port}/v1/events`, {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ content: n.content, meta: n.meta, idempotency_key: n.idempotency_key }),
      timeoutMs: 10000,
    })
  // one retry for a transient failure; the idempotency key makes a repeat harmless
  let r = await post()
  if (r.code !== 202 && r.code !== 200) {
    await new Promise((res) => setTimeout(res, retryMs))
    r = await post()
  }
  return r.code === 202 || r.code === 200 ? 'sent' : 'failed'
}

/** Reads the SSE stream until it ends; calls `onEnvelope` for every data frame. */
export async function readStream(url: string, key: string, onEnvelope: (e: Json) => Promise<void>, signal?: AbortSignal): Promise<void> {
  const res = await fetch(url, { headers: { 'X-API-Key': key, Accept: 'text/event-stream' }, signal })
  if (!res.ok || !res.body) throw new Error(`stream: HTTP ${res.status}`)
  const dec = new TextDecoder()
  let buf = ''
  for await (const chunk of res.body as unknown as AsyncIterable<Uint8Array>) {
    buf += dec.decode(chunk, { stream: true })
    let i: number
    while ((i = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, i)
      buf = buf.slice(i + 2)
      const data = frame
        .split('\n')
        .filter((l) => l.startsWith('data:'))
        .map((l) => l.slice(5).trim())
        .join('')
      const env = data ? tryJson(data) : undefined
      if (env) await onEnvelope(env)
    }
  }
}

/** Runs until aborted: reconnects with a growing pause (1 s to 30 s). */
export async function watch(
  signal?: AbortSignal,
  base = DEV_URL,
  log: (m: string) => void = (m) => console.error(m),
  firstPauseMs = 1000,
): Promise<void> {
  let pause = firstPauseMs
  while (!signal?.aborted) {
    try {
      await readStream(
        `${base}/api/events/stream?type=InputRequested,InputEscalated`,
        fixerKey(),
        async (env) => {
          pause = firstPauseMs
          const r = await deliver(env)
          if (r !== 'skipped') log(`BRIDGE ${env.type} ${env.event?.request?.id} -> ${r}`)
        },
        signal,
      )
    } catch (e) {
      if (signal?.aborted) return
      log(`BRIDGE stream ended: ${(e as Error).message}; retrying in ${pause / 1000}s`)
    }
    await new Promise((r) => setTimeout(r, pause))
    pause = Math.min(pause * 2, 30000)
  }
}

if (import.meta.main) await watch()
