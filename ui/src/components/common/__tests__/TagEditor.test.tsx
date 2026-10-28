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
  it('adds, edits and removes rows', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[{ key: 'env', value: 'prod' }]} />);

    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    await user.type(screen.getByLabelText('Tag 2 key'), 'team');
    await user.type(screen.getByLabelText('Tag 2 value'), 'core');
    expect(screen.getByTestId('tags')).toHaveTextContent(
      JSON.stringify([
        { key: 'env', value: 'prod' },
        { key: 'team', value: 'core' },
      ])
    );

    await user.click(screen.getByRole('button', { name: 'Remove tag 1' }));
    expect(screen.getByTestId('tags')).toHaveTextContent(
      JSON.stringify([{ key: 'team', value: 'core' }])
    );
  });

  it('says why the rows cannot be saved', async () => {
    const user = userEvent.setup();
    render(<Harness initial={[{ key: 'a', value: '1' }]} />);

    await user.click(screen.getByRole('button', { name: 'Add tag' }));
    expect(screen.getByText('A tag needs a key.')).toBeInTheDocument();
    await user.type(screen.getByLabelText('Tag 2 key'), 'a');
    expect(
      screen.getByText('The tag key "a" is used twice.')
    ).toBeInTheDocument();
  });
});
