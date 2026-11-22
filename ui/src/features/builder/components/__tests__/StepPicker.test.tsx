// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expectTooltip } from '@/test/utils/testUtils';
import type { PluginSummary } from '@/features/plugins/types';
import { StepPicker } from '../StepPicker';

const plugins: PluginSummary[] = [
  { type: 'API_TRIGGER', category: 'TRIGGER', description: 'Starts a run' },
  {
    type: 'LLM_CALL',
    category: 'PROCESSOR',
    description: 'Asks a model',
    enabled: false,
    enableWith: 'set yukta.plugins.enabled to LLM_CALL',
  },
  {
    type: 'HTTP_REQUEST',
    category: 'PROCESSOR',
    description: 'Calls a web service',
  },
  { type: 'GATE', category: 'PROCESSOR', enabled: false, enableWith: null },
];

function setup(allowed?: Parameters<typeof StepPicker>[0]['allowed']) {
  const onPick = vi.fn();
  render(
    <StepPicker plugins={plugins} allowed={allowed} onPick={onPick}>
      <button type="button">Add step</button>
    </StepPicker>
  );
  return { onPick, user: userEvent.setup() };
}

describe('StepPicker', () => {
  beforeEach(() => localStorage.clear());

  it('lists each step with its name and description, and picks one', async () => {
    const { onPick, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));

    const processors = within(
      await screen.findByRole('region', { name: 'Processors' })
    );
    expect(processors.getByText('Calls a web service')).toBeInTheDocument();
    await user.click(processors.getByRole('button', { name: /Http Request/ }));

    expect(onPick).toHaveBeenCalledExactlyOnceWith('HTTP_REQUEST');
    expect(screen.queryByLabelText('Search steps')).toBeNull();
  });

  it('greys out a step the operator has not enabled and says how to enable it', async () => {
    const { onPick, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));

    const llm = await screen.findByRole('button', { name: /Llm Call/ });
    expect(llm).toBeDisabled();
    expect(llm).toHaveTextContent(
      'Not enabled: set yukta.plugins.enabled to LLM_CALL'
    );
    await expectTooltip(
      llm.parentElement as HTMLElement,
      'set yukta.plugins.enabled to LLM_CALL'
    );
    // without a hint there is nothing to add after the label
    const gate = screen.getByRole('button', { name: /Gate/ });
    expect(gate).toHaveTextContent(/Gate\s*Not enabled$/);
    await user.click(llm);
    expect(onPick).not.toHaveBeenCalled();
  });

  it('narrows the list as you type, and Enter picks the first step that is enabled', async () => {
    const { onPick, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));
    const search = await screen.findByLabelText('Search steps');

    await user.type(search, 'call');
    expect(screen.queryByRole('button', { name: /Gate/ })).toBeNull();
    expect(screen.getByRole('button', { name: /Http Request/ })).toBeVisible();
    await user.clear(search);
    await user.type(search, 'model{Enter}');
    // the only match, Llm Call, is not enabled: Enter picks nothing
    expect(onPick).not.toHaveBeenCalled();

    await user.clear(search);
    await user.type(search, 'call{Enter}');
    expect(onPick).toHaveBeenCalledExactlyOnceWith('HTTP_REQUEST');
  });

  it('says so when nothing matches', async () => {
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));
    await user.type(await screen.findByLabelText('Search steps'), 'zzz');
    expect(screen.getByText('No step matches “zzz”.')).toBeVisible();
  });

  it('lists only the allowed categories', async () => {
    const { user } = setup(['TRIGGER']);
    await user.click(screen.getByRole('button', { name: 'Add step' }));
    expect(
      await screen.findByRole('region', { name: 'Triggers' })
    ).toBeVisible();
    expect(screen.queryByRole('region', { name: 'Processors' })).toBeNull();
  });

  it('offers the step picked last at the top the next time it opens', async () => {
    const { user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));
    expect(screen.queryByRole('region', { name: 'Recent' })).toBeNull();
    await user.click(
      within(await screen.findByRole('region', { name: 'Triggers' })).getByRole(
        'button',
        { name: /Api Trigger/ }
      )
    );

    await user.click(screen.getByRole('button', { name: 'Add step' }));
    expect(
      within(await screen.findByRole('region', { name: 'Recent' })).getByRole(
        'button',
        { name: /Api Trigger/ }
      )
    ).toBeVisible();
    // the search starts empty each time
    expect(screen.getByLabelText('Search steps')).toHaveValue('');
  });

  it('closes without picking when Escape is pressed, and the list is gone', async () => {
    const { onPick, user } = setup();
    await user.click(screen.getByRole('button', { name: 'Add step' }));
    await user.type(await screen.findByLabelText('Search steps'), 'call');

    await user.keyboard('{Escape}');

    expect(screen.queryByLabelText('Search steps')).toBeNull();
    expect(onPick).not.toHaveBeenCalled();
  });
});

describe('StepPicker without plugins', () => {
  it('says there are none', async () => {
    render(
      <StepPicker plugins={[]} onPick={vi.fn()}>
        <button type="button">Add step</button>
      </StepPicker>
    );
    await userEvent.click(screen.getByRole('button', { name: 'Add step' }));
    expect(await screen.findByText('No plugins are available.')).toBeVisible();
  });
});
