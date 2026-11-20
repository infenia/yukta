// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flowLayout } from '../../lib/flowLayout';
import { TemplateFlow } from '../TemplateFlow';

const flow = flowLayout(
  ['a', 'b', 'c'].map((nodeId) => ({ nodeId, type: 'MAPPER' })),
  [
    { source: 'a', target: 'b' },
    { source: 'b', target: 'c' },
  ]
);

const live = (c: HTMLElement) => c.querySelectorAll('.flow-edge-live').length;
const running = (c: HTMLElement) => c.querySelectorAll('.ring-4').length;

describe('TemplateFlow', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('draws the flow still while not playing', () => {
    const { container } = render(
      <TemplateFlow flow={flow} categories={{}} playing={false} />
    );
    expect(
      container.querySelectorAll('.material-symbols-outlined')
    ).toHaveLength(3);
    expect(live(container)).toBe(0);
    expect(running(container)).toBe(0);
  });

  it('replays a run wave by wave while playing, then starts over', () => {
    const { container } = render(
      <TemplateFlow flow={flow} categories={{}} playing />
    );
    // wave 0 runs first; no edge has been travelled yet
    expect(running(container)).toBe(1);
    expect(live(container)).toBe(0);
    act(() => void vi.advanceTimersByTime(520));
    expect(running(container)).toBe(1);
    expect(live(container)).toBe(1);
    act(() => void vi.advanceTimersByTime(520 * 2));
    // everything finished: nothing runs, the beat before the loop
    expect(running(container)).toBe(0);
    act(() => void vi.advanceTimersByTime(520));
    expect(running(container)).toBe(1);
  });

  it('stops and resets when playing ends', () => {
    const { container, rerender } = render(
      <TemplateFlow flow={flow} categories={{}} playing />
    );
    act(() => void vi.advanceTimersByTime(520));
    rerender(<TemplateFlow flow={flow} categories={{}} playing={false} />);
    expect(running(container)).toBe(0);
    expect(live(container)).toBe(0);
  });

  it('names each node when asked to', () => {
    const { getAllByText } = render(
      <TemplateFlow flow={flow} categories={{}} playing={false} labels />
    );
    expect(getAllByText('Mapper')).toHaveLength(3);
  });

  it('does not animate for reduced motion', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: true }));
    const { container } = render(
      <TemplateFlow flow={flow} categories={{}} playing />
    );
    act(() => void vi.advanceTimersByTime(1500));
    expect(running(container)).toBe(0);
  });

  it('draws a node the server has switched off dashed, and keeps it out of the run', () => {
    const { container } = render(
      <TemplateFlow
        flow={flow}
        categories={{}}
        playing
        off={new Set(['MAPPER'])}
      />
    );
    // every node of the sample is a MAPPER: all dashed, none lit
    expect(container.querySelectorAll('.border-dashed')).toHaveLength(3);
    expect(running(container)).toBe(0);
  });
});
