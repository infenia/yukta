// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ArcGauge, Donut, MeterBar, RingProgress } from '../Gauges';
import { KpiTile } from '../KpiTile';
import { sparkPoints } from '../tones';
import { Knob } from '../Knob';
import { ChartAnnotations, HeatStrip, StatusMatrix } from '../Strips';

describe('meters', () => {
  it.each([
    ['ArcGauge', <ArcGauge key="a" label="Load" value={5} max={10} />],
    ['RingProgress', <RingProgress key="r" label="Load" value={5} max={10} />],
    ['MeterBar', <MeterBar key="m" label="Load" value={5} max={10} />],
  ])('%s is a meter with its range', (_, node) => {
    render(node);
    const meter = screen.getByRole('meter', { name: 'Load' });
    expect(meter).toHaveAttribute('aria-valuenow', '5');
    expect(meter).toHaveAttribute('aria-valuemax', '10');
  });

  it('clamps the bar to its range', () => {
    render(<MeterBar label="Load" value={50} max={10} />);
    expect(screen.getByRole('meter').firstElementChild).toHaveStyle({
      width: '100%',
    });
  });

  it('Donut names every segment', () => {
    render(
      <Donut
        segments={[
          { label: 'Applied', value: 2, tone: 'success' },
          { label: 'Rejected', value: 1, tone: 'danger' },
        ]}
      />
    );
    expect(screen.getByRole('img')).toHaveAccessibleName(
      'Applied 2, Rejected 1'
    );
  });
});

describe('KpiTile', () => {
  it('shows value and delta, and a sparkline only with a series', () => {
    const { container, rerender } = render(
      <KpiTile label="Runs" value="42" delta="+5%" />
    );
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('+5%')).toBeInTheDocument();
    expect(container.querySelector('polyline')).toBeNull();
    rerender(<KpiTile label="Runs" value="42" series={[1, 2, 3]} />);
    expect(container.querySelector('polyline')).not.toBeNull();
  });

  it('scales points into the box; flat and empty series do not break', () => {
    expect(sparkPoints([0, 10], 100, 20)).toBe('0.0,20.0 100.0,0.0');
    expect(sparkPoints([5, 5, 5])).toBe('0.0,24.0 50.0,24.0 100.0,24.0');
    expect(sparkPoints([])).toBe('');
  });
});

describe('Knob', () => {
  const setup = (value = 5) => {
    const onChange = vi.fn();
    const onCommit = vi.fn();
    render(
      <Knob
        label="Limit"
        value={value}
        min={0}
        max={10}
        onChange={onChange}
        onCommit={onCommit}
      />
    );
    return {
      onChange,
      onCommit,
      knob: screen.getByRole('slider', { name: 'Limit' }),
    };
  };

  it('exposes its value to assistive tech', () => {
    const { knob } = setup();
    expect(knob).toHaveAttribute('aria-valuenow', '5');
    expect(knob).toHaveAttribute('aria-valuemin', '0');
    expect(knob).toHaveAttribute('aria-valuemax', '10');
  });

  it.each([
    ['{ArrowUp}', 6],
    ['{ArrowDown}', 4],
    ['{PageUp}', 6],
    ['{Home}', 0],
    ['{End}', 10],
  ])('%s sets %i and commits', async (key, expected) => {
    const { knob, onChange, onCommit } = setup();
    knob.focus();
    await userEvent.keyboard(key);
    expect(onChange).toHaveBeenCalledWith(expected);
    expect(onCommit).toHaveBeenCalledWith(expected);
  });

  it('stays inside its range', async () => {
    const { knob, onChange } = setup(10);
    knob.focus();
    await userEvent.keyboard('{ArrowUp}');
    expect(onChange).toHaveBeenCalledWith(10);
  });
});

describe('strips', () => {
  it('HeatStrip draws one cell per interval', () => {
    render(
      <HeatStrip
        label="Runs"
        columns={2}
        cells={[
          { label: 'a', volume: 1 },
          { label: 'b', volume: 0 },
        ]}
      />
    );
    expect(screen.getByRole('img', { name: 'Runs' }).children).toHaveLength(2);
  });

  it('StatusMatrix says each state in text, not only colour', () => {
    render(
      <StatusMatrix items={[{ id: 'a', label: 'node-a', state: 'stale' }]} />
    );
    expect(screen.getByText('stale')).toBeInTheDocument();
  });

  it('ChartAnnotations places markers in range and reports a click', async () => {
    const onSelect = vi.fn();
    render(
      <ChartAnnotations
        from={0}
        to={100}
        onSelect={onSelect}
        items={[
          { at: 25, label: 'Paused' },
          { at: 500, label: 'Outside' },
        ]}
      />
    );
    const marker = screen.getByRole('button', { name: 'Paused' });
    expect(marker).toHaveStyle({ left: '25%' });
    expect(screen.queryByRole('button', { name: 'Outside' })).toBeNull();
    await userEvent.click(marker);
    expect(onSelect).toHaveBeenCalledOnce();
  });
});
