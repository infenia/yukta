// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { afterEach, describe, expect, it, vi } from 'vitest';
import { useRef } from 'react';
import { render } from '@testing-library/react';
import { usePageTransition } from '../usePageTransition';

function Page({ path, animate }: { path: string; animate?: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  usePageTransition(ref, path);
  return (
    <div
      ref={(node) => {
        if (node && animate) {
          node.animate = animate as unknown as HTMLElement['animate'];
        }
        ref.current = node;
      }}
    />
  );
}

function mockReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockReturnValue({ matches: reduce } as MediaQueryList)
  );
}

describe('usePageTransition', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('fades the page in on mount and on every path change', () => {
    mockReducedMotion(false);
    const animate = vi.fn();
    const { rerender } = render(<Page path="/a" animate={animate} />);
    expect(animate).toHaveBeenCalledTimes(1);
    expect(animate.mock.calls[0][1]).toMatchObject({ duration: 350 });
    rerender(<Page path="/b" animate={animate} />);
    expect(animate).toHaveBeenCalledTimes(2);
    rerender(<Page path="/b" animate={animate} />);
    expect(animate).toHaveBeenCalledTimes(2);
  });

  it('does not animate when the user prefers reduced motion', () => {
    mockReducedMotion(true);
    const animate = vi.fn();
    render(<Page path="/a" animate={animate} />);
    expect(animate).not.toHaveBeenCalled();
  });

  it('does nothing before the element is attached', () => {
    mockReducedMotion(false);
    function Detached() {
      const ref = useRef<HTMLDivElement>(null);
      usePageTransition(ref, '/a');
      return null;
    }
    expect(() => render(<Detached />)).not.toThrow();
  });

  it('does nothing where the Web Animations API is missing', () => {
    mockReducedMotion(false);
    expect(() => render(<Page path="/a" />)).not.toThrow();
  });
});
