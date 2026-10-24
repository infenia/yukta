// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StateMessage } from '../StateMessage';

describe('StateMessage', () => {
  it('shows a neutral empty state with an icon tile and body', () => {
    render(<StateMessage title="No runs yet.">Run a workflow.</StateMessage>);
    expect(screen.getByText('No runs yet.')).toHaveClass('text-on-surface');
    expect(screen.getByText('Run a workflow.')).toBeInTheDocument();
    expect(screen.getByTestId('state-icon')).toHaveTextContent('inbox');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('announces loading as a status with a spinning icon', () => {
    render(<StateMessage tone="loading" title="Loading…" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
    const glyph = screen.getByTestId('state-icon').firstElementChild;
    expect(glyph).toHaveClass('animate-spin');
  });

  it('announces errors as an alert and retries', async () => {
    const onRetry = vi.fn();
    render(<StateMessage tone="error" title="Failed" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toHaveClass('text-error');
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('takes a custom icon and a compact size', () => {
    const { container } = render(
      <StateMessage
        title="Nothing here"
        icon="search_off"
        compact
        className="border-0"
      />
    );
    expect(screen.getByTestId('state-icon')).toHaveTextContent('search_off');
    expect(container.firstElementChild).toHaveClass('py-6', 'border-0');
  });
});
