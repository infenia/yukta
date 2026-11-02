// The OpenAPI document of the channel API (GET /v1/openapi.json): the contract callers code against.
import { MAX_CONTENT, MAX_META_KEYS, type ChannelOptions } from './channel.ts'

const err = { type: 'object', properties: { error: { type: 'string' }, issues: { type: 'array', items: { type: 'string' } } }, required: ['error'] }
const meta = {
  type: 'object',
  maxProperties: MAX_META_KEYS,
  propertyNames: { pattern: '^[A-Za-z0-9_]+$' },
  additionalProperties: { type: 'string', maxLength: 2000 },
  description: 'Each entry becomes an attribute of the <channel> tag. Keys: letters, digits, underscores. event_id and sender are set by the channel.',
}
const res = (description: string, schema?: unknown) => ({ description, ...(schema ? { content: { 'application/json': { schema } } } : {}) })
const secured = [{ bearer: [] }]

export function openapi(opts: ChannelOptions, base = ''): Record<string, unknown> {
  const exclusive = opts.exclusive ?? false
  return {
    openapi: '3.1.0',
    info: {
      title: `${opts.name} channel API`,
      version: opts.version ?? '0.1.0',
      description:
        'Talk to the Claude Code session that runs this channel: POST an event and it arrives as <channel source="' + opts.name + '" ...>. ' +
        'Claude answers with the reply and done tools, which show up on GET /v1/stream. Claude Code does not acknowledge events: a reply or done is the only delivery signal.',
    },
    servers: base ? [{ url: base }] : [],
    components: {
      securitySchemes: { bearer: { type: 'http', scheme: 'bearer', description: 'The content of the channel token file.' } },
    },
    paths: {
      '/v1/status': {
        get: {
          summary: 'Readiness and the event in flight',
          responses: {
            '200': res('Status', {
              type: 'object',
              properties: {
                name: { type: 'string' },
                connected: { type: 'boolean', description: 'Claude Code has the MCP connection open' },
                exclusive: { type: 'boolean' },
                busy: { type: 'boolean', description: exclusive ? 'An event is in flight: POST /v1/events answers 409' : 'Always false: this channel is not exclusive' },
                event_id: { type: ['string', 'null'] },
                meta: { type: ['object', 'null'] },
                since: { type: ['integer', 'null'], description: 'epoch seconds' },
                permission_relay: { type: 'boolean' },
                pending_permissions: { type: 'integer' },
                subscribers: { type: 'integer' },
              },
            }),
          },
        },
      },
      '/v1/events': {
        post: {
          summary: 'Send an event into the session',
          security: secured,
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  required: ['content'],
                  additionalProperties: false,
                  properties: {
                    content: { type: 'string', minLength: 1, maxLength: MAX_CONTENT, description: 'The body of the <channel> tag. Untrusted input to Claude.' },
                    meta,
                    sender: { type: 'string', description: 'Who sends it. Required when the channel has a sender allowlist.' },
                    idempotency_key: { type: 'string', description: 'A repeat within 10 minutes answers 200 with the first event_id and sends nothing.' },
                  },
                },
              },
            },
          },
          responses: {
            '202': res('Delivered to the session', { type: 'object', properties: { event_id: { type: 'string' } } }),
            '200': res('Duplicate of an idempotency_key', { type: 'object', properties: { event_id: { type: 'string' }, duplicate: { type: 'boolean' } } }),
            '400': res('Invalid body', err),
            '401': res('Bad or missing token', err),
            '403': res('Sender not on the allowlist', err),
            '409': res(exclusive ? 'An event is in flight' : 'Not used: this channel is not exclusive', err),
            '413': res('Body too large', err),
            '503': res('Not connected to Claude Code, or delivery failed', err),
          },
        },
      },
      '/v1/stream': {
        get: {
          summary: 'Server-sent events: what Claude sends back',
          description:
            'Event types: event (an accepted POST), reply (the reply tool), done (the done tool or a timeout), permission_request and permission_resolved. ' +
            'Each has an id (a sequence); reconnect with Last-Event-ID or ?since= to replay the last 200.',
          security: secured,
          parameters: [{ name: 'since', in: 'query', required: false, schema: { type: 'integer' } }],
          responses: { '200': { description: 'text/event-stream', content: { 'text/event-stream': { schema: { type: 'string' } } } }, '401': res('Bad or missing token', err) },
        },
      },
      '/v1/permissions': {
        get: {
          summary: 'Open permission prompts (only with permission relay)',
          security: secured,
          responses: { '200': res('Open requests'), '404': res('Relay is off', err), '401': res('Bad or missing token', err) },
        },
      },
      '/v1/permissions/{request_id}': {
        post: {
          summary: 'Answer a permission prompt (only with permission relay)',
          description: 'Claude Code applies the first answer, from the terminal or here; sender must be on the allowlist.',
          security: secured,
          parameters: [{ name: 'request_id', in: 'path', required: true, schema: { type: 'string', pattern: '^[a-z]{5}$' } }],
          requestBody: {
            required: true,
            content: {
              'application/json': {
                schema: {
                  type: 'object',
                  required: ['behavior', 'sender'],
                  additionalProperties: false,
                  properties: { behavior: { enum: ['allow', 'deny'] }, sender: { type: 'string' } },
                },
              },
            },
          },
          responses: { '202': res('Sent to Claude Code'), '400': res('Invalid body', err), '403': res('Sender not allowed', err), '404': res('No such open request', err), '401': res('Bad or missing token', err) },
        },
      },
    },
  }
}
