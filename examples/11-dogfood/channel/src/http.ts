// The HTTP API of a channel, on 127.0.0.1. Every route is under /v1; all but /v1/status and /v1/openapi.json need
// `Authorization: Bearer <token>` (the content of the token file, read per request so it can be rotated).
import { readFileSync } from 'node:fs'
import { timingSafeEqual } from 'node:crypto'

import { type Channel, MAX_CONTENT, type Outcome } from './channel.ts'
import { openapi } from './openapi.ts'

const MAX_BODY = MAX_CONTENT + 16 * 1024
const HEARTBEAT_MS = 15_000

export type HttpOptions = { port: number; tokenFile: string }

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } })

const answer = (o: Outcome) => json(o.status, o.body)

function readToken(file: string): string {
  try {
    return readFileSync(file, 'utf8').trim()
  } catch {
    return ''
  }
}

export function authorized(req: Request, tokenFile: string): boolean {
  const want = Buffer.from(readToken(tokenFile))
  const got = Buffer.from(/^Bearer (.+)$/.exec(req.headers.get('authorization') ?? '')?.[1] ?? '')
  return want.length > 0 && want.length === got.length && timingSafeEqual(want, got)
}

async function readJson(req: Request): Promise<{ ok: true; value: unknown } | { ok: false; res: Response }> {
  if (Number(req.headers.get('content-length') ?? 0) > MAX_BODY) return { ok: false, res: json(413, { error: 'body too large' }) }
  const text = await req.text()
  if (text.length > MAX_BODY) return { ok: false, res: json(413, { error: 'body too large' }) }
  try {
    return { ok: true, value: JSON.parse(text || '{}') }
  } catch {
    return { ok: false, res: json(400, { error: 'body is not JSON' }) }
  }
}

function stream(channel: Channel, req: Request): Response {
  const enc = new TextEncoder()
  const since = Number(req.headers.get('last-event-id') ?? new URL(req.url).searchParams.get('since') ?? 0) || 0
  let off = () => {}
  let beat: ReturnType<typeof setInterval> | undefined
  const body = new ReadableStream<Uint8Array>({
    start(ctl) {
      const send = (s: string) => {
        try {
          ctl.enqueue(enc.encode(s))
        } catch {
          // the client went away: cancel() cleans up
        }
      }
      send(': connected\n\n')
      off = channel.subscribe(e => send(`id: ${e.seq}\nevent: ${e.type}\ndata: ${JSON.stringify({ at: e.at, ...e.data })}\n\n`), since)
      beat = setInterval(() => send(': ping\n\n'), HEARTBEAT_MS)
    },
    cancel() {
      off()
      clearInterval(beat)
    },
  })
  return new Response(body, { headers: { 'content-type': 'text/event-stream', 'cache-control': 'no-cache', connection: 'keep-alive' } })
}

export function handler(channel: Channel, tokenFile: string, base = '') {
  return async (req: Request): Promise<Response> => {
    const url = new URL(req.url)
    const path = url.pathname
    const route = `${req.method} ${path}`
    if (route === 'GET /v1/status') return json(200, channel.status())
    if (route === 'GET /v1/openapi.json') return json(200, openapi(channel.options, base))
    if (!path.startsWith('/v1/')) return json(404, { error: 'not found' })
    if (!authorized(req, tokenFile)) return json(401, { error: 'bad or missing token' }, { 'www-authenticate': 'Bearer' })

    if (route === 'POST /v1/events') {
      const body = await readJson(req)
      return body.ok ? answer(await channel.submit(body.value)) : body.res
    }
    if (route === 'GET /v1/stream') return stream(channel, req)
    if (route === 'GET /v1/permissions') {
      if (!channel.options.permissionRelay) return json(404, { error: 'permission relay is off' })
      return json(200, { pending: channel.pendingPermissions() })
    }
    const m = /^\/v1\/permissions\/([a-z]{5})$/.exec(path)
    if (m && req.method === 'POST') {
      const body = await readJson(req)
      return body.ok ? answer(await channel.verdict(m[1] as string, body.value)) : body.res
    }
    return json(404, { error: 'not found' })
  }
}

// Serves the API; port 0 takes any free one. Returns the server (its `port` is the one in use).
export function serve(channel: Channel, opts: HttpOptions) {
  return Bun.serve({ hostname: '127.0.0.1', port: opts.port, idleTimeout: 0, fetch: handler(channel, opts.tokenFile) })
}
