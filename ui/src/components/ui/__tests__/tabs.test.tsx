// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Tabs } from '../tabs';

const items = [
  { id: 'a', label: 'Alpha', count: 2 },
  { id: 'b', label: 'Beta' },
  { id: 'c', label: 'Gamma' },
] as const;

function Harness() {
  const [value, setValue] = useState<'a' | 'b' | 'c'>('a');
  return (
    <Tabs ariaLabel="Letters" items={items} value={value} onChange={setValue} />
  );
}

describe('Tabs', () => {
  it('marks the selected tab, shows the count, and moves with the arrow keys', async () => {
    const user = userEvent.setup();
    render(<Harness />);
    expect(screen.getByRole('tab', { name: /Alpha/ })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByRole('tablist', { name: 'Letters' })).toHaveTextContent(
      '2'
    );

    await user.click(screen.getByRole('tab', { name: /Alpha/ }));
    await user.keyboard('{ArrowRight}');
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByRole('tab', { name: 'Beta' })).toHaveFocus();
    await user.keyboard('{ArrowLeft}{ArrowLeft}');
    expect(screen.getByRole('tab', { name: 'Gamma' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    await user.keyboard('{Home}');
    expect(screen.getByRole('tab', { name: /Alpha/ })).toHaveFocus();
  });
});
