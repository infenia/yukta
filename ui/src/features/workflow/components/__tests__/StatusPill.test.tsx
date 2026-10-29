// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusPill } from '../StatusPill';

describe('StatusPill', () => {
  it('shows the design-system label and keeps the raw value for agents', () => {
    render(<StatusPill status="SUCCESS" />);
    const pill = screen.getByTestId('status-chip');
    expect(pill).toHaveTextContent('Succeeded');
    expect(pill).toHaveAttribute('data-status', 'SUCCESS');
    expect(pill).toHaveAttribute('title', 'SUCCESS');
    expect(pill).toHaveClass('text-success');
  });

  it('shows a live dot instead of an icon while running', () => {
    render(<StatusPill status="RUNNING" />);
    const pill = screen.getByTestId('status-chip');
    expect(pill).toHaveTextContent(/^Running$/);
    expect(pill).toHaveClass('text-primary');
    expect(pill.querySelector('.live-dot')).not.toBeNull();
  });

  it('colours failures destructive, timed-out runs included', () => {
    render(<StatusPill status="TIMED_OUT" />);
    const pill = screen.getByTestId('status-chip');
    expect(pill).toHaveTextContent('Timed out');
    expect(pill).toHaveClass('text-destructive');
  });

  it('draws never-ran nodes dashed', () => {
    render(<StatusPill status="UPSTREAM_FAILED" scope="node" />);
    const pill = screen.getByTestId('status-chip');
    expect(pill).toHaveTextContent('Not reached');
    expect(pill).toHaveClass('border-dashed');
  });

  it('shows Not run for a null status', () => {
    render(<StatusPill status={null} />);
    const pill = screen.getByTestId('status-chip');
    expect(pill).toHaveTextContent('Not run');
    expect(pill).toHaveAttribute('data-status', 'NOT_RUN');
  });
});
