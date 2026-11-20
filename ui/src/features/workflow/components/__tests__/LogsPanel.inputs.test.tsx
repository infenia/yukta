// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LogsPanel } from '../LogsPanel';

describe('LogsPanel Inputs tab', () => {
  afterEach(() => localStorage.clear());

  it('adds an Inputs tab with the count of requests that wait', async () => {
    render(
      <LogsPanel
        entries={[]}
        isStreaming={false}
        inputs={{ waiting: 2, content: <p>inputs here</p> }}
      />
    );
    const tab = screen.getByRole('tab', { name: /Inputs/ });
    expect(tab).toHaveTextContent('2 waiting');
    expect(screen.queryByText('inputs here')).not.toBeInTheDocument();

    await userEvent.click(tab);

    expect(screen.getByText('inputs here')).toBeInTheDocument();
    expect(screen.queryByTestId('logs-body')).not.toBeInTheDocument();
  });

  it('has no tab without inputs', () => {
    render(<LogsPanel entries={[]} isStreaming={false} />);
    expect(screen.queryByRole('tab', { name: /Inputs/ })).toBeNull();
  });
});
