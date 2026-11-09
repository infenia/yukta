// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StaleNotice } from '../StaleNotice';

const query = (over: Partial<Parameters<typeof StaleNotice>[0]['query']>) => ({
  error: null,
  data: undefined,
  dataUpdatedAt: Date.now() - 2 * 60_000,
  refetch: vi.fn(),
  ...over,
});

describe('StaleNotice', () => {
  it('says what is shown is old and retries, when a refresh failed but data is kept', async () => {
    const kept = query({ error: new Error('down'), data: [] });
    render(<StaleNotice query={kept} />);
    expect(
      screen.getByText(/Showing what was there 2 min/)
    ).toBeInTheDocument();
    expect(
      screen.getByText('The server is not answering; this may be out of date.')
    ).toBeInTheDocument();
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: /Retry/ }));
    expect(kept.refetch).toHaveBeenCalled();
  });

  it('shows nothing without an error, or without data to keep', () => {
    const { container, rerender } = render(
      <StaleNotice query={query({ data: [] })} />
    );
    expect(container).toBeEmptyDOMElement();
    rerender(<StaleNotice query={query({ error: new Error('down') })} />);
    expect(container).toBeEmptyDOMElement();
  });
});
