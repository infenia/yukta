// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import {
  NotificationsContext,
  type NotificationsState,
} from '@/features/inbox/NotificationsContext';
import { action } from '@/features/inbox/testFixtures';
import { InputsTile } from '../InputsTile';

const show = (over: Partial<NotificationsState>) => {
  const actions = over.actions ?? [];
  return render(
    <MemoryRouter>
      <NotificationsContext.Provider
        value={{
          items: actions,
          actions,
          unread: [],
          highest: null,
          connected: true,
          toasts: [],
          loaded: true,
          dismissToast: () => undefined,
          markRead: () => undefined,
          dismiss: () => undefined,
          ...over,
        }}
      >
        <InputsTile />
      </NotificationsContext.Provider>
    </MemoryRouter>
  );
};

describe('InputsTile', () => {
  it('counts the questions that wait for me and links to the inbox', () => {
    show({
      actions: [
        action({ createdAt: new Date(Date.now() - 600_000).toISOString() }),
        action({ createdAt: new Date(Date.now() - 60_000).toISOString() }),
      ],
    });

    expect(screen.getByLabelText('Inputs')).toHaveTextContent('2');
    expect(screen.getByText(/oldest 10 minutes ago/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open inbox' })).toHaveAttribute(
      'href',
      '/inbox'
    );
  });

  it('says when none wait, and shows a dash until the first answer', () => {
    const { unmount } = show({});
    expect(screen.getByText('none waiting')).toBeInTheDocument();
    unmount();

    show({ loaded: false });
    expect(screen.getByLabelText('Inputs')).toHaveTextContent('–');
  });
});
