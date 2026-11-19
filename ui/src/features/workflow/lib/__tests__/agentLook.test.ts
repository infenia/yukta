// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import { capabilityLook, modeLook, stateLook } from '../agentLook';

describe('capabilityLook', () => {
  it('names a known capability in plain words', () => {
    expect(capabilityLook('steer')).toMatchObject({
      icon: 'edit',
      label: 'Steer',
    });
  });

  it('falls back to the raw name for an unknown capability', () => {
    expect(capabilityLook('teleport')).toEqual({
      icon: 'smart_toy',
      label: 'teleport',
      hint: 'teleport',
    });
  });
});

describe('modeLook', () => {
  it('calls a PTY session a terminal', () => {
    expect(modeLook('PTY').label).toBe('Terminal');
  });

  it('calls an ACP conversation a chat over ACP', () => {
    expect(modeLook('ACP').hint).toContain('ACP protocol');
  });

  it('calls a JSONL conversation a chat over JSONL', () => {
    expect(modeLook('JSONL')).toMatchObject({
      label: 'Chat',
      hint: 'A message conversation (JSONL protocol)',
    });
  });
});

describe('stateLook', () => {
  it('shows a starting agent as muted and not live', () => {
    const look = stateLook({ state: 'STARTING', outcome: undefined });
    expect(look.icon).toBe('hourglass_empty');
    expect(look.live).toBeUndefined();
    expect(look.needsYou).toBeUndefined();
  });

  it('shows an idle agent as a paused muted state', () => {
    const look = stateLook({ state: 'IDLE', outcome: undefined });
    expect(look.icon).toBe('pause');
    expect(look.text).toBe('text-muted-foreground');
  });

  it('shows an ended agent by its outcome', () => {
    expect(stateLook({ state: 'ENDED', outcome: 'failure' }).icon).toBe(
      'error'
    );
    expect(stateLook({ state: 'ENDED', outcome: 'incomplete' }).icon).toBe(
      'warning'
    );
    expect(stateLook({ state: 'ENDED', outcome: 'success' }).icon).toBe(
      'check_circle'
    );
  });
});
