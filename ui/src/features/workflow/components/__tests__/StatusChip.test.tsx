// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusChip } from '../StatusChip';

describe('StatusChip', () => {
  it('renders the raw status uppercased', () => {
    render(<StatusChip status="RUNNING" />);
    expect(screen.getByTestId('status-chip')).toHaveTextContent('RUNNING');
    expect(screen.getByTestId('status-chip')).toHaveClass('border-primary');
  });

  it('renders success statuses with the tertiary color', () => {
    render(<StatusChip status="COMPLETED" />);
    expect(screen.getByTestId('status-chip')).toHaveClass('border-tertiary');
  });

  it('renders failure statuses with the error color', () => {
    render(<StatusChip status="FAILED" />);
    expect(screen.getByTestId('status-chip')).toHaveClass('border-error');
  });

  it('humanizes underscored statuses', () => {
    render(<StatusChip status="WORKFLOW_STOPPED" />);
    expect(screen.getByTestId('status-chip')).toHaveTextContent('WORKFLOW STOPPED');
    expect(screen.getByTestId('status-chip')).toHaveClass('border-outline');
  });

  it('renders NOT RUN for a null status', () => {
    render(<StatusChip status={null} />);
    expect(screen.getByTestId('status-chip')).toHaveTextContent('NOT RUN');
  });
});
