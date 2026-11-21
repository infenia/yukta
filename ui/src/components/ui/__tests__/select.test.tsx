// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Select, type SelectOption } from '../select';
import { choose } from '@/test/utils/choose';

const OPTIONS: SelectOption[] = [
  { value: '', label: 'Any severity' },
  { value: 'WARN', label: 'Warning or above' },
  { value: 'ERROR', label: 'Error' },
];

function Harness({
  onChange = () => {},
  ...rest
}: Partial<React.ComponentProps<typeof Select>> & {
  onChange?: (v: string) => void;
}) {
  const [value, setValue] = useState('');
  return (
    <Select
      ariaLabel="Severity"
      options={OPTIONS}
      {...rest}
      value={value}
      onValueChange={(v) => {
        setValue(v);
        onChange(v);
      }}
    />
  );
}

describe('Select', () => {
  it('shows the chosen label, exposes its value and reports a pick', async () => {
    const onChange = vi.fn();
    render(<Harness onChange={onChange} />);
    const trigger = screen.getByRole('combobox', { name: 'Severity' });
    expect(trigger).toHaveTextContent('Any severity');
    expect(trigger).toHaveAttribute('data-value', '');
    await choose(userEvent, trigger, 'ERROR');
    expect(onChange).toHaveBeenLastCalledWith('ERROR');
    expect(trigger).toHaveTextContent('Error');
    expect(trigger).toHaveAttribute('data-value', 'ERROR');
  });

  it('can be named by a label and disabled', () => {
    render(
      <>
        <label htmlFor="sev">Level</label>
        <Harness id="sev" ariaLabel={undefined} disabled />
      </>
    );
    expect(screen.getByLabelText('Level')).toBeDisabled();
  });

  it('lists grouped options under their heading, ungrouped first', async () => {
    render(
      <Harness
        options={[
          { value: 'a', label: 'Alpha', group: 'Letters' },
          { value: '', label: 'None' },
        ]}
      />
    );
    await userEvent.click(screen.getByRole('combobox'));
    const options = await screen.findAllByRole('option');
    expect(options.map((o) => o.textContent?.replace('check', ''))).toEqual([
      'None',
      'Alpha',
    ]);
    expect(screen.getByRole('group', { name: 'Letters' })).toBeInTheDocument();
  });

  describe('searchable', () => {
    it('filters by typing, picks, and says when nothing matches', async () => {
      const onChange = vi.fn();
      render(<Harness searchable onChange={onChange} />);
      const trigger = screen.getByRole('combobox', { name: 'Severity' });
      await userEvent.click(trigger);
      await userEvent.type(
        await screen.findByRole('combobox', { name: 'Search Severity' }),
        'warn'
      );
      await waitFor(() =>
        expect(screen.getAllByRole('option')).toHaveLength(1)
      );
      await userEvent.click(
        screen.getByRole('option', { name: 'Warning or above' })
      );
      expect(onChange).toHaveBeenLastCalledWith('WARN');
      expect(trigger).toHaveTextContent('Warning or above');

      await userEvent.click(trigger);
      await userEvent.type(
        await screen.findByRole('combobox', { name: 'Search Severity' }),
        'zzz'
      );
      expect(await screen.findByText('No matches')).toBeInTheDocument();
    });

    it('shows the placeholder with no selection, groups, and a nameless search', async () => {
      render(
        <Harness
          searchable
          ariaLabel={undefined}
          placeholder="Pick one"
          options={[{ value: 'a', label: 'Alpha', group: 'Letters' }]}
        />
      );
      const trigger = screen.getByRole('combobox');
      expect(trigger).toHaveTextContent('Pick one');
      await userEvent.click(trigger);
      expect(
        await screen.findByRole('combobox', { name: 'Search options' })
      ).toBeInTheDocument();
      expect(
        screen.getByRole('group', { name: 'Letters' })
      ).toBeInTheDocument();
      await userEvent.click(screen.getByRole('option', { name: 'Alpha' }));
      expect(trigger).toHaveTextContent('Alpha');
    });

    it('can be disabled', () => {
      render(<Harness searchable disabled />);
      expect(screen.getByRole('combobox', { name: 'Severity' })).toBeDisabled();
    });
  });
});
