// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** One rule as a person reads it: "When …" and "Then …". */
export interface RuleSentence {
  id: string;
  when: string;
  then: string;
}

type Fields = Record<string, unknown>;

const text = (value: unknown) =>
  typeof value === 'string' || typeof value === 'number' ? String(value) : '';

const EVENTS: Record<string, string> = {
  NodeStatusChanged: 'a node',
  WorkflowStatusChanged: 'the run',
  ControlOutcome: 'a command is carried out or refused',
  LogLine: 'a node logs a line',
  EventsMissed: 'events were missed',
  InputRequested: 'an input request is asked',
  InputEscalated: 'an input request is handed up',
  InputAnswered: 'an input request is answered',
  InputExpired: 'an input request expires',
  InputCancelled: 'an input request is cancelled',
};

const COMMANDS: Record<string, string> = {
  PauseWorkflow: 'pause the run',
  ResumeWorkflow: 'resume the run',
  StopWorkflow: 'stop the run',
  PauseNode: 'pause the node',
  ResumeNode: 'resume the node',
  StopNode: 'stop the node',
  Restart: 'restart the run',
  RestartFromNode: 'restart from the node',
};

const commandName = (command: unknown) =>
  text(command).replace(/Command$/, '') || 'any command';

const condition = (fields: Fields) =>
  text(fields.if) ? `, if ${text(fields.if)}` : '';

const reason = (fields: Fields) =>
  text(fields.reason) ? ` · reason “${text(fields.reason)}”` : '';

function whenEvent(when: Fields): string {
  const event = text(when.event);
  let subject =
    EVENTS[event] ?? (event ? `${event} happens` : 'anything happens');
  if (event === 'NodeStatusChanged' || event === 'WorkflowStatusChanged') {
    subject += text(when.status)
      ? ` turns ${text(when.status)}`
      : ' changes status';
  }
  const only = [
    text(when.module) && `of type ${text(when.module)}`,
    text(when.nodeId) && `only node ${text(when.nodeId)}`,
    text(when.workflowId) && `only workflow ${text(when.workflowId)}`,
  ].filter(Boolean);
  const count = Number(when.count ?? 1);
  const times =
    count > 1
      ? ` at least ${count} times within ${text(when.withinSeconds) || '60'} s`
      : '';
  return `${subject}${times}${only.length ? `, ${only.join(', ')}` : ''}${condition(when)}`;
}

function thenAction(then: Fields): string {
  if (then.answer) {
    return `answer “${text((then.answer as Fields).decision)}”`;
  }
  if (then.escalate) {
    return `hand it up${reason(then.escalate as Fields)}`;
  }
  const command = text(then.command);
  const extra = [
    text(then.mode) && `mode ${text(then.mode)}`,
    then.immediate === true && 'at once',
    text(then.from) && `from ${text(then.from)}`,
  ].filter(Boolean);
  return `${COMMANDS[command] ?? commandName(command)}${extra.length ? ` (${extra.join(', ')})` : ''}${reason(then)}`;
}

/** The rule in plain words; a rule of an unknown shape says so instead of guessing. */
export function ruleSentence(rule: Fields, index: number): RuleSentence {
  const id = text(rule.id) || `rule ${index + 1}`;
  const deny = rule.deny as Fields | undefined;
  if (deny) {
    return {
      id,
      when: `someone sends ${commandName(deny.command)}${condition(deny)}`,
      then: `refuse it${reason(deny)}`,
    };
  }
  const hold = rule.requireApproval as Fields | undefined;
  if (hold) {
    return {
      id,
      when: `someone sends ${commandName(hold.command)}${condition(hold)}`,
      then: `hold it until approved (risk ${text(hold.risk) || 'HIGH'})${reason(hold)}`,
    };
  }
  if (rule.when || rule.then) {
    return {
      id,
      when: whenEvent((rule.when ?? {}) as Fields),
      then: thenAction((rule.then ?? {}) as Fields),
    };
  }
  return { id, when: 'not a rule this page can read', then: 'see the JSON' };
}
