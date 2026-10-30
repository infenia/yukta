// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  TagEditor,
  tagsError,
  toRows,
  toTags,
  type TagRow,
} from '../TagEditor';

function Harness({ initial }: { initial: TagRow[] }) {
  const [rows, setRows] = useState(initial);
  return (
    <>
      <TagEditor rows={rows} onChange={setRows} />
      <output data-testid="tags">{JSON.stringify(rows)}</output>
    </>
  );
}

describe('tag rows', () => {
  it('turns a tag map into rows and back, trimming keys', () => {
    expect(toRows({ env: 'prod', team: 'a' })).toEqual([
      { key: 'env', value: 'prod' },
      { key: 'team', value: 'a' },
    ]);
    expect(toTags([{ key: ' env ', value: 'x' }])).toEqual({ env: 'x' });
  });

  it('rejects an empty key and a repeated key', () => {
    expect(tagsError([{ key: 'a', value: '' }])).toBeNull();
    expect(tagsError([{ key: ' ', value: 'v' }])).toBe('A tag needs a key.');
    expect(
      tagsError([
        { key: 'a', value: '1' },
        { key: 'a ', value: '2' },
      ])
    ).toBe('The tag key "a" is used twice.');
  });
});

describe('TagEditor', () => {
  const tags = () => screen.getByTestId('tags');

  it('adds key: value tags on Enter, a comma or leaving the box, and removes them', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[{ key: 'env', value: 'prod' }]} />);
    const add = screen.getByLabelText('Add a tag');

    await user.type(add, ' team :  core {Enter}');
    await user.type(add, 'solo,');
    await user.type(add, 'stage: release');
    await user.tab();
    expect(tags()).toHaveTextContent(
      JSON.stringify([
        { key: 'env', value: 'prod' },
        { key: 'team', value: 'core' },
        { key: 'solo', value: '' },
        { key: 'stage', value: 'release' },
      ])
    );
    // a tag without a value shows its key alone
    expect(screen.getByRole('button', { name: 'solo' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove tag env' }));
    expect(tags()).not.toHaveTextContent('prod');
  });

  it('puts a clicked tag back in the box to edit it', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[{ key: 'env', value: 'prod' }]} />);
    await user.click(screen.getByRole('button', { name: 'env: prod' }));
    expect(screen.getByLabelText('Add a tag')).toHaveValue('env: prod');
    expect(tags()).toHaveTextContent('[]');
  });

  it('refuses a tag without a key or with a key in use, until the text changes', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[{ key: 'a', value: '1' }]} />);
    const add = screen.getByLabelText('Add a tag');

    await user.type(add, '{Enter}');
    expect(screen.queryByText(/needs a key/)).not.toBeInTheDocument();
    await user.type(add, ': x{Enter}');
    expect(
      screen.getByText('A tag needs a key: write key: value.')
    ).toBeInTheDocument();
    await user.clear(add);
    expect(screen.queryByText(/needs a key/)).not.toBeInTheDocument();
    await user.type(add, 'a: 2{Enter}');
    expect(
      screen.getByText('The tag key "a" is used already.')
    ).toBeInTheDocument();
    expect(tags()).toHaveTextContent(
      JSON.stringify([{ key: 'a', value: '1' }])
    );
  });

  it('says why rows given to it cannot be saved', () => {
    render(<Harness initial={[{ key: '', value: '1' }]} />);
    expect(screen.getByText('A tag needs a key.')).toBeInTheDocument();
  });
});
