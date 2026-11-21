// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** When a change to an option takes effect. */
export type AppliesWhen = 'startup' | 'runtime' | 'build';

export const AREAS = [
  'Server and network',
  'Security',
  'Storage',
  'Scheduler and engine',
  'Plugins',
  'Agents',
  'MCP',
  'Logging and observability',
  'Run mode',
] as const;
export type Area = (typeof AREAS)[number];

export interface ConfigOption {
  /** The Spring property key (yaml / `-D`), or the variable name for env-only options. */
  key: string;
  area: Area;
  default: string;
  purpose: string;
  appliesWhen: AppliesWhen;
  /** Set only as `-Dkey=value`; the environment variable and yaml are ignored. */
  sysprop?: boolean;
  /** An explicit environment variable when it is not the relaxed-binding form of `key`. */
  env?: string;
  /** What differs in the GraalVM native binary (Docker runs the native binary). */
  native?: string;
}

/** The prefixes `GET /api/config` reads; any other key makes the whole request fail with 400. */
const READABLE = /^(yukta|server|spring|management|logging|springdoc)\./;

/** The environment variable for a key: upper case, dots and dashes become underscores. */
export const envName = (o: ConfigOption) =>
  o.env ?? o.key.toUpperCase().replace(/[.-]/g, '_');

const s = 'startup' as const;
const FROZEN =
  'Frozen when the native image is built: the value that was set at build time wins. Build with the value you need.';

type Spec = Omit<ConfigOption, 'area' | 'appliesWhen'> &
  Partial<Pick<ConfigOption, 'appliesWhen'>>;
const group = (area: Area, rows: Spec[]): ConfigOption[] =>
  rows.map((r) => ({ appliesWhen: s, ...r, area }));

export const CATALOG: ConfigOption[] = [
  ...group('Server and network', [
    {
      key: 'server.port',
      env: 'SERVER_PORT',
      default: '9858',
      purpose: 'HTTP port for the API, the web UI and the MCP endpoint.',
    },
    {
      key: 'server.address',
      env: 'YUKTA_SERVER_ADDRESS',
      default: '127.0.0.1',
      purpose:
        'Address to bind. A non-loopback address without an API key stops the server at start. The Docker image sets 0.0.0.0.',
    },
    {
      key: 'spring.codec.max-in-memory-size',
      env: 'YUKTA_LIMITS_MAX_BODY',
      default: '1MB',
      purpose: 'Largest request body; a bigger one gets 413.',
    },
    {
      key: 'yukta.allowed-hosts',
      default: 'loopback names',
      purpose: 'Extra Host header values accepted while no API key is set.',
    },
    {
      key: 'yukta.allowed-origins',
      default: 'none',
      purpose:
        'Extra Origin values accepted for changing requests while no API key is set. The only CORS-like control.',
    },
    {
      key: 'yukta.rate-limit-per-minute',
      sysprop: true,
      default: '1200',
      purpose: '/api calls per key per minute.',
    },
    {
      key: 'yukta.auth-failures-per-minute',
      sysprop: true,
      default: '20',
      purpose: 'Wrong-key attempts per address per minute.',
    },
    {
      key: 'yukta.streams.max-concurrent',
      sysprop: true,
      default: '500',
      purpose: 'Open server-sent-event streams, all clients together.',
    },
    {
      key: 'yukta.streams.max-per-key',
      sysprop: true,
      default: '50',
      purpose: 'Open server-sent-event streams per API key.',
    },
    {
      key: 'yukta.streams.max-lifetime',
      sysprop: true,
      default: '1h',
      purpose: 'How long one stream stays open before the client reconnects.',
    },
    {
      key: 'spring.threads.virtual.enabled',
      default: 'true',
      purpose: 'Run blocking work on virtual threads.',
    },
    {
      key: 'spring.web.compression.enabled',
      default: 'true (prod profile)',
      purpose:
        'gzip responses. `spring.web.compression.min-response-size` is 1024.',
    },
  ]),
  ...group('Security', [
    {
      key: 'yukta.api-keys[0].key',
      env: 'YUKTA_API_KEYS_0_KEY',
      default: 'none (no auth)',
      purpose:
        'An API key. With none configured every caller is ADMIN, so only bind to loopback. Repeat with index 1, 2 … for more keys.',
    },
    {
      key: 'yukta.api-keys[0].name',
      env: 'YUKTA_API_KEYS_0_NAME',
      default: 'unnamed',
      purpose: 'Label shown for the key in the UI and audit trail.',
    },
    {
      key: 'yukta.api-keys[0].role',
      env: 'YUKTA_API_KEYS_0_ROLE',
      default: 'VIEWER',
      purpose:
        'VIEWER reads, OPERATOR also controls runs and conversations, ADMIN does everything.',
    },
    {
      key: 'yukta.api-keys[0].sessions',
      env: 'YUKTA_API_KEYS_0_SESSIONS_0',
      default: 'unset',
      purpose: 'Limit the key to these session ids (`*` for all).',
    },
    {
      key: 'yukta.api-keys[0].expires',
      env: 'YUKTA_API_KEYS_0_EXPIRES',
      default: 'never',
      purpose: 'When the key stops working.',
    },
    {
      key: 'yukta.input.token-secret',
      default: 'random per start',
      purpose:
        'Signs one-time answer tokens. Set it so tokens survive a restart or work across instances.',
    },
    {
      key: 'yukta.variables.allowed',
      default: 'empty',
      purpose: 'Extra `${env.X}` and `${sys.x}` names workflows may read.',
    },
    {
      key: 'YUKTA_SECRET_<NAME>',
      env: 'YUKTA_SECRET_<NAME>',
      default: 'none',
      purpose:
        'A secret a workflow reads as `decrypted:<name>`. Scope by tenant or session with `YUKTA_SECRET_T_<tenant>__<NAME>` and `YUKTA_SECRET_S_<session>__<NAME>`.',
    },
    {
      key: 'yukta.agent.requests-per-minute',
      default: '600',
      purpose: 'Agent credential API calls per minute.',
    },
    {
      key: 'yukta.agent.failures-per-minute',
      default: '20',
      purpose: 'Wrong agent credentials per minute.',
    },
    {
      key: 'yukta.supervisor.lock-holders',
      default: '[]',
      purpose: 'API key names allowed to change a locked assignment.',
    },
  ]),
  ...group('Storage', [
    {
      key: 'yukta.session.store-type',
      default: 'in-memory',
      purpose:
        '`in-memory`, `file` (Plus edition) or `postgres` (build with `-Pyukta.persistence=postgres`). An unavailable value stops the server at start.',
      native: FROZEN,
    },
    {
      key: 'yukta.session.base-dir',
      default: '~/.yukta',
      purpose: 'Folder of the file store. Mount a volume here in Docker.',
    },
    {
      key: 'yukta.restart.store-type',
      default: 'memory (file when dir is set)',
      purpose: 'Where node checkpoints live: `memory`, `file` or `postgres`.',
      native: FROZEN,
    },
    {
      key: 'yukta.restart.dir',
      default: 'unset',
      purpose: 'Folder for node checkpoints (Plus edition).',
      native: FROZEN,
    },
    {
      key: 'yukta.restart.auto-resume',
      default: 'false',
      purpose: 'Resume interrupted runs from their checkpoints at start.',
    },
    {
      key: 'yukta.restart.retained-executions',
      default: '10',
      purpose: 'Executions whose checkpoints are kept.',
    },
    {
      key: 'yukta.execution-log.dir',
      default: 'unset (in memory)',
      purpose: 'Folder for the JSONL execution log (Plus edition).',
      native: FROZEN,
    },
    {
      key: 'yukta.execution-log.retained-runs',
      default: '500',
      purpose: 'Runs kept in the execution log.',
    },
    {
      key: 'yukta.input.store-type',
      default: 'memory',
      purpose: 'Where open input requests live: `memory` or `postgres`.',
      native: FROZEN,
    },
    {
      key: 'yukta.agents.store-type',
      default: 'memory',
      purpose: 'Where agent conversations live: `memory` or `postgres`.',
      native: FROZEN,
    },
    {
      key: 'yukta.ha.mode',
      default: 'single',
      purpose: '`single` or `postgres` (leader lease across instances).',
      native: FROZEN,
    },
    {
      key: 'yukta.ha.lease',
      default: '15s',
      purpose: 'Leader lease length.',
    },
    {
      key: 'spring.r2dbc.url',
      default: 'none',
      purpose:
        'Postgres connection for the running server (profile `postgres`). Also `.username` and `.password`.',
    },
    {
      key: 'spring.flyway.url',
      default: 'none',
      purpose:
        'Postgres connection for schema migrations. Also `.user` and `.password`.',
    },
    {
      key: 'yukta.bootstrap.dir',
      default: 'unset',
      purpose:
        'Session files applied at start. `yukta.bootstrap.watch` re-applies them on change.',
      native: FROZEN,
    },
    {
      key: 'yukta.cache.ttl',
      default: '10m',
      purpose:
        'How long a prepared workflow stays cached. `yukta.cache.max-size` is 1000.',
    },
    {
      key: 'yukta.restart.messages-per-node',
      default: '200',
      purpose: 'Messages kept per node in a checkpoint.',
    },
    {
      key: 'yukta.restart.bytes-per-node',
      default: '4194304',
      purpose: 'Bytes kept per node in a checkpoint.',
    },
    {
      key: 'yukta.restart.spill-dir',
      default: 'empty',
      purpose: 'Folder where checkpoint data over the limit is written.',
    },
    {
      key: 'yukta.restart.payload-packages',
      default: '[]',
      purpose: 'Java packages whose types may appear in a checkpoint.',
    },
    {
      key: 'yukta.execution-log.compact-after',
      default: '10000',
      purpose: 'Log entries written before the log is compacted.',
    },
    {
      key: 'yukta.execution-log.min-runs-per-session',
      default: '10',
      purpose: 'Runs kept per session when the log is trimmed.',
    },
  ]),
  ...group('Scheduler and engine', [
    {
      key: 'yukta.execution.max-concurrent',
      default: '0 (no limit)',
      appliesWhen: 'runtime',
      purpose:
        'Runs at once, server wide. Changeable without a restart on the Runtime tab or with `yukta-cli scheduler`; the change is lost on restart.',
    },
    {
      key: 'yukta.execution.max-concurrent-per-workflow',
      default: '0 (no limit)',
      appliesWhen: 'runtime',
      purpose:
        'Runs at once per workflow. Changeable at runtime like the one above.',
    },
    {
      key: 'yukta.execution.max-concurrent-per-session',
      default: '0 (no limit)',
      purpose: 'Runs at once per session.',
    },
    {
      key: 'yukta.execution.queue-capacity',
      default: '100',
      purpose: 'Runs that may wait for a slot.',
    },
    {
      key: 'yukta.execution.on-limit',
      default: 'QUEUE',
      purpose: '`QUEUE` waits for a slot, `REJECT` answers 429.',
    },
    {
      key: 'yukta.execution.timeout',
      default: '1h',
      purpose: 'Longest a run may take.',
    },
    {
      key: 'yukta.execution.shutdown-timeout',
      default: '30s',
      purpose: 'How long a stop waits for running work.',
    },
    {
      key: 'yukta.execution.max-starts-per-minute',
      default: '0 (no limit)',
      purpose: 'Run starts per minute.',
    },
    {
      key: 'yukta.results.retention',
      default: '1h',
      purpose:
        'How long finished results stay readable. `yukta.results.max-executions` is 200.',
    },
    {
      key: 'yukta.streaming.status-history-ttl-minutes',
      default: '5',
      purpose: 'Minutes of status history replayed to a new stream (1 to 30).',
    },
    {
      key: 'yukta.tracker.cleanup-ttl',
      default: '10m',
      purpose: 'How long finished runs stay in the live tracker.',
    },
    {
      key: 'yukta.start.idempotency-ttl',
      default: '10m',
      purpose: 'How long an `Idempotency-Key` is remembered.',
    },
    {
      key: 'yukta.supervisor.dry-run',
      default: 'false',
      purpose: 'Supervisors decide and log but do not act.',
    },
    {
      key: 'yukta.supervisor.max-commands-per-second',
      default: '20',
      purpose: 'Commands a supervisor may send per second.',
    },
    {
      key: 'yukta.supervisor.rules.file',
      env: 'YUKTA_SUPERVISOR_RULES_FILE',
      default: 'unset',
      purpose: 'A rules file; setting it turns on the rule supervisor.',
      native: FROZEN,
    },
    {
      key: 'yukta.input.default-ttl',
      default: '1h',
      purpose: 'How long a human-input request waits (maximum 24h).',
    },
    {
      key: 'yukta.notifications.enabled',
      default: 'true',
      purpose: 'The notification inbox. `retention` is 7d, `max-items` 5000.',
    },
    {
      key: 'yukta.metrics.enabled',
      default: 'true',
      purpose: 'Built-in metrics sampler. `interval` is 5s.',
    },
    {
      key: 'yukta.script.timeout-ms',
      sysprop: true,
      default: '10000',
      purpose: 'Time limit for a JavaScript node.',
    },
    {
      key: 'yukta.spel.timeout-ms',
      sysprop: true,
      default: '1000',
      purpose: 'Time limit for one expression.',
    },
    {
      key: 'yukta.execution.max-concurrent-children',
      default: '0 (no limit)',
      purpose: 'Child (sub-workflow) runs at once.',
    },
    {
      key: 'yukta.execution.queue-capacity-per-workflow',
      default: '0',
      purpose: 'Runs that may wait per workflow; 0 sets no separate limit.',
    },
    {
      key: 'yukta.execution.queue-timeout',
      default: '0',
      purpose: 'How long a queued run waits for a slot; 0 sets no limit.',
    },
    {
      key: 'yukta.results.retained-events',
      default: '1000',
      purpose: 'Events kept for a finished run.',
    },
    {
      key: 'yukta.start.idempotency-max-keys',
      default: '100000',
      purpose: 'Idempotency keys remembered.',
    },
    {
      key: 'yukta.stop.drain-timeout',
      default: '30s',
      purpose: 'How long a stop with drain waits for running work.',
    },
    {
      key: 'yukta.streaming.status-history-max-per-run',
      default: '200',
      purpose: 'Status events replayed per run to a new stream.',
    },
    {
      key: 'yukta.streaming.status-history-max-executions',
      default: '1000',
      purpose: 'Runs whose status history is kept.',
    },
    {
      key: 'yukta.tracker.max-sessions',
      default: '0 (no limit)',
      purpose: 'Sessions the live tracker holds.',
    },
    {
      key: 'yukta.tracker.session-ttl',
      default: 'unset',
      purpose: 'How long an idle session stays in the live tracker.',
    },
    {
      key: 'yukta.control.approval.ttl',
      default: '10m',
      purpose: 'How long an approval request waits.',
    },
    {
      key: 'yukta.supervisor.buffer-size',
      default: '10000',
      purpose: 'Events a supervisor may have waiting.',
    },
    {
      key: 'yukta.supervisor.default.enabled',
      default: 'true',
      purpose: 'The default supervisor, which carries what nobody owns.',
    },
    {
      key: 'yukta.supervisor.max-attachments',
      default: '200',
      purpose: 'Attachments one supervisor may hold.',
    },
    {
      key: 'yukta.supervisor.review-timeout',
      default: '5s',
      purpose: 'Time a reviewer has to answer a command.',
    },
    {
      key: 'yukta.supervisor.turn-around.period',
      default: '60s',
      purpose: 'Window in which turn-arounds are counted.',
    },
    {
      key: 'yukta.supervisor.turn-around.limit',
      default: '4',
      purpose: 'Turn-arounds allowed within the period.',
    },
    {
      key: 'yukta.supervisor.turn-around.cooldown',
      default: '5m',
      purpose: 'Pause after the limit is reached.',
    },
    {
      key: 'yukta.input.max-ttl',
      default: '24h',
      purpose: 'Longest a human-input request may wait.',
    },
    {
      key: 'yukta.input.max-open-per-execution',
      default: '5',
      purpose: 'Open input requests per run.',
    },
    {
      key: 'yukta.input.max-open-per-session',
      default: '50',
      purpose: 'Open input requests per session.',
    },
    {
      key: 'yukta.input.memory-max',
      default: '10000',
      purpose: 'Input requests kept in memory.',
    },
    {
      key: 'yukta.input.retention',
      default: '30d',
      purpose: 'How long answered requests are kept.',
    },
    {
      key: 'yukta.input.sweep-interval',
      default: '1s',
      purpose: 'How often expired requests are cleaned up.',
    },
    {
      key: 'yukta.input.redact-keys',
      default: '[]',
      purpose: 'Data keys whose values are hidden in input requests.',
    },
    {
      key: 'yukta.input.routes',
      default: 'none',
      purpose:
        'Named routes that say where input requests are sent (`yukta.input.routes.<name>`).',
    },
    {
      key: 'yukta.notifications.retention',
      default: '7d',
      purpose: 'How long notifications are kept.',
    },
    {
      key: 'yukta.notifications.max-items',
      default: '5000',
      purpose: 'Notifications kept.',
    },
    {
      key: 'yukta.notifications.action-grace',
      default: '2s',
      purpose: 'Grace period for notification actions.',
    },
    {
      key: 'yukta.notifications.rules',
      default: 'none',
      purpose: 'Rules for what is notified (`yukta.notifications.rules.*`).',
    },
    {
      key: 'yukta.notifications.channels',
      default: 'none',
      purpose:
        'Where notifications go (`yukta.notifications.channels.<type>.<name>.*`).',
    },
    {
      key: 'yukta.metrics.interval',
      default: '5s',
      purpose: 'How often meters are sampled.',
    },
    {
      key: 'yukta.metrics.fine-retention',
      default: '1h',
      purpose: 'How long fine-grained samples are kept.',
    },
    {
      key: 'yukta.metrics.coarse-retention',
      default: '24h',
      purpose: 'How long coarse samples are kept.',
    },
    {
      key: 'yukta.metrics.max-series',
      default: '500',
      purpose: 'Most series the sampler records.',
    },
    {
      key: 'yukta.metrics.allow-list',
      default: 'built-in list',
      purpose: 'Meters the sampler records.',
    },
    {
      key: 'yukta.workers.lease',
      default: '15s',
      purpose:
        'How long a remote worker holds a lease before it must renew (Plus).',
    },
  ]),
  ...group('Plugins', [
    {
      key: 'yukta.plugins.enabled',
      env: 'YUKTA_PLUGINS_ENABLED',
      default: 'empty',
      purpose:
        'Plugins to switch on beyond the always-on core set; `all` enables every plugin compiled in. Cannot add a plugin the build left out (`-Pyukta.plugins`).',
    },
    {
      key: 'yukta.plugins.dir',
      env: 'YUKTA_PLUGINS_DIR',
      default: 'none',
      purpose: 'Folder of community plugins, run as separate processes.',
    },
    {
      key: 'yukta.plugins.container.runtime',
      env: 'YUKTA_PLUGINS_CONTAINER_RUNTIME',
      default: 'docker',
      purpose: 'Container CLI used to run community plugins.',
    },
    {
      key: 'yukta.process.allowed-commands',
      env: 'YUKTA_PROCESS_ALLOWED_COMMANDS',
      default: 'empty (runs nothing)',
      purpose: 'Commands the process executor may run, for example `sh,echo`.',
    },
    {
      key: 'yukta.process.allow-any',
      env: 'YUKTA_PROCESS_ALLOW_ANY',
      default: 'false',
      purpose: 'Allow any executable. Only while no allow-list is set.',
    },
    {
      key: 'yukta.files.allowed-root',
      env: 'YUKTA_FILES_ALLOWED_ROOT',
      default: 'unset',
      purpose: 'The folder file nodes may read and write.',
    },
    {
      key: 'yukta.http.allowed-hosts',
      default: 'empty (any)',
      purpose: 'Hosts the HTTP request node may call.',
    },
    {
      key: 'yukta.egress.allowed-hosts',
      default: 'empty',
      purpose: 'Global outbound host allow-list.',
    },
    {
      key: 'yukta.mcp-client.allowed-tools',
      default: 'empty',
      purpose: 'Tools the MCP tool-call node may call.',
    },
    {
      key: 'yukta.llm.max-input-chars',
      default: '0 (no cap)',
      purpose: 'Longest prompt the LLM node sends.',
    },
    {
      key: 'yukta.ai.presets-dir',
      env: 'YUKTA_AI_PRESETS_DIR',
      default: 'empty',
      purpose: 'Folder of agent presets.',
    },
  ]),
  ...group('Agents', [
    {
      key: 'yukta.agents.enabled',
      default: 'true',
      purpose: 'The agent console and conversation store.',
      native: FROZEN,
    },
    {
      key: 'yukta.agents.retention',
      default: '7d',
      purpose: 'How long conversations are kept.',
    },
    {
      key: 'yukta.agents.max-conversations',
      default: '500',
      purpose: 'Conversations kept.',
    },
    {
      key: 'yukta.agents.max-events-per-conversation',
      default: '5000',
      purpose: 'Events kept per conversation.',
    },
    {
      key: 'yukta.agents.messages-per-minute',
      default: '30',
      purpose: 'Messages a client may send an agent per minute.',
    },
    {
      key: 'yukta.agents.pty.enabled',
      default: 'false',
      purpose: 'Terminal tier for agents. Also `host`, `cols` and `rows`.',
    },
    {
      key: 'yukta.agents.max-message-bytes',
      default: '65536',
      purpose: 'Largest message sent to an agent.',
    },
    {
      key: 'yukta.agents.max-tool-bytes',
      default: '4096',
      purpose: 'Cap on the bytes kept for one tool call in a conversation.',
    },
    {
      key: 'yukta.agents.terminal-ring-bytes',
      default: '262144',
      purpose: 'Terminal output kept per agent (a ring buffer).',
    },
    {
      key: 'yukta.agents.pty.host',
      default: 'yukta-cli',
      purpose: 'Program that hosts an agent terminal.',
    },
    {
      key: 'yukta.agents.pty.cols',
      default: '120',
      purpose: 'Terminal width in columns.',
    },
    {
      key: 'yukta.agents.pty.rows',
      default: '40',
      purpose: 'Terminal height in rows.',
    },
  ]),
  ...group('MCP', [
    {
      key: 'spring.ai.mcp.server.enabled',
      default: 'true',
      purpose: 'The MCP server at `/mcp` (stateless streamable HTTP).',
    },
    {
      key: 'spring.ai.mcp.server.streamable-http.mcp-endpoint',
      default: '/mcp',
      purpose: 'Path of the MCP endpoint.',
    },
    {
      key: 'yukta.mcp.rate-limit-per-minute',
      default: '600',
      purpose: 'MCP calls per minute.',
    },
    {
      key: 'yukta.mcp.heavy-rate-limit-per-minute',
      default: '60',
      purpose: 'Calls per minute for heavy tools.',
    },
    {
      key: 'yukta.assistant.enabled',
      default: 'true',
      purpose:
        'The relay behind the experimental UI assistant (`POST /api/assistant/proxy/{provider}`); false answers 404.',
    },
    {
      key: 'yukta.assistant.allowed-base-urls',
      default:
        'api.openai.com, generativelanguage.googleapis.com, openrouter.ai',
      purpose:
        'Hosts an OpenAI-compatible base URL of the assistant may name. An empty list allows none; list Ollama or LM Studio hosts here.',
    },
    {
      key: 'yukta.assistant.anthropic-url',
      default: 'https://api.anthropic.com/v1/messages',
      purpose: 'Where the assistant relay sends Anthropic requests.',
    },
    {
      key: 'yukta.mcp.start-dedupe-window',
      default: '30s',
      purpose: 'Identical start calls inside this window return the same run.',
    },
  ]),
  ...group('Logging and observability', [
    {
      key: 'logging.level.root',
      default: 'WARN (prod), INFO',
      purpose:
        'Log level. Set `logging.level.com.infenia.yukta` for Yukta alone. Not changeable at runtime.',
    },
    {
      key: 'management.endpoints.web.exposure.include',
      default: 'health,info,metrics',
      purpose:
        'Actuator endpoints. Everything except `/actuator/health` needs an ADMIN key.',
    },
    {
      key: 'management.endpoint.health.show-details',
      default: 'never',
      purpose: 'Detail in `/actuator/health`.',
    },
    {
      key: 'management.otlp.metrics.export.enabled',
      default: 'false',
      purpose:
        'Export metrics over OTLP. Set `management.otlp.metrics.export.url`.',
    },
    {
      key: 'management.tracing.export.enabled',
      default: 'false',
      purpose:
        'Export traces. Set `management.opentelemetry.tracing.export.otlp.endpoint`.',
    },
    {
      key: 'springdoc.swagger-ui.enabled',
      default: 'false (prod), true',
      purpose:
        'Swagger UI. `springdoc.api-docs.enabled` turns the OpenAPI document on or off the same way.',
    },
    {
      key: 'yukta.logs.store.retention.default-period-minutes',
      default: '30',
      purpose: 'Minutes plugin logs are kept (1 to 1440).',
    },
  ]),
  ...group('Run mode', [
    {
      key: 'spring.profiles.active',
      env: 'SPRING_PROFILES_ACTIVE',
      default: 'prod',
      purpose:
        'Profiles. `prod` is the default; name both to add one (`prod,postgres`), because naming only `postgres` replaces `prod`.',
      native: 'A native binary uses `prod` unless a profile is given.',
    },
    {
      key: 'yukta.run-mode',
      env: 'YUKTA_MODE',
      default: 'console on a terminal, else plain',
      purpose: '`console`, `plain` or `daemon` (also `--mode=` or `--plain`).',
    },
    {
      key: 'YUKTA_HOME',
      env: 'YUKTA_HOME',
      default: '~/.local/state/yukta',
      purpose: 'Folder of the daemon pid and log files.',
    },
  ]),
];

/** The catalog keys the server can report a value for (not variable-only names like YUKTA_HOME). */
export const readableKeys = (): string[] =>
  CATALOG.map((o) => o.key).filter((key) => READABLE.test(key));
