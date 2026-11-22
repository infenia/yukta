// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HistoryView } from '../components/HistoryView';
import { archiveChat, saveChat } from '../lib/history';

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

describe('HistoryView', () => {
  it('says when there is nothing yet', () => {
    render(<HistoryView onOpen={vi.fn()} />);

    expect(screen.getByText('No earlier chats')).toBeInTheDocument();
  });

  it('lists the kept chats, opens one and deletes one', async () => {
    saveChat([
      { role: 'user', text: 'Why did it fail?' },
      { role: 'assistant', text: 'Because.', calls: [] },
    ]);
    archiveChat();
    const onOpen = vi.fn();
    render(<HistoryView onOpen={onOpen} />);

    await userEvent.click(
      screen.getByRole('button', { name: 'Open Why did it fail?' })
    );
    expect(onOpen).toHaveBeenCalledWith(expect.any(String));

    await userEvent.click(
      screen.getByRole('button', { name: 'Delete Why did it fail?' })
    );
    expect(screen.getByText('No earlier chats')).toBeInTheDocument();
  });
});
