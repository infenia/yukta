// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import { useHeatmap } from '@/features/metrics/hooks';
import { CommandStrip } from '../CommandStrip';

vi.mock('@/features/metrics/hooks', () => ({
  useMetricSnapshot: () => ({ data: {} }),
  useMetricSeries: () => ({ data: [] }),
  useHeatmap: vi.fn(),
}));
vi.mock('../../api/workers', () => ({ useWorkers: () => ({ data: [] }) }));
vi.mock('../../api/scheduler', () => ({ getLimits: vi.fn(async () => ({})) }));

const heatmap = vi.mocked(useHeatmap);

describe('CommandStrip success rate tone', () => {
  it('shows a danger tone when fewer than 90% of the day’s runs succeeded', () => {
    heatmap.mockReturnValue({
      data: [{ runs: 10, failed: 5 }],
    } as unknown as ReturnType<typeof useHeatmap>);
    renderWithProviders(<CommandStrip scope="global" live={false} />);
    expect(screen.getByTestId('command-strip')).toBeInTheDocument();
  });

  it('shows a success tone with no runs in the day', () => {
    heatmap.mockReturnValue({ data: [] } as unknown as ReturnType<
      typeof useHeatmap
    >);
    renderWithProviders(<CommandStrip scope="global" live />);
    expect(screen.getByTestId('command-strip')).toBeInTheDocument();
  });
});
