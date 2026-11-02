// The generic Claude Code channel (research preview): an MCP server over stdio that turns calls of an HTTP API into
// `notifications/claude/channel` events, and Claude's answers (the `reply` and `done` tools, permission prompts) back into API events.
// Contract: https://code.claude.com/docs/en/channels-reference.md
import { randomUUID } from 'node:crypto'

import { Server } from '@modelcontextprotocol/sdk/server/index.js'
import type { Transport } from '@modelcontextprotocol/sdk/shared/transport.js'
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  type Notification,
  type Request,
  type Result,
} from '@modelcontextprotocol/sdk/types.js'
import { z } from 'zod'

export type Meta = Record<string, string>
export type Outcome = { status: number; body: Record<string, unknown> }
export type BusType = 'event' | 'reply' | 'done' | 'permission_request' | 'permission_resolved'
export type BusEvent = { seq: number; type: BusType; at: number; data: Record<string, unknown> }
export type ToolResult = { text: string; isError?: boolean }
export type InFlight = { id: string; since: number; meta: Meta }

// A tool beyond `reply` and `done`: a profile (pickup) adds its own.
export type ExtraTool = {
  name: string
  description: string
  inputSchema: Record<string, unknown>
  handle: (args: Record<string, unknown>, channel: Channel) => Promise<ToolResult> | ToolResult
}

export type ChannelOptions = {
  name: string
  version?: string
  instructions: string
  // At most one event in flight: a POST while one is open answers 409. It is freed by a `done` call or after busyLimitMs.
  exclusive?: boolean
  busyLimitMs?: number
  // Forward tool approval prompts to the API. Needs `senders`: anyone who can answer can approve a tool call.
  permissionRelay?: boolean
  // Sender ids that may post events and answer permission prompts. Empty: events are not gated by sender (the token still is).
  senders?: string[]
  // `reply` and `done`: on unless a profile has tools of its own only.
  builtinTools?: boolean
  extraTools?: ExtraTool[]
  log?: (msg: string) => void
}

export const MAX_CONTENT = 64 * 1024
export const MAX_META_KEYS = 32
const MAX_META_VALUE = 2000
const IDEMPOTENCY_TTL_MS = 10 * 60 * 1000
const BUS_RING = 200
const MAX_REMEMBERED = 500
const MAX_PENDING_PERMISSIONS = 50
// Keys that become attributes of the <channel> tag must be identifiers: others are silently dropped by Claude Code. The channel sets these itself.
const META_KEY = /^[A-Za-z0-9_]+$/
const RESERVED_META = ['event_id', 'sender']

export const EventInput = z
  .object({
    content: z.string().min(1).max(MAX_CONTENT),
    meta: z
      .record(z.string().regex(META_KEY, 'meta keys are letters, digits and underscores'), z.string().max(MAX_META_VALUE))
      .refine(m => Object.keys(m).length <= MAX_META_KEYS, `at most ${MAX_META_KEYS} meta keys`)
      .refine(m => !RESERVED_META.some(k => k in m), `meta keys ${RESERVED_META.join(', ')} are set by the channel`)
      .optional(),
    sender: z.string().min(1).max(200).optional(),
    idempotency_key: z.string().min(1).max(200).optional(),
  })
  .strict()

export const VerdictInput = z
  .object({ behavior: z.enum(['allow', 'deny']), sender: z.string().min(1).max(200).optional() })
  .strict()

const ReplyArgs = z.object({
  text: z.string().min(1).max(MAX_CONTENT),
  event_id: z.string().min(1).max(200).optional(),
  meta: z.record(z.string().regex(META_KEY), z.string().max(MAX_META_VALUE)).optional(),
})
const DoneArgs = z.object({
  event_id: z.string().min(1).max(200).optional(),
  outcome: z.string().max(200).optional(),
  note: z.string().max(MAX_CONTENT).optional(),
})
const PermissionRequestSchema = z.object({
  method: z.literal('notifications/claude/channel/permission_request'),
  params: z.object({
    request_id: z.string(),
    tool_name: z.string(),
    description: z.string(),
    input_preview: z.string(),
  }),
})

export type PendingPermission = z.infer<typeof PermissionRequestSchema>['params'] & { at: number }

const BUILTIN_TOOLS = [
  {
    name: 'reply',
    description:
      'Send a message back to whoever sent the channel event. Pass the event_id attribute of the <channel> tag you answer.',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'The message to send' },
        event_id: { type: 'string', description: 'The event_id of the <channel> tag you answer' },
        meta: { type: 'object', additionalProperties: { type: 'string' }, description: 'Extra string attributes for the caller' },
      },
      required: ['text'],
    },
  },
  {
    name: 'done',
    description: 'Report that the channel event is handled (and, on an exclusive channel, free it so the next one can be sent).',
    inputSchema: {
      type: 'object',
      properties: {
        event_id: { type: 'string', description: 'The event_id of the <channel> tag; omit for the event in flight' },
        outcome: { type: 'string', description: 'A short result word, such as ok, failed or skipped' },
        note: { type: 'string' },
      },
    },
  },
]

export class Channel {
  readonly server: Server<Request, Notification, Result>
  readonly options: Required<Pick<ChannelOptions, 'name' | 'exclusive' | 'busyLimitMs' | 'permissionRelay'>> & ChannelOptions
  private seq = 0
  private inFlight: InFlight | null = null
  private readonly ring: BusEvent[] = []
  private readonly subscribers = new Set<(e: BusEvent) => void>()
  private readonly keys = new Map<string, { eventId: string; at: number }>()
  private readonly remembered = new Map<string, Meta>()
  private readonly pending = new Map<string, PendingPermission>()
  private connected = false

  constructor(opts: ChannelOptions) {
    if (opts.permissionRelay && !(opts.senders?.length)) {
      throw new Error('permission relay needs a sender allowlist: anyone who can answer can approve a tool call')
    }
    this.options = { exclusive: false, busyLimitMs: 4 * 3600 * 1000, permissionRelay: false, ...opts }
    this.server = new Server<Request, Notification, Result>(
      { name: opts.name, version: opts.version ?? '0.1.0' },
      {
        capabilities: {
          experimental: {
            'claude/channel': {},
            ...(this.options.permissionRelay ? { 'claude/channel/permission': {} } : {}),
          },
          tools: {},
        },
        instructions: opts.instructions,
      },
    )
    this.server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: this.tools() }))
    this.server.setRequestHandler(CallToolRequestSchema, async req => {
      const { text, isError } = await this.callTool(req.params.name, req.params.arguments ?? {})
      return { content: [{ type: 'text', text }], isError: isError ?? false }
    })
    if (this.options.permissionRelay) {
      // the schema is cast: its type makes the compiler give up ("excessively deep") with the SDK's generics; the handler checks the payload itself
      const schema = PermissionRequestSchema as unknown as Parameters<Server['setNotificationHandler']>[0]
      this.server.setNotificationHandler(schema, async (n: Notification) => {
        const p = PermissionRequestSchema.shape.params.safeParse(n.params)
        if (!p.success) return
        if (this.pending.size >= MAX_PENDING_PERMISSIONS) this.pending.delete(this.pending.keys().next().value as string)
        this.pending.set(p.data.request_id, { ...p.data, at: Date.now() })
        this.publish('permission_request', { ...p.data })
      })
    }
  }

  async connect(transport: Transport): Promise<void> {
    await this.server.connect(transport)
    this.connected = true
  }

  async close(): Promise<void> {
    this.connected = false
    await this.server.close()
  }

  log(msg: string): void {
    ;(this.options.log ?? ((m: string) => console.error(`${this.options.name}: ${m}`)))(msg)
  }

  tools(): { name: string; description: string; inputSchema: Record<string, unknown> }[] {
    const own = this.options.builtinTools === false ? [] : BUILTIN_TOOLS
    return [...own, ...(this.options.extraTools ?? []).map(({ name, description, inputSchema }) => ({ name, description, inputSchema }))]
  }

  async callTool(name: string, args: Record<string, unknown>): Promise<ToolResult> {
    const extra = this.options.extraTools?.find(t => t.name === name)
    if (extra) return extra.handle(args, this)
    if (this.options.builtinTools !== false && name === 'reply') return this.reply(args)
    if (this.options.builtinTools !== false && name === 'done') return this.done(args)
    return { text: `unknown tool ${name}`, isError: true }
  }

  // The `reply` tool: Claude's message goes to whoever listens on the stream.
  private reply(args: Record<string, unknown>): ToolResult {
    const p = ReplyArgs.safeParse(args)
    if (!p.success) return { text: `bad arguments: ${p.error.issues.map(i => i.message).join('; ')}`, isError: true }
    const { text, event_id, meta } = p.data
    this.publish('reply', { event_id: event_id ?? null, text, meta: meta ?? {}, in_reply_to: event_id ? (this.remembered.get(event_id) ?? null) : null })
    return { text: 'sent' }
  }

  // The `done` tool: the event is handled; an exclusive channel is free again.
  private done(args: Record<string, unknown>): ToolResult {
    const p = DoneArgs.safeParse(args)
    if (!p.success) return { text: `bad arguments: ${p.error.issues.map(i => i.message).join('; ')}`, isError: true }
    const { event_id, outcome, note } = p.data
    const freed = this.release(event_id)
    this.publish('done', { event_id: event_id ?? freed?.id ?? null, outcome: outcome ?? 'ok', note: note ?? '' })
    return { text: freed ? `done ${freed.id}` : 'noted (nothing was in flight)' }
  }

  // The event in flight on an exclusive channel, or null (a limit passed without `done` frees it).
  current(): InFlight | null {
    const cur = this.inFlight
    if (cur && Date.now() - cur.since > this.options.busyLimitMs) {
      this.log(`event ${cur.id} timed out after ${this.options.busyLimitMs} ms without done`)
      this.inFlight = null
      this.publish('done', { event_id: cur.id, outcome: 'timeout', note: '' })
      return null
    }
    return cur
  }

  // Frees the event in flight when it is `eventId` (any when omitted); returns it, or null when nothing matched.
  release(eventId?: string): InFlight | null {
    const cur = this.current()
    if (!cur || (eventId !== undefined && cur.id !== eventId)) return null
    this.inFlight = null
    return cur
  }

  async submit(input: unknown): Promise<Outcome> {
    const parsed = EventInput.safeParse(input)
    if (!parsed.success) {
      return { status: 400, body: { error: 'invalid', issues: parsed.error.issues.map(i => `${i.path.join('.') || 'body'}: ${i.message}`) } }
    }
    const { content, meta = {}, sender, idempotency_key: key } = parsed.data
    const senders = this.options.senders ?? []
    if (senders.length > 0 && (sender === undefined || !senders.includes(sender))) {
      return { status: 403, body: { error: 'sender not allowed' } }
    }
    const now = Date.now()
    if (key !== undefined) {
      this.expireKeys(now)
      const seen = this.keys.get(key)
      if (seen) return { status: 200, body: { event_id: seen.eventId, duplicate: true } }
    }
    const cur = this.current()
    if (this.options.exclusive && cur) {
      return { status: 409, body: { error: 'busy', event_id: cur.id, meta: cur.meta, since: cur.since } }
    }
    if (!this.connected) return { status: 503, body: { error: 'not connected to Claude Code' } }
    const id = randomUUID().slice(0, 8)
    const attrs: Meta = { ...meta, event_id: id, ...(sender === undefined ? {} : { sender }) }
    if (this.options.exclusive) this.inFlight = { id, since: now, meta: attrs }
    try {
      await this.server.notification({ method: 'notifications/claude/channel', params: { content, meta: attrs } })
    } catch (e) {
      if (this.inFlight?.id === id) this.inFlight = null
      this.log(`could not deliver event ${id}: ${(e as Error).message}`)
      return { status: 503, body: { error: 'could not deliver the event' } }
    }
    if (key !== undefined) this.keys.set(key, { eventId: id, at: now })
    this.remember(id, attrs)
    this.publish('event', { event_id: id, content, meta: attrs })
    return { status: 202, body: { event_id: id } }
  }

  pendingPermissions(): PendingPermission[] {
    return [...this.pending.values()]
  }

  // The answer of a remote user to a permission prompt. Claude Code applies the first answer (terminal or here) and drops later ones.
  async verdict(requestId: string, input: unknown): Promise<Outcome> {
    if (!this.options.permissionRelay) return { status: 404, body: { error: 'permission relay is off' } }
    const parsed = VerdictInput.safeParse(input)
    if (!parsed.success) return { status: 400, body: { error: 'invalid', issues: parsed.error.issues.map(i => `${i.path.join('.') || 'body'}: ${i.message}`) } }
    const { behavior, sender } = parsed.data
    if (sender === undefined || !(this.options.senders ?? []).includes(sender)) return { status: 403, body: { error: 'sender not allowed' } }
    const req = this.pending.get(requestId)
    if (!req) return { status: 404, body: { error: 'no such open permission request' } }
    this.pending.delete(requestId)
    try {
      await this.server.notification({ method: 'notifications/claude/channel/permission', params: { request_id: requestId, behavior } })
    } catch (e) {
      this.log(`could not send the verdict for ${requestId}: ${(e as Error).message}`)
      return { status: 503, body: { error: 'could not deliver the verdict' } }
    }
    this.publish('permission_resolved', { request_id: requestId, behavior, sender })
    return { status: 202, body: { request_id: requestId, behavior } }
  }

  publish(type: BusType, data: Record<string, unknown>): void {
    const e: BusEvent = { seq: ++this.seq, type, at: Date.now(), data }
    this.ring.push(e)
    if (this.ring.length > BUS_RING) this.ring.shift()
    for (const fn of this.subscribers) fn(e)
  }

  // Calls `fn` for the buffered events after `since` and then for every new one; returns the unsubscribe.
  subscribe(fn: (e: BusEvent) => void, since = 0): () => void {
    for (const e of this.ring) if (e.seq > since) fn(e)
    this.subscribers.add(fn)
    return () => this.subscribers.delete(fn)
  }

  status(): Record<string, unknown> {
    const cur = this.current()
    return {
      name: this.options.name,
      version: this.options.version ?? '0.1.0',
      connected: this.connected,
      exclusive: this.options.exclusive,
      busy: cur !== null,
      event_id: cur?.id ?? null,
      meta: cur?.meta ?? null,
      since: cur ? Math.floor(cur.since / 1000) : null,
      permission_relay: this.options.permissionRelay,
      pending_permissions: this.pending.size,
      subscribers: this.subscribers.size,
    }
  }

  private remember(id: string, meta: Meta): void {
    this.remembered.set(id, meta)
    if (this.remembered.size > MAX_REMEMBERED) this.remembered.delete(this.remembered.keys().next().value as string)
  }

  private expireKeys(now: number): void {
    for (const [k, v] of this.keys) {
      if (now - v.at <= IDEMPOTENCY_TTL_MS) break // insertion order: the rest is younger
      this.keys.delete(k)
    }
  }
}
