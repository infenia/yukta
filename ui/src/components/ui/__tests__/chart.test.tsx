// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// jsdom has no layout, so Recharts would render nothing; the frame is what is under test
vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="responsive">{children}</div>
  ),
  Tooltip: (props: { contentStyle?: { background?: string } }) => (
    <div data-testid="tooltip" data-bg={props.contentStyle?.background} />
  ),
}));

import { ChartContainer, ChartTooltip } from '../chart';

describe('ChartContainer', () => {
  it('is an image with an accessible name', () => {
    render(
      <ChartContainer
        config={{ v: { label: 'Runs', color: 'var(--chart-1)' } }}
        label="Runs per minute"
      >
        <span>chart</span>
      </ChartContainer>
    );
    expect(
      screen.getByRole('img', { name: 'Runs per minute' })
    ).toBeInTheDocument();
    expect(screen.getByTestId('responsive')).toHaveTextContent('chart');
  });

  it('exposes each series colour as a css variable', () => {
    render(
      <ChartContainer
        config={{
          runs: { label: 'Runs', color: 'var(--chart-1)' },
          failed: { label: 'Failed', color: 'var(--destructive)' },
        }}
        label="Runs"
      >
        <span>chart</span>
      </ChartContainer>
    );
    const frame = screen.getByRole('img', { name: 'Runs' });
    expect(frame.style.getPropertyValue('--color-runs')).toBe('var(--chart-1)');
    expect(frame.style.getPropertyValue('--color-failed')).toBe(
      'var(--destructive)'
    );
  });

  it('takes the class of the caller', () => {
    render(
      <ChartContainer config={{}} label="x" className="h-20">
        <span />
      </ChartContainer>
    );
    expect(screen.getByRole('img')).toHaveClass('h-20');
  });
});

describe('ChartTooltip', () => {
  it('sits on the popover tokens', () => {
    render(<ChartTooltip />);
    expect(screen.getByTestId('tooltip')).toHaveAttribute(
      'data-bg',
      'var(--popover)'
    );
  });
});
