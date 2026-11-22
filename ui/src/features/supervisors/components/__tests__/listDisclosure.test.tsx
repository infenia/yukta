// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef } from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, renderHook, screen, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ListToggle, useListDisclosure } from '../ListDisclosure';

function Probe({
  selection,
  heading = true,
  ready = true,
}: {
  selection: string | null;
  heading?: boolean;
  ready?: boolean;
}) {
  const detailRef = useRef<HTMLDivElement>(null);
  const { open, toggle, listClass, onPick } = useListDisclosure(
    selection,
    detailRef,
    ready
  );
  return (
    <>
      <ListToggle
        label="All things"
        open={open}
        controls="things"
        onToggle={toggle}
      />
      <output aria-label="list class">{listClass ?? 'shown'}</output>
      <button type="button" onClick={onPick}>
        pick
      </button>
      <div ref={detailRef}>{heading && <h2>Title</h2>}</div>
    </>
  );
}

afterEach(() => {
  // jsdom has no scrollIntoView
  delete (HTMLElement.prototype as { scrollIntoView?: unknown }).scrollIntoView;
});

describe('the phone list disclosure', () => {
  it('shows the list while nothing is chosen, and folds it once something is', () => {
    const { rerender } = render(<Probe selection={null} />);
    expect(screen.getByLabelText('list class')).toHaveTextContent('shown');
    rerender(<Probe selection="a" />);
    expect(screen.getByLabelText('list class')).toHaveTextContent(
      'max-md:hidden'
    );
  });

  it('opens and closes from its button, which says what it controls', async () => {
    const user = userEvent.setup();
    render(<Probe selection="a" />);
    const toggle = screen.getByRole('button', { name: /All things/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveAttribute('aria-controls', 'things');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByLabelText('list class')).toHaveTextContent('shown');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
  });

  it('closes the list and moves focus to the heading when an item is picked', async () => {
    const user = userEvent.setup();
    const scrollIntoView = vi.fn();
    HTMLElement.prototype.scrollIntoView = scrollIntoView;
    const { rerender } = render(<Probe selection="a" />);
    await user.click(screen.getByRole('button', { name: /All things/ }));
    await user.click(screen.getByRole('button', { name: 'pick' }));
    expect(screen.getByLabelText('list class')).toHaveTextContent(
      'max-md:hidden'
    );
    rerender(<Probe selection="b" />);
    expect(screen.getByRole('heading', { name: 'Title' })).toHaveFocus();
    expect(scrollIntoView).toHaveBeenCalledOnce();
  });

  it('waits until the detail says it is ready, then moves focus to its heading', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Probe selection="a" />);
    await user.click(screen.getByRole('button', { name: 'pick' }));
    rerender(<Probe selection="b" ready={false} />);
    expect(screen.getByRole('heading', { name: 'Title' })).not.toHaveFocus();
    rerender(<Probe selection="b" ready />);
    expect(screen.getByRole('heading', { name: 'Title' })).toHaveFocus();
  });

  it('keeps focus where it is when the selection changes without a pick', () => {
    const { rerender } = render(<Probe selection="a" />);
    rerender(<Probe selection="b" />);
    expect(screen.getByRole('heading', { name: 'Title' })).not.toHaveFocus();
  });

  it('copes with a detail that has no heading yet, or no element at all', async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Probe selection="a" heading={false} />);
    await user.click(screen.getByRole('button', { name: 'pick' }));
    rerender(<Probe selection="b" heading={false} />);
    expect(screen.queryByRole('heading')).toBeNull();
    const detailRef = { current: null };
    const hook = renderHook(
      ({ selection }) => useListDisclosure(selection, detailRef),
      {
        initialProps: { selection: 'a' },
      }
    );
    act(() => hook.result.current.onPick());
    hook.rerender({ selection: 'b' });
    expect(hook.result.current.open).toBe(false);
  });
});
