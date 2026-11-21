// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PluginSummary } from '@/features/plugins/types';
import {
  STEP_CATEGORIES,
  groupSteps,
  readRecent,
  rememberRecent,
} from '../StepPicker.helpers';

const plugins: PluginSummary[] = [
  { type: 'API_TRIGGER', category: 'TRIGGER', description: 'Starts a run' },
  {
    type: 'HTTP_REQUEST',
    category: 'PROCESSOR',
    description: 'Calls a web service',
  },
  { type: 'BRANCH', category: 'PROCESSOR', description: null },
  { type: 'CONSOLE_TERMINAL', category: 'TERMINAL' },
];

describe('groupSteps', () => {
  it('groups by category in the allowed order', () => {
    const groups = groupSteps(plugins, STEP_CATEGORIES, '', []);
    expect(groups.map((g) => g.title)).toEqual([
      'Triggers',
      'Processors',
      'Terminals',
    ]);
    expect(groups[1].plugins.map((p) => p.type)).toEqual([
      'HTTP_REQUEST',
      'BRANCH',
    ]);
  });

  it('lists only the allowed categories, leaving out an empty group', () => {
    const groups = groupSteps(plugins, ['TERMINAL', 'PROCESSOR'], '', []);
    expect(groups.map((g) => g.title)).toEqual(['Terminals', 'Processors']);
    expect(groupSteps([], STEP_CATEGORIES, '', [])).toEqual([]);
  });

  it('puts the recent steps first, newest first, skipping any that is not listed', () => {
    const groups = groupSteps(plugins, ['PROCESSOR'], '', [
      'BRANCH',
      'GONE',
      'API_TRIGGER',
      'HTTP_REQUEST',
    ]);
    expect(groups[0].title).toBe('Recent');
    expect(groups[0].plugins.map((p) => p.type)).toEqual([
      'BRANCH',
      'HTTP_REQUEST',
    ]);
    expect(groups).toHaveLength(2);
  });

  it('shows no Recent group when none of them is listed', () => {
    expect(groupSteps(plugins, STEP_CATEGORIES, '', ['GONE'])[0].title).toBe(
      'Triggers'
    );
  });

  it('matches every word of the query against the type, the name and the description', () => {
    const types = (query: string) =>
      groupSteps(plugins, STEP_CATEGORIES, query, [])
        .flatMap((g) => g.plugins)
        .map((p) => p.type);
    expect(types('http')).toEqual(['HTTP_REQUEST']);
    expect(types('Web  SERVICE')).toEqual(['HTTP_REQUEST']);
    expect(types('console terminal')).toEqual(['CONSOLE_TERMINAL']);
    expect(types('web branch')).toEqual([]);
    expect(types('   ')).toHaveLength(4);
  });

  it('hides Recent while a query is typed', () => {
    const groups = groupSteps(plugins, STEP_CATEGORIES, 'branch', ['BRANCH']);
    expect(groups.map((g) => g.title)).toEqual(['Processors']);
  });
});

describe('recent steps', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('remembers the newest first, without repeats, five at most', () => {
    ['A', 'B', 'C', 'D', 'E', 'F', 'C'].forEach(rememberRecent);
    expect(readRecent()).toEqual(['C', 'F', 'E', 'D', 'B']);
  });

  it('reads nothing from storage that holds something else', () => {
    expect(readRecent()).toEqual([]);
    localStorage.setItem('yukta.recentSteps', '{"a":1}');
    expect(readRecent()).toEqual([]);
    localStorage.setItem('yukta.recentSteps', '["A", 3, null, "B"]');
    expect(readRecent()).toEqual(['A', 'B']);
    localStorage.setItem('yukta.recentSteps', 'not json');
    expect(readRecent()).toEqual([]);
  });

  it('does not throw when storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(readRecent()).toEqual([]);
    expect(() => rememberRecent('A')).not.toThrow();
  });
});
