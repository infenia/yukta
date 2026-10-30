// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import {
  isQueuedExecStatus,
  isAbortedExecStatus,
  isTerminalExecStatus,
  toExecStatusCategory,
  toNodeStatusLabel,
  toDisplayExecStatus,
  toNodeUiStatus,
  toStatusDisplay,
} from '../statusMapper';
import type { NodeUiStatus } from '../../types/workflow';

describe('toNodeUiStatus', () => {
  it.each([
    ['SUCCESS', 'completed'],
    ['COMPLETED', 'completed'],
    ['RUNNING', 'running'],
    ['NODE_RESUMED', 'running'],
    ['NODE_STEPPED', 'running'],
    ['NODE_UNSKIPPED', 'running'],
    ['FAILURE', 'failed'],
    ['FAILED', 'failed'],
    ['ERROR', 'failed'],
    ['PAUSED', 'paused'],
    ['NODE_PAUSED', 'paused'],
    ['NODE_SKIPPED', 'skipped'],
    ['NODE_STOPPED', 'skipped'],
    ['PENDING', 'pending'],
  ])('maps %s to %s', (raw, expected) => {
    expect(toNodeUiStatus(raw)).toBe(expected);
  });

  it('falls back to pending for unknown statuses', () => {
    expect(toNodeUiStatus('STEP_MODE_ENABLED')).toBe('pending');
  });

  it('falls back to pending for null and undefined', () => {
    expect(toNodeUiStatus(null)).toBe('pending');
    expect(toNodeUiStatus(undefined)).toBe('pending');
    expect(toNodeUiStatus('')).toBe('pending');
  });
});

describe('toExecStatusCategory', () => {
  it.each([
    ['RUNNING', 'running'],
    ['NODE_RESUMED', 'running'],
    ['NODE_STEPPED', 'running'],
    ['COMPLETED', 'success'],
    ['SUCCESS', 'success'],
    ['FAILED', 'failure'],
    ['FAILURE', 'failure'],
    ['ERROR', 'failure'],
    ['CANCELLED', 'stopped'],
    ['WORKFLOW_STOPPED', 'stopped'],
    ['NODE_STOPPED', 'running'],
    ['PAUSED', 'paused'],
    ['NODE_PAUSED', 'paused'],
    ['PENDING', 'pending'],
  ])('maps %s to %s', (raw, expected) => {
    expect(toExecStatusCategory(raw)).toBe(expected);
  });

  it('falls back to unknown for unmapped and missing statuses', () => {
    expect(toExecStatusCategory('STEP_MODE_DISABLED')).toBe('unknown');
    expect(toExecStatusCategory(null)).toBe('unknown');
    expect(toExecStatusCategory(undefined)).toBe('unknown');
    expect(toExecStatusCategory('')).toBe('unknown');
  });
});

describe('isTerminalExecStatus', () => {
  it.each([
    'COMPLETED',
    'FAILED',
    'CANCELLED',
    'WORKFLOW_STOPPED',
    'SUCCESS',
    'FAILURE',
    'ERROR',
  ])('treats %s as terminal', (raw) => {
    expect(isTerminalExecStatus(raw)).toBe(true);
  });

  it('treats non-terminal and missing statuses as not terminal', () => {
    expect(isTerminalExecStatus('RUNNING')).toBe(false);
    expect(isTerminalExecStatus('PAUSED')).toBe(false);
    expect(isTerminalExecStatus('NODE_STOPPED')).toBe(false);
    expect(isTerminalExecStatus(null)).toBe(false);
    expect(isTerminalExecStatus(undefined)).toBe(false);
  });
});

describe('status labels', () => {
  const cases: Array<[NodeUiStatus, string]> = [
    ['completed', 'Succeeded'],
    ['running', 'Running'],
    ['pending', 'Waiting'],
    ['failed', 'Failed'],
    ['paused', 'Paused'],
    ['skipped', 'Skipped'],
  ];

  it.each(cases)('labels %s as %s', (status, label) => {
    expect(toNodeStatusLabel(status)).toBe(label);
  });
});

describe('toStatusDisplay', () => {
  it.each([
    ['PENDING', 'Waiting', 'muted'],
    ['QUEUED', 'Queued', 'muted'],
    ['RUNNING', 'Running', 'primary'],
    ['NODE_PAUSED', 'Paused', 'attention'],
    ['STOPPING', 'Stopping', 'attention'],
    ['SUCCESS', 'Succeeded', 'success'],
    ['COMPLETED', 'Succeeded', 'success'],
    ['ERROR', 'Failed', 'destructive'],
    ['TIMED_OUT', 'Timed out', 'destructive'],
    ['ABORTED', 'Interrupted', 'destructive'],
    ['CANCELLED', 'Stopped', 'muted'],
    ['UPSTREAM_FAILED', 'Not reached', 'muted'],
  ])('shows %s as %s (%s)', (raw, label, tone) => {
    expect(toStatusDisplay(raw)).toMatchObject({ label, tone });
  });

  it('shows Not run without a status', () => {
    expect(toStatusDisplay(null).label).toBe('Not run');
  });

  it('reads a stopped node as a running run, but a stopped node as stopped', () => {
    expect(toStatusDisplay('NODE_STOPPED').label).toBe('Running');
    expect(toStatusDisplay('NODE_STOPPED', 'node').label).toBe('Stopped');
  });

  it('humanizes an unknown value', () => {
    expect(toStatusDisplay('WAITING_FOR_GODOT').label).toBe(
      'Waiting for godot'
    );
  });

  it('shows a live dot (no icon) only while work is happening', () => {
    expect(toStatusDisplay('RUNNING').icon).toBeNull();
    expect(toStatusDisplay('STOPPING').icon).toBeNull();
    expect(toStatusDisplay('SUCCESS').icon).toBe('check_circle');
  });
});

describe('toDisplayExecStatus', () => {
  it('shows a pause control state over a running status', () => {
    expect(toDisplayExecStatus('RUNNING', 'NODE_PAUSED')).toBe('NODE_PAUSED');
    expect(toDisplayExecStatus('RUNNING', 'PAUSED')).toBe('PAUSED');
  });

  it('shows STOPPING while a stop drains, as a stopped bucket', () => {
    expect(toDisplayExecStatus('RUNNING', 'STOPPING')).toBe('STOPPING');
    expect(toExecStatusCategory('STOPPING')).toBe('stopped');
    expect(toDisplayExecStatus('STOPPED', 'STOPPING')).toBe('STOPPED');
  });

  it('never overrides a terminal status', () => {
    expect(toDisplayExecStatus('STOPPED', 'PAUSED')).toBe('STOPPED');
  });

  it('ignores non-pause control states and falls back to null', () => {
    expect(toDisplayExecStatus('RUNNING', 'RESUMED')).toBe('RUNNING');
    expect(toDisplayExecStatus(undefined, null)).toBeNull();
  });
});

describe('isQueuedExecStatus', () => {
  it('is true only for QUEUED', () => {
    expect(isQueuedExecStatus('QUEUED')).toBe(true);
    expect(isQueuedExecStatus('PENDING')).toBe(false);
    expect(isQueuedExecStatus(null)).toBe(false);
  });
});

describe('isAbortedExecStatus', () => {
  it('is true only for ABORTED', () => {
    expect(isAbortedExecStatus('ABORTED')).toBe(true);
    expect(isAbortedExecStatus('FAILURE')).toBe(false);
    expect(isAbortedExecStatus(null)).toBe(false);
  });
});
