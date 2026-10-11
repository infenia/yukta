// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SessionListPage } from '../SessionListPage';
import * as useSessionSummariesModule from '../../hooks/useSessionSummaries';
import { createMockSessions } from '@/test/factories/sessionFactory';
import { renderWithProviders } from '@/test/utils/testUtils';

const navigate = vi.fn();

vi.mock('react-router', async () => {
  const actual = await vi.importActual('react-router');
  return { ...actual, useNavigate: () => navigate };
});

describe('SessionListPage row navigation', () => {
  const sessions = createMockSessions(2);

  beforeEach(() => {
    navigate.mockClear();
    vi.spyOn(useSessionSummariesModule, 'useSessionSummaries').mockReturnValue({
      data: sessions,
      isLoading: false,
      error: null,
    } as unknown as ReturnType<typeof useSessionSummariesModule.useSessionSummaries>);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  function firstRow() {
    return screen.getByText(sessions[0].name).closest('tr') as HTMLElement;
  }

  it('opens the session on click', async () => {
    renderWithProviders(<SessionListPage />);
    await userEvent.click(firstRow());
    expect(navigate).toHaveBeenCalledWith(`/sessions/${sessions[0].sessionId}`);
  });

  it('opens the session with Enter and Space but ignores other keys', async () => {
    renderWithProviders(<SessionListPage />);
    const row = firstRow();
    row.focus();

    await userEvent.keyboard('{Enter}');
    await userEvent.keyboard(' ');
    expect(navigate).toHaveBeenCalledTimes(2);

    await userEvent.keyboard('a');
    expect(navigate).toHaveBeenCalledTimes(2);
  });

  it('does not open the session when the actions cell is clicked', () => {
    renderWithProviders(<SessionListPage />);
    const actionsCell = firstRow().querySelector('td:last-child > div') as HTMLElement;

    fireEvent.click(actionsCell);

    expect(navigate).not.toHaveBeenCalled();
  });
});
