// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { NeedsPlugins, enableSetting } from '../NeedsPlugins';

describe('NeedsPlugins', () => {
  it('shows nothing when no plugin is off', () => {
    const { container } = render(<NeedsPlugins types={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('names the plugins and the setting that switches them on', () => {
    render(<NeedsPlugins types={['FILE_READ', 'FILE_WRITE']} />);
    expect(screen.getByRole('status')).toHaveTextContent(
      'Needs FILE_READ, FILE_WRITE enabled'
    );
    expect(
      screen.getByText('YUKTA_PLUGINS_ENABLED=FILE_READ,FILE_WRITE')
    ).toBeVisible();
    expect(enableSetting(['A'])).toBe('YUKTA_PLUGINS_ENABLED=A');
  });

  it('takes its own heading', () => {
    render(<NeedsPlugins types={['X']} title="Heads up" />);
    expect(screen.getByText('Heads up')).toBeVisible();
  });
});
