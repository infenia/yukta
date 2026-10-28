// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/**
 * What an action in the UI looks like in the other clients: the CLI, curl and an MCP tool call.
 * The CLI syntax follows cli/internal/commands/workflow, the MCP tool and argument names follow
 * mcp/.../AppMcpTools.java and the REST paths follow web/.../ExecutionControlController.java.
 * The curl commands carry `$YUKTA_TOKEN`, never a real key.
 */

export type StopMode = 'DRAIN' | 'CANCEL' | 'KILL';

export type CommandAction =
  | {
      kind: 'start';
      sessionId: string;
      workflowId: string;
      payload?: Record<string, unknown>;
    }
  | { kind: 'stopAll'; sessionId: string; workflowId: string; mode: StopMode }
  | {
      kind: 'run';
      verb: 'pause' | 'resume' | 'stop' | 'restart';
      sessionId: string;
      executionId: string;
    }
  | {
      kind: 'restartFromNode';
      sessionId: string;
      executionId: string;
      nodeId: string;
    }
  | {
      kind: 'node';
      verb:
        | 'pause'
        | 'resume'
        | 'stop'
        | 'skip'
        | 'retry'
        | 'step'
        | 'step/enable'
        | 'step/disable';
      sessionId: string;
      executionId: string;
      nodeId: string;
    }
  | {
      kind: 'signal';
      sessionId: string;
      executionId: string;
      nodeId: string;
      name: string;
      decision: 'approve' | 'reject';
    };

export type CommandFormat = 'cli' | 'curl' | 'mcp';

const SAFE = /^[A-Za-z0-9_@%+=:,./-]+$/;

/** Quotes a value for a POSIX shell only when it needs it. */
export function shellQuote(value: string): string {
  return SAFE.test(value) ? value : `'${value.replace(/'/g, `'\\''`)}'`;
}

const sh = (...words: string[]) => words.map(shellQuote).join(' ');

const enc = encodeURIComponent;

/** The server the CLI talks to unless told otherwise (`--url`, env YUKTA_SERVER_URL). */
const CLI_DEFAULT_URL = 'http://localhost:8080';

/** `yukta-cli ...` for the action; a `baseUrl` other than the CLI's default is passed as `--url`. */
export function toCli(
  action: CommandAction,
  baseUrl = CLI_DEFAULT_URL
): string {
  const command = cliCommand(action);
  const url = baseUrl.replace(/\/$/, '');
  return url === CLI_DEFAULT_URL ? command : `${command} ${sh('--url', url)}`;
}

function cliCommand(action: CommandAction): string {
  switch (action.kind) {
    case 'start':
      return sh(
        'yukta-cli',
        'workflow',
        'start',
        action.sessionId,
        action.workflowId,
        ...(action.payload && Object.keys(action.payload).length > 0
          ? ['--payload', JSON.stringify(action.payload)]
          : [])
      );
    case 'stopAll':
      return sh(
        'yukta-cli',
        'workflow',
        'stop',
        action.sessionId,
        action.workflowId,
        '--mode',
        action.mode
      );
    case 'run':
      // Stopping and restarting a run take no session id.
      return action.verb === 'stop' || action.verb === 'restart'
        ? sh(
            'yukta-cli',
            'workflow',
            'executions',
            action.verb,
            action.executionId
          )
        : sh(
            'yukta-cli',
            'workflow',
            action.verb,
            action.sessionId,
            action.executionId
          );
    case 'restartFromNode':
      return sh(
        'yukta-cli',
        'workflow',
        'executions',
        'restart',
        action.executionId,
        action.nodeId
      );
    case 'node':
      return nodeCli(action);
    case 'signal':
      return sh(
        'yukta-cli',
        'workflow',
        'node',
        'signal',
        action.executionId,
        action.nodeId,
        '--name',
        action.name,
        '--decision',
        action.decision
      );
  }
}

function nodeCli(action: Extract<CommandAction, { kind: 'node' }>): string {
  const { verb, sessionId, executionId, nodeId } = action;
  if (verb === 'retry') {
    return sh('yukta-cli', 'workflow', 'node', 'retry', executionId, nodeId);
  }
  const step = verb.startsWith('step');
  const word = verb === 'step' ? 'next' : verb.replace('step/', '');
  return sh(
    'yukta-cli',
    'workflow',
    'node',
    ...(step ? ['step', word] : [verb]),
    sessionId,
    executionId,
    nodeId
  );
}

/** The REST path (and body, for a start) the action calls. */
function rest(action: CommandAction): { path: string; body?: string } {
  switch (action.kind) {
    case 'start':
      return {
        path: '/api/workflow/start',
        body: JSON.stringify({
          sessionId: action.sessionId,
          workflowId: action.workflowId,
          payload: action.payload ?? {},
        }),
      };
    case 'stopAll':
      return {
        path: `/api/workflow/${enc(action.sessionId)}/${enc(action.workflowId)}/stop?mode=${action.mode}`,
      };
    case 'run':
      return {
        path: `/api/executions/${enc(action.executionId)}/${action.verb}`,
      };
    case 'restartFromNode':
      return {
        path: `/api/executions/${enc(action.executionId)}/restart/${enc(action.nodeId)}`,
      };
    case 'node':
      return {
        path: `/api/executions/${enc(action.executionId)}/nodes/${enc(action.nodeId)}/${action.verb}`,
      };
    case 'signal':
      return {
        path:
          `/api/executions/${enc(action.executionId)}/nodes/${enc(action.nodeId)}/signal` +
          `?name=${enc(action.name)}&decision=${action.decision}`,
      };
  }
}

/** A `curl` command for the action against `baseUrl`, with the key left as `$YUKTA_TOKEN`. */
export function toCurl(action: CommandAction, baseUrl: string): string {
  const { path, body } = rest(action);
  const parts = [
    `curl -X POST ${shellQuote(baseUrl.replace(/\/$/, '') + path)}`,
    '-H "Authorization: Bearer $YUKTA_TOKEN"',
  ];
  if (body !== undefined) {
    parts.push("-H 'Content-Type: application/json'", `-d ${shellQuote(body)}`);
  }
  return parts.join(' ');
}

const NODE_MCP_ACTION: Record<
  Extract<CommandAction, { kind: 'node' }>['verb'],
  string
> = {
  pause: 'PAUSE',
  resume: 'RESUME',
  stop: 'STOP',
  skip: 'SKIP',
  retry: 'RETRY',
  step: 'STEP',
  'step/enable': 'STEP_ENABLE',
  'step/disable': 'STEP_DISABLE',
};

const RUN_MCP_ACTION = {
  pause: 'PAUSE',
  resume: 'RESUME',
  stop: 'STOP',
  restart: 'RESTART',
} as const;

/** The MCP tool call (name and arguments as JSON) for the action. The MCP tool has no start payload or stop mode. */
export function toMcp(action: CommandAction): string {
  const call = (name: string, args: Record<string, unknown>) =>
    JSON.stringify({ name, arguments: args }, null, 2);
  switch (action.kind) {
    case 'start':
      return call('start_workflow', {
        sessionId: action.sessionId,
        workflowId: action.workflowId,
      });
    case 'stopAll':
      return call('control_workflow', {
        sessionId: action.sessionId,
        action: 'STOP_ALL',
        workflowId: action.workflowId,
      });
    case 'run':
      return call('control_workflow', {
        sessionId: action.sessionId,
        action: RUN_MCP_ACTION[action.verb],
        executionId: action.executionId,
      });
    case 'restartFromNode':
      return call('control_workflow', {
        sessionId: action.sessionId,
        action: 'RESTART_FROM_NODE',
        executionId: action.executionId,
        fromNodeId: action.nodeId,
      });
    case 'node':
      return call('control_node', {
        sessionId: action.sessionId,
        executionId: action.executionId,
        nodeId: action.nodeId,
        action: NODE_MCP_ACTION[action.verb],
      });
    case 'signal':
      return call('signal_node', {
        sessionId: action.sessionId,
        executionId: action.executionId,
        nodeId: action.nodeId,
        signalName: action.name,
        decision: action.decision,
      });
  }
}

/** The text for one format. */
export function toCommand(
  format: CommandFormat,
  action: CommandAction,
  baseUrl: string
): string {
  if (format === 'cli') return toCli(action, baseUrl);
  if (format === 'curl') return toCurl(action, baseUrl);
  return toMcp(action);
}
