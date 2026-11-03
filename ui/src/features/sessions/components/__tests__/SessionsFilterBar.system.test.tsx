// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SessionsFilterBar } from '../SessionsFilterBar';
import { filterSessions } from '../SessionsFilterBar.helpers';
import { createMockSession } from '@/test/factories/sessionFactory';

const NONE = { tags: [], initiators: [], lastRuns: [], system: false };
const sessions = [
  createMockSession({ sessionId: 'a' }),
  createMockSession({ sessionId: 'sys', tags: ['yukta.internal'] }),
];

describe('system sessions', () => {
  it('are left out of the list unless asked for', () => {
    expect(filterSessions(sessions, NONE).map((s) => s.sessionId)).toEqual([
      'a',
    ]);
    expect(
      filterSessions(sessions, { ...NONE, system: true }).map(
        (s) => s.sessionId
      )
    ).toEqual(['a', 'sys']);
  });

  it('the System button turns them on', async () => {
    const onFiltersChange = vi.fn();
    render(
      <SessionsFilterBar
        data={sessions}
        filters={NONE}
        onFiltersChange={onFiltersChange}
        globalFilter=""
        onGlobalFilterChange={() => {}}
        onReset={() => {}}
      />
    );
    await userEvent.click(screen.getByLabelText('Show system sessions'));
    expect(onFiltersChange).toHaveBeenCalledWith({ ...NONE, system: true });
  });
});
