// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ConfigField } from '@/features/plugins/types';
import { ConfigForm } from '../ConfigForm';

const field = (over: Partial<ConfigField>): ConfigField => ({
  name: 'f',
  type: 'STRING',
  required: false,
  defaultValue: null,
  options: [],
  description: '',
  ...over,
});

/** Keeps the values in state like the inspector does and exposes them as JSON. */
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

describe('ConfigForm', () => {
  it('gives same-named fields of two forms distinct ids', () => {
    const schema = [field({ name: 'trigger' })];
    render(
      <>
        <ConfigForm schema={schema} values={{}} onChange={() => {}} />
        <ConfigForm schema={schema} values={{}} onChange={() => {}} />
      </>
    );
    const [a, b] = screen.getAllByLabelText('trigger');
    expect(a.id).not.toBe(b.id);
  });

  it('edits and clears a text field, marking required ones and showing help', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[
          field({ name: 'url', required: true, description: 'the url' }),
        ]}
      />
    );
    expect(screen.getByText('the url')).toBeInTheDocument();
    expect(screen.getByText('*')).toBeInTheDocument();

    await user.type(screen.getByLabelText(/url/), 'abc');
    expect(values()).toBe('{"url":"abc"}');
    await user.clear(screen.getByLabelText(/url/));
    expect(values()).toBe('{}');
  });

  it('shows a secret hint and an expression editor', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[
          field({ name: 'key', type: 'SECRET' }),
          field({ name: 'expr', type: 'EXPRESSION' }),
        ]}
      />
    );
    expect(screen.getByPlaceholderText('decrypted:<key>')).toBeInTheDocument();
    await user.type(screen.getByLabelText('expr'), '#a');
    expect(values()).toBe('{"expr":"#a"}');
    await user.clear(screen.getByLabelText('expr'));
    expect(values()).toBe('{}');
  });

  it('edits numbers, showing the default as a placeholder', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[
          field({ name: 'timeout', type: 'NUMBER', defaultValue: 30 }),
          field({ name: 'plain', type: 'NUMBER' }),
        ]}
      />
    );
    expect(screen.getByPlaceholderText('30')).toBeInTheDocument();
    await user.type(screen.getByLabelText('timeout'), '45');
    expect(values()).toBe('{"timeout":45}');
    await user.clear(screen.getByLabelText('timeout'));
    expect(values()).toBe('{}');
  });

  it('toggles a boolean, starting from its default', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[field({ name: 'on', type: 'BOOLEAN', defaultValue: true })]}
      />
    );
    const box = screen.getByLabelText('on');
    expect(box).toBeChecked();
    await user.click(box);
    expect(values()).toBe('{"on":false}');
    await user.click(box);
    expect(values()).toBe('{"on":true}');
  });

  it('treats an unset boolean without a default as off', () => {
    render(<Harness schema={[field({ name: 'on', type: 'BOOLEAN' })]} />);
    expect(screen.getByLabelText('on')).not.toBeChecked();
  });

  it('picks an enum option and goes back to the default', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[
          field({
            name: 'method',
            type: 'ENUM',
            defaultValue: 'GET',
            options: ['GET', 'POST'],
          }),
          field({ name: 'bare', type: 'ENUM', options: ['x'] }),
        ]}
      />
    );
    expect(
      screen.getByRole('option', { name: 'Default (GET)' })
    ).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '—' })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText('method'), 'POST');
    expect(values()).toBe('{"method":"POST"}');
    await user.selectOptions(screen.getByLabelText('method'), 'Default (GET)');
    expect(values()).toBe('{}');
  });

  it('edits lists and maps as JSON, flagging invalid text and keeping the last good value', async () => {
    const user = userEvent.setup();
    render(
      <Harness
        schema={[field({ name: 'headers', type: 'MAP' })]}
        initial={{ headers: { a: '1' } }}
      />
    );
    const box = screen.getByLabelText('headers');
    expect(box).toHaveValue('{\n  "a": "1"\n}');

    await user.clear(box);
    expect(values()).toBe('{}');

    await user.click(box);
    await user.paste('{bad');
    expect(screen.getByText('Not valid JSON')).toBeInTheDocument();
    expect(values()).toBe('{}');

    await user.clear(box);
    await user.click(box);
    await user.paste('[1,2]');
    expect(screen.queryByText('Not valid JSON')).not.toBeInTheDocument();
    expect(values()).toBe('{"headers":[1,2]}');
  });

  it('adds and removes list items as chips, dropping the field when emptied', async () => {
    const user = userEvent.setup();
    render(<Harness schema={[field({ name: 'items', type: 'LIST' })]} />);
    await user.type(screen.getByLabelText('items'), 'prettier{Enter}');
    await user.type(screen.getByLabelText('items'), '--check');
    await user.tab();
    await user.type(screen.getByLabelText('items'), '   {Enter}');
    expect(values()).toBe('{"items":["prettier","--check"]}');
    await user.click(screen.getByRole('button', { name: 'Remove prettier' }));
    await user.click(screen.getByRole('button', { name: 'Remove --check' }));
    expect(values()).toBe('{}');
  });

  it('edits a list as JSON on request, and opens non-string lists that way', async () => {
    const user = userEvent.setup();
    const { unmount } = render(
      <Harness schema={[field({ name: 'items', type: 'LIST' })]} />
    );
    await user.click(screen.getByRole('button', { name: 'Edit as JSON' }));
    expect(screen.getByLabelText('items')).toHaveValue('');
    unmount();
    render(
      <Harness
        schema={[field({ name: 'items', type: 'LIST' })]}
        initial={{ items: [{ a: 1 }] }}
      />
    );
    expect(screen.getByLabelText('items')).toHaveValue(
      JSON.stringify([{ a: 1 }], null, 2)
    );
  });

  it('shows a non-string value in a text field as empty', () => {
    render(<Harness schema={[field({ name: 'url' })]} initial={{ url: 5 }} />);
    expect(screen.getByLabelText('url')).toHaveValue('');
  });
});
