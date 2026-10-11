// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import { SessionInfoPanel } from '../SessionInfoPanel';
import type { SessionDetails } from '../../types/session';

const mockSession: SessionDetails = {
  sessionId: 'sess-123',
  name: 'Test Session',
  description: 'A test session',
  initiator: 'John Doe',
  tags: ['tag1', 'tag2'],
  projectPath: '/path/to/project',
  workflowIds: ['wf1'],
};

describe('SessionInfoPanel', () => {
  it('renders session information', () => {
    render(<SessionInfoPanel session={mockSession} />);

    expect(screen.getByText('Test Session')).toBeInTheDocument();
    expect(screen.getByText('A test session')).toBeInTheDocument();
    expect(screen.getByText('John Doe')).toBeInTheDocument();
    expect(screen.getByText('tag1')).toBeInTheDocument();
    expect(screen.getByText('tag2')).toBeInTheDocument();
  });

  it('displays correct initials from initiator name', () => {
    render(<SessionInfoPanel session={mockSession} />);
    expect(screen.getByText('JD')).toBeInTheDocument();
  });

  it('copies project path to clipboard on button click', async () => {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, {
      clipboard: {
        writeText: writeTextMock,
      },
    });

    render(<SessionInfoPanel session={mockSession} />);

    const copyButton = screen.getByRole('button');
    fireEvent.click(copyButton);

    await waitFor(() => {
      expect(writeTextMock).toHaveBeenCalledWith('/path/to/project');
    });
  });

  it('handles single-word initiator name', () => {
    const singleWordSession = { ...mockSession, initiator: 'User' };
    render(<SessionInfoPanel session={singleWordSession} />);
    expect(screen.getByText('US')).toBeInTheDocument();
  });

  it('handles initiator with three characters', () => {
    const threeCharSession = { ...mockSession, initiator: 'ABC' };
    render(<SessionInfoPanel session={threeCharSession} />);
    expect(screen.getByText('AB')).toBeInTheDocument();
  });

  it('handles initiator with extra whitespace', () => {
    const whitespacSession = { ...mockSession, initiator: '  John   Doe  ' };
    render(<SessionInfoPanel session={whitespacSession} />);
    expect(screen.getByText('JD')).toBeInTheDocument();
  });

  it('renders empty tags list', () => {
    const noTagsSession = { ...mockSession, tags: [] };
    render(<SessionInfoPanel session={noTagsSession} />);
    expect(screen.getByText('Tags')).toBeInTheDocument();
  });

  it('displays project path in code block', () => {
    render(<SessionInfoPanel session={mockSession} />);
    const codeBlock = screen.getByText('/path/to/project');
    expect(codeBlock.tagName).toBe('CODE');
  });

  it('logs and keeps going when the clipboard is not available', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    Object.assign(navigator, {
      clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
    });

    render(<SessionInfoPanel session={mockSession} />);
    fireEvent.click(screen.getByRole('button'));

    await waitFor(() => {
      expect(error).toHaveBeenCalledWith('Failed to copy to clipboard:', expect.any(Error));
    });
    error.mockRestore();
  });

  it('resets the copied state after two seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      Object.assign(navigator, {
        clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
      });
      render(<SessionInfoPanel session={mockSession} />);
      const button = screen.getByRole('button');

      fireEvent.click(button);
      await waitFor(() => {
        expect(navigator.clipboard.writeText).toHaveBeenCalled();
      });
      await act(async () => {
        vi.advanceTimersByTime(2000);
      });

      expect(button).toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });
});
