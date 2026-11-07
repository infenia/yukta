// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CodeBlock } from '../CodeBlock';

describe('CodeBlock', () => {
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  afterEach(() => {
    if (original) Object.defineProperty(navigator, 'clipboard', original);
    else Reflect.deleteProperty(navigator, 'clipboard');
  });

  const setClipboard = (value: unknown) =>
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value,
    });

  it('shows the code and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    setClipboard({ writeText });
    render(<CodeBlock code='{"a":1}' label="usage" />);

    fireEvent.click(screen.getByRole('button', { name: 'Copy usage' }));

    expect(writeText).toHaveBeenCalledWith('{"a":1}');
    expect(await screen.findByText('Copied')).toBeInTheDocument();
  });

  it('goes back to Copy after a moment', async () => {
    vi.useFakeTimers();
    try {
      setClipboard({ writeText: vi.fn().mockResolvedValue(undefined) });
      render(<CodeBlock code="x" />);

      fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(1600);
      });

      expect(screen.getByRole('button', { name: 'Copy' })).toHaveTextContent(
        'Copy'
      );
    } finally {
      vi.useRealTimers();
    }
  });

  it('says so when the copy is refused', async () => {
    const user = userEvent.setup();
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('no')) });
    render(<CodeBlock code="x" />);

    await user.click(screen.getByRole('button', { name: 'Copy' }));

    expect(await screen.findByText('Copy failed')).toBeInTheDocument();
  });

  it('says so when there is no clipboard', async () => {
    const user = userEvent.setup();
    setClipboard(undefined);
    render(<CodeBlock code="x" />);

    await user.click(screen.getByRole('button', { name: 'Copy' }));

    expect(await screen.findByText('Copy failed')).toBeInTheDocument();
  });

  it('colors JSON tokens and leaves other text plain', () => {
    const json = '{"a": "x", "n": 1, "b": true, "z": null}';
    const { container, rerender } = render(<CodeBlock code={json} />);
    const cls = (t: string) =>
      screen.getByText(t, { selector: 'span' }).className;
    expect(cls('"a"')).toContain('text-primary');
    expect(cls('"x"')).toContain('text-success');
    expect(cls('1')).toContain('text-attention');
    expect(cls('true')).toContain('text-muted-foreground');
    expect(cls('null')).toContain('text-muted-foreground');
    expect(container.querySelector('pre')).toHaveTextContent(json);

    rerender(<CodeBlock code="run 1 true" />);
    expect(container.querySelector('pre span')).toBeNull();
  });
});
