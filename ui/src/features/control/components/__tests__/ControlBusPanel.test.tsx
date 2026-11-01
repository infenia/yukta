// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { getHeartbeats } from '../../api/controlBus';
import { ControlBusPanel } from '../ControlBusPanel';

vi.mock('../../api/controlBus', () => ({ getHeartbeats: vi.fn() }));
vi.mock('../SendCommandDialog', () => ({ SendCommandDialog: () => null }));

const mocked = vi.mocked(getHeartbeats);

describe('ControlBusPanel', () => {
  beforeEach(() => mocked.mockReset());

  it('says so when no node is active', async () => {
    mocked.mockResolvedValue([]);
    renderWithProviders(<ControlBusPanel />);
    expect(await screen.findByText('No active nodes.')).toBeInTheDocument();
  });

  it('shows the failure and retries the request', async () => {
    mocked.mockRejectedValueOnce(new Error('down'));
    mocked.mockResolvedValue([]);
    renderWithProviders(<ControlBusPanel />);
    expect(
      await screen.findByText('Failed to load nodes')
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /retry/i }));
    await waitFor(() =>
      expect(screen.getByText('No active nodes.')).toBeInTheDocument()
    );
  });
});
