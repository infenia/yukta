// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { ruleSentence } from '../ruleSentences';

const said = (rule: Record<string, unknown>) => {
  const { when, then } = ruleSentence(rule, 0);
  return `When ${when} Then ${then}`;
};

describe('ruleSentence', () => {
  it('reads a counted node rule with its filters', () => {
    expect(
      ruleSentence(
        {
          id: 'pause-on-failures',
          when: {
            event: 'NodeStatusChanged',
            status: 'FAILURE',
            module: 'HTTP_REQUEST',
            nodeId: 'deploy.prod',
            count: 3,
            withinSeconds: 60,
          },
          then: { command: 'PauseWorkflow', reason: 'too many failures' },
        },
        0
      )
    ).toEqual({
      id: 'pause-on-failures',
      when: 'a node turns FAILURE at least 3 times within 60 s, of type HTTP_REQUEST, only node deploy.prod',
      then: 'pause the run · reason “too many failures”',
    });
  });

  it('reads run, log and other events', () => {
    expect(
      said({
        when: { event: 'WorkflowStatusChanged', workflowId: 'w', count: 2 },
        then: { command: 'StopWorkflow', mode: 'KILL' },
      })
    ).toBe(
      'When the run changes status at least 2 times within 60 s, only workflow w Then stop the run (mode KILL)'
    );
    expect(
      said({
        when: { event: 'LogLine', if: "level == 'ERROR'" },
        then: { command: 'StopNode', immediate: true },
      })
    ).toBe(
      "When a node logs a line, if level == 'ERROR' Then stop the node (at once)"
    );
    expect(
      said({
        when: { event: 'Custom' },
        then: { command: 'RestartFromNode', from: 'metadata.restartFrom' },
      })
    ).toBe(
      'When Custom happens Then restart from the node (from metadata.restartFrom)'
    );
    expect(said({ when: {}, then: { command: 'Odd' } })).toBe(
      'When anything happens Then Odd'
    );
    expect(said({ then: {} })).toBe('When anything happens Then any command');
    expect(said({ when: {} })).toBe('When anything happens Then any command');
  });

  it('reads answers and escalations of input requests', () => {
    expect(
      said({
        when: { event: 'InputRequested' },
        then: { answer: { decision: 'approve' } },
      })
    ).toBe('When an input request is asked Then answer “approve”');
    expect(
      said({
        when: { event: 'InputEscalated' },
        then: { escalate: { reason: 'not covered' } },
      })
    ).toBe(
      'When an input request is handed up Then hand it up · reason “not covered”'
    );
  });

  it('reads deny and approval rules', () => {
    expect(
      said({
        deny: {
          command: 'StopWorkflowCommand',
          if: "mode == 'KILL'",
          reason: 'a deploy is in progress',
        },
      })
    ).toBe(
      "When someone sends StopWorkflow, if mode == 'KILL' Then refuse it · reason “a deploy is in progress”"
    );
    expect(said({ deny: {} })).toBe(
      'When someone sends any command Then refuse it'
    );
    expect(said({ requireApproval: { command: 'Restart', risk: 'LOW' } })).toBe(
      'When someone sends Restart Then hold it until approved (risk LOW)'
    );
    expect(
      said({ requireApproval: { command: 'Restart', reason: 'prod' } })
    ).toBe(
      'When someone sends Restart Then hold it until approved (risk HIGH) · reason “prod”'
    );
  });

  it('names a rule without an id by its place and says when it cannot read it', () => {
    expect(ruleSentence({}, 2)).toEqual({
      id: 'rule 3',
      when: 'not a rule this page can read',
      then: 'see the JSON',
    });
  });
});
