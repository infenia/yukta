// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useHeatmap } from '@/features/metrics/hooks';
import { getOpenInputs } from '@/features/overview/api';
import { CommandStrip } from '../CommandStrip';

vi.mock('@/features/metrics/hooks', () => ({
  useMetricSnapshot: () => ({ data: {} }),
  useMetricSeries: () => ({ data: [] }),
  useHeatmap: vi.fn(),
}));
vi.mock('../../api/workers', () => ({ useWorkers: () => ({ data: [] }) }));
vi.mock('../../api/scheduler', () => ({ getLimits: vi.fn(async () => ({})) }));
vi.mock('@/features/overview/api', () => ({ getOpenInputs: vi.fn() }));

const heatmap = vi.mocked(useHeatmap);
const approvals = vi.mocked(getOpenInputs);

beforeEach(() => {
  heatmap.mockReturnValue({ data: [] } as unknown as ReturnType<
    typeof useHeatmap
  >);
  approvals.mockResolvedValue([]);
});

describe('CommandStrip success rate tone', () => {
  it('shows a danger tone when fewer than 90% of the day’s runs succeeded', () => {
    heatmap.mockReturnValue({
      data: [{ runs: 10, failed: 5 }],
    } as unknown as ReturnType<typeof useHeatmap>);
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getByTestId('command-strip')).toBeInTheDocument();
  });

  it('shows a success tone with no runs in the day', () => {
    renderWithProviders(<CommandStrip scope="global" live />);
    expect(screen.getByTestId('command-strip')).toBeInTheDocument();
  });
});

describe('CommandStrip approvals tile', () => {
  const wait = (i: number) => ({
    sessionId: 's1',
    workflowId: 'wf',
    executionId: 'e',
    nodeId: `n${i}`,
    name: 'approve',
    since: 't',
  });

  it('says there are more when a full page waits', async () => {
    approvals.mockResolvedValue(Array.from({ length: 50 }, (_, i) => wait(i)));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(
      await screen.findByRole('link', { name: /Approvals waiting: 50\+\./ })
    ).toBeInTheDocument();
  });

  it('shows a dash when the approvals cannot be read', async () => {
    approvals.mockRejectedValue(new Error('down'));
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(
      await screen.findByRole('link', { name: /Approvals waiting: –\./ })
    ).toBeInTheDocument();
  });
});
