// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * The data behind the documentation screenshots: one invented release pipeline, seen from every
 * page. Every value is typed against the UI's own types (../../../src/...), so an API change that
 * the UI follows is a compile error here (run `pnpm docs:screenshots:types`). Keep the story
 * consistent: ids used by one shot appear in the others.
 */
import type { EditionInfo } from '../../../src/features/edition/types';
import type { SessionDraft } from '../../../src/features/builder/types';
import type {
  ControlAnnotation,
  HeatCell,
  LiveRun,
  ModuleRow,
} from '../../../src/features/metrics/api';
import type { PluginDetails } from '../../../src/features/plugins/types';
import type { SchedulerLimits } from '../../../src/features/control/api/scheduler';
import type { Worker } from '../../../src/features/control/api/workers';
import type { SessionListItem } from '../../../src/features/sessions/types/session';
import type {
  Decision,
  AttachmentView,
  SupervisorTypeDetails,
  SupervisorTypeSummary,
  SupervisorView,
} from '../../../src/features/supervisors/types';
import type { InputRequest } from '../../../src/features/workflow/api/inputs';
import type {
  ExecutionSummary,
  LogEntry,
  WorkflowGraph,
  WorkflowGraphNode,
  WorkflowProgress,
} from '../../../src/features/workflow/types/workflow';

/** The instant the page believes it is: shortly after the running release started. */
export const NOW = new Date('2026-07-26T14:30:00Z');
const at = (minutesAgo: number) =>
  new Date(NOW.getTime() - minutesAgo * 60_000).toISOString();

export const SESSION_ID = 'release-pipeline';
export const WORKFLOW_ID = 'release';
export const EXECUTION_ID = 'run-7f3a91c2';
export const WORKFLOW_URL = `/sessions/${SESSION_ID}/workflow/${WORKFLOW_ID}`;

// ---- sessions ----------------------------------------------------------------------------

const lastRun = (
  workflowId: string,
  status: string,
  minutesAgo: number
): ExecutionSummary => ({
  executionId: `run-${workflowId}-${minutesAgo}`,
  workflowId,
  status,
  startTime: at(minutesAgo),
  endTime: status === 'RUNNING' ? null : at(minutesAgo - 4),
});

export const SESSIONS: SessionListItem[] = [
  {
    sessionId: SESSION_ID,
    name: 'Release pipeline',
    description: 'Validate, approve and deploy a release',
    initiator: 'github-actions',
    tags: ['production', 'release'],
    projectPath: 'acme/storefront',
    workflowCount: 2,
    lastRun: {
      ...lastRun(WORKFLOW_ID, 'RUNNING', 8),
      executionId: EXECUTION_ID,
    },
  },
  {
    sessionId: 'pr-quality-gate',
    name: 'PR quality gate',
    description: 'Compile, test and review every pull request',
    initiator: 'claude-code',
    tags: ['review', 'quality-gate'],
    projectPath: 'acme/storefront',
    workflowCount: 3,
    lastRun: lastRun('gate', 'SUCCESS', 42),
  },
  {
    sessionId: 'nightly-etl',
    name: 'Nightly ETL',
    description: "Load yesterday's orders into the warehouse",
    initiator: 'scheduler',
    tags: ['nightly', 'data'],
    projectPath: 'acme/data-platform',
    workflowCount: 2,
    lastRun: lastRun('load-orders', 'SUCCESS', 700),
  },
  {
    sessionId: 'support-triage',
    name: 'Support triage',
    description: 'Classify tickets and draft a first reply',
    initiator: 'webhook',
    tags: ['support', 'ai'],
    projectPath: 'acme/support',
    workflowCount: 1,
    lastRun: lastRun('triage', 'FAILURE', 95),
  },
  {
    sessionId: 'dependency-audit',
    name: 'Dependency audit',
    description: 'Weekly scan of lockfiles for known vulnerabilities',
    initiator: 'scheduler',
    tags: ['security', 'weekly'],
    projectPath: 'acme/storefront',
    workflowCount: 1,
    lastRun: lastRun('scan', 'SUCCESS', 2900),
  },
];

// ---- the release workflow ----------------------------------------------------------------

const node = (
  nodeId: string,
  type: string,
  category: WorkflowGraphNode['category'],
  description: string,
  outputPorts: string[] = ['default'],
  group: string | null = null
): WorkflowGraphNode => ({
  nodeId,
  type,
  category,
  description,
  uiDesign: null,
  outputPorts,
  group,
});

export const GRAPH: WorkflowGraph = {
  workflowId: WORKFLOW_ID,
  description: 'Validate, approve and deploy a release',
  nodes: [
    node('on_tag', 'WEBHOOK_TRIGGER', 'TRIGGER', 'A release tag was pushed'),
    node(
      'validate',
      'VALIDATE',
      'PROCESSOR',
      'Check artifact checksums and schema'
    ),
    node('risk_gate', 'BRANCH', 'PROCESSOR', 'High risk needs a person', [
      'high',
      'low',
    ]),
    node('draft_notes', 'LLM_CALL', 'PROCESSOR', 'Draft the release notes'),
    node(
      'approve',
      'ASK_INPUT',
      'PROCESSOR',
      'Ask a release manager to approve'
    ),
    node('deploy', 'HTTP_REQUEST', 'PROCESSOR', 'Roll out to production'),
    node('notify', 'NOTIFY_TERMINAL', 'TERMINAL', 'Tell the team in chat', []),
  ],
  edges: [
    { source: 'on_tag', target: 'validate', sourcePort: null },
    { source: 'validate', target: 'risk_gate', sourcePort: null },
    { source: 'validate', target: 'draft_notes', sourcePort: null },
    { source: 'risk_gate', target: 'approve', sourcePort: 'high' },
    { source: 'risk_gate', target: 'deploy', sourcePort: 'low' },
    { source: 'approve', target: 'deploy', sourcePort: null },
    { source: 'deploy', target: 'notify', sourcePort: null },
    { source: 'draft_notes', target: 'notify', sourcePort: null },
  ],
  topologicalOrder: [
    'on_tag',
    'validate',
    'risk_gate',
    'draft_notes',
    'approve',
    'deploy',
    'notify',
  ],
};

const done = (nodeId: string, module: string, start: number, end: number) => ({
  nodeId,
  module,
  status: 'SUCCESS',
  startTime: at(start),
  endTime: at(end),
});
const pending = (nodeId: string, module: string) => ({
  nodeId,
  module,
  status: 'PENDING',
  startTime: null,
  endTime: null,
});

export const PROGRESS: WorkflowProgress = {
  executionId: EXECUTION_ID,
  sessionId: SESSION_ID,
  workflowId: WORKFLOW_ID,
  status: 'RUNNING',
  startTime: at(8),
  endTime: null,
  tasks: [
    done('on_tag', 'WEBHOOK_TRIGGER', 8, 8),
    done('validate', 'VALIDATE', 8, 6),
    done('risk_gate', 'BRANCH', 6, 6),
    done('draft_notes', 'LLM_CALL', 6, 4),
    {
      nodeId: 'approve',
      module: 'ASK_INPUT',
      status: 'RUNNING',
      startTime: at(4),
      endTime: null,
    },
    pending('deploy', 'HTTP_REQUEST'),
    pending('notify', 'NOTIFY_TERMINAL'),
  ],
};

export const EXECUTIONS: ExecutionSummary[] = [
  {
    executionId: EXECUTION_ID,
    workflowId: WORKFLOW_ID,
    status: 'RUNNING',
    startTime: at(8),
    endTime: null,
  },
  {
    executionId: 'run-61be02d9',
    workflowId: WORKFLOW_ID,
    status: 'SUCCESS',
    startTime: at(1500),
    endTime: at(1492),
  },
  {
    executionId: 'run-2c90ae14',
    workflowId: WORKFLOW_ID,
    status: 'FAILURE',
    startTime: at(4300),
    endTime: at(4296),
  },
];

const log = (
  minutesAgo: number,
  pluginId: string,
  pluginName: string,
  message: string,
  level = 'INFO'
): LogEntry => ({
  executionId: EXECUTION_ID,
  pluginId,
  pluginName,
  stream: 'STDOUT',
  message,
  level,
  timestamp: at(minutesAgo),
});

export const LOGS: LogEntry[] = [
  log(
    8,
    'on_tag',
    'WEBHOOK_TRIGGER',
    'Tag v2.8.0 received from acme/storefront'
  ),
  log(
    8,
    'validate',
    'VALIDATE',
    'storefront-2.8.0.tar.gz: checksum matches, manifest schema valid'
  ),
  log(
    6,
    'risk_gate',
    'BRANCH',
    'changed paths include payments/: routing to port "high"'
  ),
  log(6, 'draft_notes', 'LLM_CALL', 'Drafting release notes from 41 commits'),
  log(4, 'draft_notes', 'LLM_CALL', 'Drafted release notes (312 tokens)'),
  log(
    4,
    'approve',
    'ASK_INPUT',
    'Waiting for a release manager to answer',
    'WARN'
  ),
];

// ---- an input request waiting in the release ---------------------------------------------

export const PENDING_INPUT: InputRequest = {
  id: 'inp-5d21',
  sessionId: SESSION_ID,
  workflowId: WORKFLOW_ID,
  executionId: EXECUTION_ID,
  nodeId: 'approve',
  state: 'OPEN',
  version: 1,
  kind: 'APPROVAL',
  prompt:
    'Release v2.8.0 touches the payments service (41 commits). Deploy to production?',
  context: { version: '2.8.0', risk: 'high', commits: 41 },
  options: [],
  responseSchema: null,
  risk: 'HIGH',
  tier: 'operator',
  advice: 'Checksums match and the release notes are drafted.',
  deadline: new Date(NOW.getTime() + 25 * 60_000).toISOString(),
  createdAt: at(4),
  updatedAt: at(4),
  answer: null,
  asked: [
    {
      who: 'release-guard',
      said: 'asked for approval',
      reason: null,
      at: at(4),
    },
    {
      who: 'release-lead',
      said: 'no-opinion',
      reason: 'needs a person for payments changes',
      at: at(3),
    },
  ],
};

// ---- supervisors -------------------------------------------------------------------------

const supervisor = (
  id: string,
  extra: Partial<SupervisorView>
): SupervisorView => ({
  id,
  type: 'rules',
  description: null,
  settings: {},
  dryRun: false,
  system: false,
  attachments: 1,
  applied: 0,
  rejected: 0,
  recorded: 0,
  rateLimited: 0,
  parents: [],
  delegation: {},
  ...extra,
});

export const SUPERVISORS: SupervisorView[] = [
  supervisor('release-lead', {
    type: 'http',
    description: 'The release agent: decides what the rules below cannot.',
    settings: { url: 'https://agent.acme.example/yukta', secretHeaders: '***' },
    applied: 14,
    recorded: 31,
  }),
  supervisor('release-guard', {
    description: 'Pauses the release when deploys keep failing.',
    parents: ['release-lead'],
    delegation: {
      APPROVAL: { mode: 'ORDERED', timeout: 'PT10M' },
      INPUT: { mode: 'ORDERED' },
    },
    applied: 6,
    rejected: 1,
    recorded: 12,
  }),
  supervisor('triage-flow', {
    type: 'workflow',
    description:
      'Runs the support-triage workflow to decide what to do with a stuck ticket.',
    settings: { sessionId: 'support-triage', workflowId: 'triage' },
    attachments: 1,
    applied: 3,
    recorded: 9,
  }),
];

export const SUPERVISOR_TYPES: SupervisorTypeSummary[] = [
  { type: 'rules', displayName: 'Rules', category: 'RULES', icon: 'rule' },
  {
    type: 'http',
    displayName: 'External system',
    category: 'EXTERNAL_SYSTEM',
    icon: 'api',
  },
  {
    type: 'workflow',
    displayName: 'Workflow on this server',
    category: 'ON_THIS_SERVER',
    icon: 'account_tree',
  },
];

export const supervisorTypeDetails = (
  summary: SupervisorTypeSummary
): SupervisorTypeDetails => ({
  ...summary,
  description: `${summary.displayName} decides what happens when a run needs a decision.`,
  usagePattern: '',
  configSchema: [],
  commonSchema: [],
  defaultAllowedCommands: null,
  exampleSettings: {},
  signals: ['EVENT', 'TICK', 'REVIEW'],
  canInitiate: summary.type !== 'http',
});

export const ASSIGNMENTS: AttachmentView[] = [
  {
    id: 'a-guard',
    supervisorId: 'release-guard',
    scope: `workflow:${SESSION_ID}/${WORKFLOW_ID}`,
    enabled: true,
    source: 'MANUAL',
    originSessionId: null,
    originWorkflowId: null,
  },
  {
    id: 'a-lead',
    supervisorId: 'release-lead',
    scope: `session:${SESSION_ID}`,
    enabled: true,
    source: 'MANUAL',
    originSessionId: null,
    originWorkflowId: null,
  },
  {
    id: 'a-triage',
    supervisorId: 'triage-flow',
    scope: 'workflow:support-triage/triage',
    enabled: true,
    source: 'DEFINITION',
    originSessionId: 'support-triage',
    originWorkflowId: 'triage',
  },
];

const decision = (
  minutesAgo: number,
  supervisorId: string,
  outcome: string,
  command: string,
  because: string,
  nodeId: string | null = null
): Decision => ({
  time: at(minutesAgo),
  supervisorId,
  outcome,
  command,
  executionId: EXECUTION_ID,
  reason: outcome === 'applied' ? null : 'not allowed',
  nodeId,
  cause: 'event',
  key: `${command}#${minutesAgo}`,
  because,
  workflowId: WORKFLOW_ID,
});

export const DECISIONS: Decision[] = [
  decision(
    3,
    'release-guard',
    'applied',
    'PauseNodeCommand',
    'deploy must wait for the payments approval',
    'deploy'
  ),
  decision(
    12,
    'release-lead',
    'applied',
    'RetryNodeCommand',
    'the checksum download timed out',
    'validate'
  ),
  decision(
    55,
    'release-guard',
    'rejected',
    'StopWorkflowCommand',
    '3 failures in 60 s'
  ),
];

export const RULES_VIEW = {
  version: 4,
  rules: {
    schemaVersion: 1,
    rules: [
      {
        id: 'pause-on-failures',
        when: {
          event: 'NodeStatusChanged',
          status: 'FAILURE',
          count: 3,
          withinSeconds: 60,
        },
        then: { command: 'PauseWorkflow', reason: 'too many failures' },
      },
    ],
  },
};

// ---- plugins -----------------------------------------------------------------------------

export const PLUGINS: PluginDetails[] = [
  {
    type: 'WEBHOOK_TRIGGER',
    category: 'TRIGGER',
    description: 'Starts the workflow when an HTTP request arrives.',
    usagePattern: 'type: WEBHOOK_TRIGGER',
    uiDesign: null,
    outputPorts: ['default'],
    configSchema: [],
  },
  {
    type: 'SCHEDULE_TRIGGER',
    category: 'TRIGGER',
    description: 'Starts the workflow on a cron schedule.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['default'],
    configSchema: [],
  },
  {
    type: 'PROCESS_EXECUTOR',
    category: 'PROCESSOR',
    description:
      'Runs a command on the server and reports its exit code and output.',
    usagePattern: 'type: PROCESS_EXECUTOR\nconfig:\n  command: pnpm build',
    uiDesign: null,
    outputPorts: ['success', 'failure'],
    configSchema: [
      {
        name: 'command',
        type: 'STRING',
        required: true,
        defaultValue: null,
        options: [],
        description: 'The command line to run',
      },
      {
        name: 'timeoutSeconds',
        type: 'NUMBER',
        required: false,
        defaultValue: 600,
        options: [],
        description: 'Kill the process after this many seconds',
      },
    ],
  },
  {
    type: 'VALIDATE',
    category: 'PROCESSOR',
    description:
      'Checks the incoming data against rules and routes it to pass or fail.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['pass', 'fail'],
    configSchema: [],
  },
  {
    type: 'BRANCH',
    category: 'PROCESSOR',
    description: 'Sends the data down one output port chosen by an expression.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['high', 'low'],
    configSchema: [],
  },
  {
    type: 'CLASSIFY',
    category: 'PROCESSOR',
    description: 'Asks a language model to pick one label for the input.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['high', 'low'],
    configSchema: [],
  },
  {
    type: 'LLM_CALL',
    category: 'PROCESSOR',
    description: 'Sends a prompt to a language model and passes on its answer.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['default'],
    configSchema: [],
  },
  {
    type: 'ASK_INPUT',
    category: 'PROCESSOR',
    description: 'Pauses the run until a person or supervisor answers.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['default'],
    configSchema: [
      {
        name: 'prompt',
        type: 'STRING',
        required: true,
        defaultValue: null,
        options: [],
        description: 'The question to ask',
      },
      {
        name: 'kind',
        type: 'ENUM',
        required: false,
        defaultValue: 'APPROVAL',
        options: ['APPROVAL', 'CHOICE', 'TEXT'],
        description: 'What kind of answer is expected',
      },
    ],
  },
  {
    type: 'HTTP_REQUEST',
    category: 'PROCESSOR',
    description: 'Calls an HTTP endpoint and passes on the response.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: ['success', 'failure'],
    configSchema: [],
  },
  {
    type: 'NOTIFY_TERMINAL',
    category: 'TERMINAL',
    description: 'Posts the final data to a chat channel or webhook.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
  {
    type: 'CONSOLE_TERMINAL',
    category: 'TERMINAL',
    description: 'Prints the final data to the run log.',
    usagePattern: null,
    uiDesign: null,
    outputPorts: [],
    configSchema: [],
  },
];

/** The release workflow as the editor loads it. */
export const SESSION_DRAFT: SessionDraft = {
  sessionId: SESSION_ID,
  name: 'Release pipeline',
  description: 'Validate, approve and deploy a release',
  initiator: 'github-actions',
  tags: { env: 'production' },
  projectPath: 'acme/storefront',
  workflows: {
    [WORKFLOW_ID]: {
      workflowId: WORKFLOW_ID,
      description: GRAPH.description,
      nodes: GRAPH.nodes.map((n) => ({
        nodeId: n.nodeId,
        type: n.type,
        config:
          n.type === 'ASK_INPUT'
            ? { prompt: 'Deploy to production?', kind: 'APPROVAL' }
            : {},
      })),
      edges: GRAPH.edges,
    },
  },
};

// ---- control dashboard -------------------------------------------------------------------

export const EDITION: EditionInfo = {
  edition: 'PLUS',
  license: 'acme-storefront',
  features: [],
  source: null,
};

export const LIMITS: SchedulerLimits = {
  maxConcurrent: 8,
  maxPerWorkflow: 2,
  running: 3,
  queued: 1,
  queueCapacity: 100,
};

export const WORKERS: Worker[] = [
  {
    workerId: 'worker-eu-1',
    pools: ['default'],
    pluginTypes: ['PROCESS_EXECUTOR', 'HTTP_REQUEST'],
    capacity: 4,
    usedSlots: 2,
    draining: false,
    registeredAt: at(600),
    leaseUntil: new Date(NOW.getTime() + 30_000).toISOString(),
  },
  {
    workerId: 'worker-eu-2',
    pools: ['default', 'gpu'],
    pluginTypes: ['LLM_CALL', 'CLASSIFY'],
    capacity: 4,
    usedSlots: 1,
    draining: false,
    registeredAt: at(580),
    leaseUntil: new Date(NOW.getTime() + 30_000).toISOString(),
  },
];

/** Sampled values; the series of each is a ramp ending at this value (see routes.ts). */
export const METRIC_VALUES: Record<string, number> = {
  'yukta.executions.running': 3,
  'runs.finished': 186,
  'runs.success': 171,
  'runs.failure': 15,
  'duration.p95': 94_000,
  'duration.p50': 31_000,
  'queue.depth': 1,
  'llm.cost|rate': 0.00045,
  'jvm.memory.used{area=heap,id=G1 Old Gen}': 612e6,
  'jvm.memory.max{area=heap,id=G1 Old Gen}': 2048e6,
  'process.cpu.usage': 0.18,
  'jvm.threads.live': 64,
};

/** One cell per minute for 24 h: busy in working hours, quiet at night. */
export const HEATMAP: HeatCell[] = Array.from({ length: 1440 }, (_, i) => {
  const hour = (i / 60 + 14.5 - 24 + 24) % 24;
  const busy = hour > 8 && hour < 19;
  const runs = busy ? 2 + ((i * 7) % 5) : (i * 3) % 4 === 0 ? 1 : 0;
  const failed = runs > 0 && i % 53 === 0 ? 1 : 0;
  return {
    time: NOW.getTime() - (1439 - i) * 60_000,
    runs,
    failed,
    failedRuns: failed ? [`run-fail-${i}`] : [],
  };
});

export const MODULES: ModuleRow[] = [
  { module: 'PROCESS_EXECUTOR', executions: 212, failures: 9, avgMs: 41_200 },
  { module: 'LLM_CALL', executions: 143, failures: 3, avgMs: 6_800 },
  { module: 'HTTP_REQUEST', executions: 188, failures: 4, avgMs: 1_150 },
  { module: 'VALIDATE', executions: 201, failures: 0, avgMs: 210 },
];

export const LIVE_RUNS: LiveRun[] = [
  {
    executionId: EXECUTION_ID,
    sessionId: SESSION_ID,
    workflowId: WORKFLOW_ID,
    startedAt: NOW.getTime() - 8 * 60_000,
    elapsedMs: 8 * 60_000,
    nodesDone: 6,
    nodesSeen: 9,
    p95Ms: 94_000,
  },
];

export const ANNOTATIONS: ControlAnnotation[] = [];
