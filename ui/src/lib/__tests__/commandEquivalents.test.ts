// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it } from 'vitest';
import { setApiKey } from '../apiKey';
import {
  shellQuote,
  toCli,
  toCommand,
  toCurl,
  toMcp,
  type CommandAction,
} from '../commandEquivalents';

const BASE = 'http://localhost:9858';
const S = 's1';
const E = 'e1';
const N = 'n1';
const AUTH = '-H "Authorization: Bearer $YUKTA_TOKEN"';

interface Case {
  name: string;
  action: CommandAction;
  cli: string;
  curl: string;
  mcp: object;
}

const nodeCase = (
  verb: Extract<CommandAction, { kind: 'node' }>['verb'],
  cli: string,
  mcpAction: string
): Case => ({
  name: `node ${verb}`,
  action: { kind: 'node', verb, sessionId: S, executionId: E, nodeId: N },
  cli,
  curl: `curl -X POST ${BASE}/api/executions/e1/nodes/n1/${verb} ${AUTH}`,
  mcp: {
    name: 'control_node',
    arguments: { sessionId: S, executionId: E, nodeId: N, action: mcpAction },
  },
});

const runCase = (
  verb: 'pause' | 'resume' | 'stop' | 'restart',
  cli: string
): Case => ({
  name: `run ${verb}`,
  action: { kind: 'run', verb, sessionId: S, executionId: E },
  cli,
  curl: `curl -X POST ${BASE}/api/executions/e1/${verb} ${AUTH}`,
  mcp: {
    name: 'control_workflow',
    arguments: { sessionId: S, action: verb.toUpperCase(), executionId: E },
  },
});

const CASES: Case[] = [
  {
    name: 'start without a payload',
    action: { kind: 'start', sessionId: S, workflowId: 'wf' },
    cli: 'yukta-cli workflow start s1 wf',
    curl: `curl -X POST ${BASE}/api/workflow/start ${AUTH} -H 'Content-Type: application/json' -d '{"sessionId":"s1","workflowId":"wf","payload":{}}'`,
    mcp: {
      name: 'start_workflow',
      arguments: { sessionId: S, workflowId: 'wf' },
    },
  },
  {
    name: 'start with an empty payload',
    action: { kind: 'start', sessionId: S, workflowId: 'wf', payload: {} },
    cli: 'yukta-cli workflow start s1 wf',
    curl: `curl -X POST ${BASE}/api/workflow/start ${AUTH} -H 'Content-Type: application/json' -d '{"sessionId":"s1","workflowId":"wf","payload":{}}'`,
    mcp: {
      name: 'start_workflow',
      arguments: { sessionId: S, workflowId: 'wf' },
    },
  },
  {
    name: 'start with a payload that needs quoting',
    action: {
      kind: 'start',
      sessionId: S,
      workflowId: 'wf',
      payload: { note: "it's" },
    },
    cli: `yukta-cli workflow start s1 wf --payload '{"note":"it'\\''s"}'`,
    curl: `curl -X POST ${BASE}/api/workflow/start ${AUTH} -H 'Content-Type: application/json' -d '{"sessionId":"s1","workflowId":"wf","payload":{"note":"it'\\''s"}}'`,
    mcp: {
      name: 'start_workflow',
      arguments: { sessionId: S, workflowId: 'wf' },
    },
  },
  {
    name: 'stop all',
    action: { kind: 'stopAll', sessionId: S, workflowId: 'wf', mode: 'KILL' },
    cli: 'yukta-cli workflow stop s1 wf --mode KILL',
    curl: `curl -X POST '${BASE}/api/workflow/s1/wf/stop?mode=KILL' ${AUTH}`,
    mcp: {
      name: 'control_workflow',
      arguments: { sessionId: S, action: 'STOP_ALL', workflowId: 'wf' },
    },
  },
  runCase('pause', 'yukta-cli workflow pause s1 e1'),
  runCase('resume', 'yukta-cli workflow resume s1 e1'),
  runCase('stop', 'yukta-cli workflow executions stop e1'),
  runCase('restart', 'yukta-cli workflow executions restart e1'),
  {
    name: 'restart from a node',
    action: {
      kind: 'restartFromNode',
      sessionId: S,
      executionId: E,
      nodeId: N,
    },
    cli: 'yukta-cli workflow executions restart e1 n1',
    curl: `curl -X POST ${BASE}/api/executions/e1/restart/n1 ${AUTH}`,
    mcp: {
      name: 'control_workflow',
      arguments: {
        sessionId: S,
        action: 'RESTART_FROM_NODE',
        executionId: E,
        fromNodeId: N,
      },
    },
  },
  nodeCase('pause', 'yukta-cli workflow node pause s1 e1 n1', 'PAUSE'),
  nodeCase('resume', 'yukta-cli workflow node resume s1 e1 n1', 'RESUME'),
  nodeCase('stop', 'yukta-cli workflow node stop s1 e1 n1', 'STOP'),
  nodeCase('skip', 'yukta-cli workflow node skip s1 e1 n1', 'SKIP'),
  nodeCase('retry', 'yukta-cli workflow node retry e1 n1', 'RETRY'),
  nodeCase('step', 'yukta-cli workflow node step next s1 e1 n1', 'STEP'),
  nodeCase(
    'step/enable',
    'yukta-cli workflow node step enable s1 e1 n1',
    'STEP_ENABLE'
  ),
  nodeCase(
    'step/disable',
    'yukta-cli workflow node step disable s1 e1 n1',
    'STEP_DISABLE'
  ),
  {
    name: 'signal approve',
    action: {
      kind: 'signal',
      sessionId: S,
      executionId: E,
      nodeId: N,
      name: 'manager approval',
      decision: 'approve',
    },
    cli: "yukta-cli workflow node signal e1 n1 --name 'manager approval' --decision approve",
    curl: `curl -X POST '${BASE}/api/executions/e1/nodes/n1/signal?name=manager%20approval&decision=approve' ${AUTH}`,
    mcp: {
      name: 'signal_node',
      arguments: {
        sessionId: S,
        executionId: E,
        nodeId: N,
        signalName: 'manager approval',
        decision: 'approve',
      },
    },
  },
  {
    name: 'signal reject',
    action: {
      kind: 'signal',
      sessionId: S,
      executionId: E,
      nodeId: N,
      name: 'ok',
      decision: 'reject',
    },
    cli: 'yukta-cli workflow node signal e1 n1 --name ok --decision reject',
    curl: `curl -X POST '${BASE}/api/executions/e1/nodes/n1/signal?name=ok&decision=reject' ${AUTH}`,
    mcp: {
      name: 'signal_node',
      arguments: {
        sessionId: S,
        executionId: E,
        nodeId: N,
        signalName: 'ok',
        decision: 'reject',
      },
    },
  },
  {
    name: 'create session',
    action: {
      kind: 'createSession',
      session: { sessionId: 'p', tags: { a: 'b' } },
    },
    cli: `yukta-cli session apply '{"sessionId":"p","tags":{"a":"b"}}'`,
    curl: `curl -X POST ${BASE}/api/sessions ${AUTH} -H 'Content-Type: application/json' -d '{"sessionId":"p","tags":{"a":"b"}}'`,
    mcp: {
      name: 'create_session',
      arguments: { config: { sessionId: 'p', tags: { a: 'b' } } },
    },
  },
];

afterEach(() => setApiKey(null));

describe.each(CASES)('$name', ({ action, cli, curl, mcp }) => {
  it('as CLI', () => expect(toCli(action)).toBe(cli));
  it('as curl', () => expect(toCurl(action, BASE)).toBe(curl));
  it('as MCP', () => expect(JSON.parse(toMcp(action))).toEqual(mcp));
  it('through toCommand', () => {
    expect(toCommand('cli', action, BASE)).toBe(cli);
    expect(toCommand('curl', action, BASE)).toBe(curl);
    expect(toCommand('mcp', action, BASE)).toBe(toMcp(action));
  });
});

describe('commandEquivalents', () => {
  it('never contains the API key that is set', () => {
    setApiKey('super-secret-key-123');
    for (const { action } of CASES) {
      for (const format of ['cli', 'curl', 'mcp'] as const) {
        expect(toCommand(format, action, BASE)).not.toContain('super-secret');
      }
    }
    expect(toCurl(CASES[0].action, BASE)).toContain('$YUKTA_TOKEN');
  });

  it('drops a trailing slash from the base URL and encodes ids in the path', () => {
    const action: CommandAction = {
      kind: 'run',
      verb: 'pause',
      sessionId: S,
      executionId: 'a/b c',
    };
    expect(toCurl(action, `${BASE}/`)).toContain(
      `${BASE}/api/executions/a%2Fb%20c/pause`
    );
  });

  it('points the CLI at the server it is copied from unless that is its default', () => {
    const action: CommandAction = {
      kind: 'run',
      verb: 'pause',
      sessionId: S,
      executionId: E,
    };
    expect(toCli(action, 'http://localhost:9858/')).toBe(
      'yukta-cli workflow pause s1 e1'
    );
    expect(toCommand('cli', action, 'https://yukta.example.com/')).toBe(
      'yukta-cli workflow pause s1 e1 --url https://yukta.example.com'
    );
    expect(toCli(action)).toBe('yukta-cli workflow pause s1 e1');
  });

  it('quotes only what a shell would split or expand', () => {
    expect(shellQuote('plain-id_1.2:3')).toBe('plain-id_1.2:3');
    expect(shellQuote('a b')).toBe("'a b'");
    expect(shellQuote('$HOME')).toBe("'$HOME'");
    expect(shellQuote("it's")).toBe("'it'\\''s'");
  });
});
