// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ConfigField } from '@/features/plugins/types';
import { ConfigForm } from '../ConfigForm';
import { isMissing } from '../ConfigForm.helpers';

const field = (rest: Partial<ConfigField> = {}): ConfigField => ({
  name: 'url',
  type: 'STRING',
  required: true,
  defaultValue: null,
  options: [],
  description: '',
  ...rest,
});

describe('isMissing', () => {
  it('is true for a required field with nothing in it and no default', () => {
    for (const value of [undefined, null, '', []]) {
      expect(isMissing(field(), value)).toBe(true);
    }
  });

  it('is false when something is set, even zero or false', () => {
    for (const value of ['x', 0, false, ['a'], { k: 1 }]) {
      expect(isMissing(field(), value)).toBe(false);
    }
  });

  it('is false for a field that is optional or has a default', () => {
    expect(isMissing(field({ required: false }), undefined)).toBe(false);
    expect(isMissing(field({ defaultValue: 30 }), undefined)).toBe(false);
    expect(isMissing(field({ defaultValue: 0 }), undefined)).toBe(false);
    expect(isMissing(field({ defaultValue: undefined }), undefined)).toBe(true);
  });
});

describe('ConfigForm required fields', () => {
  it('flags an empty required field once the user has left it, not before', async () => {
    const user = userEvent.setup();
    render(
      <>
        <ConfigForm
          schema={[field(), field({ name: 'note', required: false })]}
          values={{}}
          onChange={vi.fn()}
        />
        <button type="button">elsewhere</button>
      </>
    );
    expect(screen.queryByText('url is required.')).toBeNull();

    await user.click(screen.getByLabelText(/^url/));
    expect(screen.queryByText('url is required.')).toBeNull();
    await user.click(screen.getByRole('button', { name: 'elsewhere' }));
    expect(screen.getByText('url is required.')).toBeInTheDocument();

    // an optional field is never flagged
    await user.click(screen.getByLabelText('note'));
    await user.click(screen.getByRole('button', { name: 'elsewhere' }));
    expect(screen.queryByText('note is required.')).toBeNull();
  });

  it('does not flag a required field that has a value', async () => {
    const user = userEvent.setup();
    render(
      <>
        <ConfigForm
          schema={[field()]}
          values={{ url: 'x' }}
          onChange={vi.fn()}
        />
        <button type="button">elsewhere</button>
      </>
    );
    await user.click(screen.getByLabelText(/^url/));
    await user.click(screen.getByRole('button', { name: 'elsewhere' }));
    expect(screen.queryByText('url is required.')).toBeNull();
  });
});
