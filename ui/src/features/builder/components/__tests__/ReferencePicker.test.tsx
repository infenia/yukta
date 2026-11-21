// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfigForm } from '../ConfigForm';
import { ReferencePicker } from '../ReferencePicker';

describe('ReferencePicker', () => {
  it('offers the fields of an earlier step and says when it has no output yet', async () => {
    const onPick = vi.fn();
    render(
      <ReferencePicker
        references={[
          { nodeId: 'fetch', paths: ['user.id'] },
          { nodeId: 'cold', paths: [] },
        ]}
        onPick={onPick}
      />
    );
    await userEvent.click(screen.getByRole('button', { name: 'user.id' }));
    expect(onPick).toHaveBeenCalledWith('payload.user.id');
    expect(screen.getByText(/No output yet/)).toBeInTheDocument();
  });

  it('inserts into an expression field, after what is there', async () => {
    const onChange = vi.fn();
    const field = {
      name: 'when',
      type: 'EXPRESSION' as const,
      required: false,
      defaultValue: null,
      options: [],
      description: '',
    };
    const references = [{ nodeId: 'fetch', paths: ['n'] }];
    const { rerender } = render(
      <ConfigForm
        schema={[field]}
        values={{}}
        onChange={onChange}
        references={references}
      />
    );
    await userEvent.click(screen.getByRole('button', { name: 'n' }));
    expect(onChange).toHaveBeenLastCalledWith({ when: 'payload.n' });
    rerender(
      <ConfigForm
        schema={[field]}
        values={{ when: 'a >' }}
        onChange={onChange}
        references={references}
      />
    );
    // the caret sits after "a "
    screen.getByRole('textbox').setSelectionRange(2, 2);
    await userEvent.click(screen.getByRole('button', { name: 'n' }));
    expect(onChange).toHaveBeenLastCalledWith({ when: 'a payload.n>' });
  });
});
