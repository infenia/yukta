// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import HeaderPortal from '../HeaderPortal';

describe('HeaderPortal', () => {
  it('renders children into the target element', () => {
    render(
      <div>
        <div id="portal-target" data-testid="portal-target" />
        <HeaderPortal targetId="portal-target">
          <span>portaled content</span>
        </HeaderPortal>
      </div>
    );

    expect(screen.getByTestId('portal-target')).toHaveTextContent('portaled content');
  });

  it('renders nothing when the target does not exist', () => {
    render(
      <HeaderPortal targetId="missing-target">
        <span>invisible</span>
      </HeaderPortal>
    );

    expect(screen.queryByText('invisible')).toBeNull();
  });
});
