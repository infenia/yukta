// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ConfigField } from '@/features/plugins/types';
import { ConfigForm } from '../ConfigForm';
import { defaultHint, humanize } from '../ConfigForm.helpers';

const field = (rest: Partial<ConfigField> = {}): ConfigField => ({
  name: 'f',
  type: 'STRING',
  required: false,
  defaultValue: null,
  options: [],
  description: '',
  ...rest,
});

function Harness({
  schema,
  initial = {},
}: {
  schema: ConfigField[];
  initial?: Record<string, unknown>;
}) {
  const [values, setValues] = useState(initial);
  return (
    <>
      <ConfigForm schema={schema} values={values} onChange={setValues} />
      <output data-testid="values">{JSON.stringify(values)}</output>
    </>
  );
}
const values = () => screen.getByTestId('values').textContent;

describe('humanize', () => {
  it('turns a key into a readable name', () => {
    expect(humanize('elementTimeoutMs')).toBe('Element timeout (ms)');
    expect(humanize('onError')).toBe('On error');
    expect(humanize('HTTP_METHOD')).toBe('Http method');
    expect(humanize('max-attempts')).toBe('Max attempts');
    expect(humanize('url')).toBe('Url');
    expect(humanize('ms')).toBe('Ms');
  });
});

describe('defaultHint', () => {
  it('spells out a default, and says nothing without one', () => {
    expect(defaultHint(field({ defaultValue: 'GET' }))).toBe('Default: GET');
    expect(defaultHint(field({ defaultValue: 30 }))).toBe('Default: 30');
    expect(defaultHint(field({ defaultValue: false }))).toBe('Default: false');
    expect(defaultHint(field({ defaultValue: ['a'] }))).toBe('Default: ["a"]');
    expect(defaultHint(field({ defaultValue: null }))).toBeNull();
    expect(defaultHint(field({ defaultValue: undefined }))).toBeNull();
    expect(defaultHint(field({ defaultValue: '' }))).toBeNull();
  });
});

describe('ConfigForm labels and hints', () => {
  it('labels a field by its readable name and shows the key too when it differs', () => {
    render(
      <Harness
        schema={[field({ name: 'elementTimeoutMs' }), field({ name: 'url' })]}
      />
    );
    expect(screen.getByLabelText('Element timeout (ms)')).toBeInTheDocument();
    expect(screen.getByText('elementTimeoutMs')).toBeInTheDocument();
    expect(screen.getByLabelText('Url')).toBeInTheDocument();
    expect(screen.queryByText('url')).toBeNull();
  });

  it('shows the default under a field, but not under a select, which says it itself', () => {
    render(
      <Harness
        schema={[
          field({ name: 'method', defaultValue: 'GET' }),
          field({
            name: 'mode',
            type: 'ENUM',
            options: ['A', 'B'],
            defaultValue: 'A',
          }),
          field({ name: 'bare' }),
        ]}
      />
    );
    expect(screen.getByText('Default: GET')).toBeInTheDocument();
    expect(screen.queryByText('Default: A')).toBeNull();
    expect(screen.getAllByText(/^Default:/)).toHaveLength(1);
  });

  it('says what an expression is evaluated on', () => {
    render(<Harness schema={[field({ name: 'when', type: 'EXPRESSION' })]} />);
    expect(screen.getByText('payload.x')).toBeInTheDocument();
    expect(screen.getByText('metadata.x')).toBeInTheDocument();
  });
});

describe('ConfigForm map fields', () => {
  const schema = [field({ name: 'headers', type: 'MAP' })];

  it('edits a map as rows of key and value, leaving out a row with no key', async () => {
    const user = userEvent.setup();
    render(<Harness schema={schema} />);

    await user.click(screen.getByRole('button', { name: 'Add a row' }));
    await user.type(screen.getByLabelText('Key 1'), 'a');
    expect(values()).toBe('{"headers":{"a":""}}');
    await user.type(screen.getByLabelText('Value 1'), '1');
    expect(values()).toBe('{"headers":{"a":"1"}}');

    await user.click(screen.getByRole('button', { name: 'Add a row' }));
    await user.type(screen.getByLabelText('Value 2'), 'x');
    expect(values()).toBe('{"headers":{"a":"1"}}');
    await user.type(screen.getByLabelText('Key 2'), 'b');
    expect(values()).toBe('{"headers":{"a":"1","b":"x"}}');

    await user.click(screen.getByRole('button', { name: 'Remove row 1' }));
    expect(values()).toBe('{"headers":{"b":"x"}}');
    await user.click(screen.getByRole('button', { name: 'Remove row 1' }));
    expect(values()).toBe('{}');
  });

  it('opens a map of strings as rows, and one of anything else as JSON', () => {
    const { unmount } = render(
      <Harness schema={schema} initial={{ headers: { a: '1' } }} />
    );
    expect(screen.getByLabelText('Key 1')).toHaveValue('a');
    expect(screen.getByLabelText('Value 1')).toHaveValue('1');
    unmount();

    render(<Harness schema={schema} initial={{ headers: { a: 1 } }} />);
    expect(screen.queryByLabelText('Key 1')).toBeNull();
    expect(screen.getByLabelText('Headers')).toHaveValue('{\n  "a": 1\n}');
  });

  it('switches to JSON on request, keeping what is there', async () => {
    const user = userEvent.setup();
    render(<Harness schema={schema} initial={{ headers: { a: '1' } }} />);
    await user.click(screen.getByRole('button', { name: 'Edit as JSON' }));
    expect(screen.getByLabelText('Headers')).toHaveValue('{\n  "a": "1"\n}');
  });

  it('labels the first key for the field label, and does not rename the button', () => {
    const { unmount } = render(<Harness schema={schema} />);
    expect(screen.getByRole('button', { name: 'Add a row' })).toBeVisible();
    unmount();
    render(<Harness schema={schema} initial={{ headers: { a: '1' } }} />);
    expect(screen.getByLabelText('Headers')).toBe(
      screen.getByLabelText('Key 1')
    );
  });
});

describe('ConfigForm fields with known keys', () => {
  const retry = field({
    name: 'retry',
    type: 'MAP',
    fields: [
      field({
        name: 'maxAttempts',
        type: 'NUMBER',
        description: 'Total attempts.',
      }),
      field({ name: 'backoffMs', type: 'NUMBER' }),
    ],
  });

  it('shows one input per key with its help, and keeps the keys it does not show', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[retry]}
        initial={{ retry: { maxAttempts: 3, retryOn: ['IOException'] } }}
      />
    );
    expect(screen.getByLabelText('Max attempts')).toHaveValue(3);
    expect(screen.getByText('Total attempts.')).toBeInTheDocument();

    await user.type(screen.getByLabelText('Backoff (ms)'), '200');
    expect(values()).toBe(
      '{"retry":{"maxAttempts":3,"retryOn":["IOException"],"backoffMs":200}}'
    );
    await user.clear(screen.getByLabelText('Max attempts'));
    expect(values()).toBe(
      '{"retry":{"retryOn":["IOException"],"backoffMs":200}}'
    );
  });

  it('drops the whole map when its last key is cleared', async () => {
    const user = userEvent.setup();
    render(<Harness schema={[retry]} initial={{ retry: { backoffMs: 5 } }} />);
    await user.clear(screen.getByLabelText('Backoff (ms)'));
    expect(values()).toBe('{}');
  });

  it('starts from nothing when the value is not a map', async () => {
    const user = userEvent.setup();
    render(<Harness schema={[retry]} initial={{ retry: 'junk' }} />);
    expect(screen.getByLabelText('Max attempts')).toHaveValue(null);
    await user.type(screen.getByLabelText('Max attempts'), '2');
    expect(values()).toBe('{"retry":{"maxAttempts":2}}');
  });

  it('treats a map field without keys as a plain map, and a field without sub-fields as empty', () => {
    render(
      <Harness
        schema={[
          field({ name: 'bare', type: 'MAP', fields: undefined }),
          field({ name: 'none', type: 'MAP', fields: [] }),
        ]}
      />
    );
    // a plain map starts with no rows, so only its label and the button to add one show
    expect(screen.getByText('Bare')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add a row' })).toBeVisible();
    // a map with no keys declared shows its label and an empty frame
    expect(screen.getByText('None')).toBeInTheDocument();
  });
});
