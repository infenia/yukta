// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Tip, TooltipProvider } from '../tooltip';
import { expectTooltip } from '@/test/utils/testUtils';

function renderTip(ui: React.ReactElement) {
  return render(<TooltipProvider delay={0}>{ui}</TooltipProvider>);
}

const tooltip = () => document.querySelector('[data-slot="tooltip-content"]');

describe('Tip', () => {
  it('shows the label on hover, with an arrow', async () => {
    renderTip(
      <Tip label="Pause run">
        <button type="button">P</button>
      </Tip>
    );
    await expectTooltip(screen.getByRole('button'), 'Pause run');
    expect(tooltip()?.querySelector('svg')).toBeInTheDocument();
  });

  it('opens on keyboard focus, describes the trigger and closes on Escape', async () => {
    const user = userEvent.setup();
    renderTip(
      <Tip label="Pause run">
        <button type="button" aria-describedby="hint">
          P
        </button>
      </Tip>
    );
    const button = screen.getByRole('button');
    await user.tab();
    expect(button).toHaveFocus();
    await screen.findByText('Pause run');
    expect(button.getAttribute('aria-describedby')).toBe(
      `hint ${tooltip()?.id}`
    );
    await user.keyboard('{Escape}');
    await waitFor(() => expect(tooltip()).not.toBeInTheDocument());
    expect(button).toHaveAttribute('aria-describedby', 'hint');
  });

  it('does not describe a trigger whose aria-label is the label', async () => {
    renderTip(
      <Tip label="Pause run">
        <button type="button" aria-label="Pause run">
          P
        </button>
      </Tip>
    );
    const button = screen.getByRole('button');
    await expectTooltip(button, 'Pause run');
    expect(button).not.toHaveAttribute('aria-describedby');
  });

  it('renders the child untouched for a falsy label', () => {
    renderTip(
      <Tip label="">
        <button type="button">P</button>
      </Tip>
    );
    expect(screen.getByRole('button').parentElement).toBe(
      document.body.firstChild
    );
  });

  it('lets title replace the label and sets aria-description', async () => {
    renderTip(
      <Tip label="Pause run" title="Needs OPERATOR">
        <button type="button">P</button>
      </Tip>
    );
    const button = screen.getByRole('button');
    expect(button).toHaveAttribute('aria-description', 'Needs OPERATOR');
    await expectTooltip(button, 'Needs OPERATOR');
    expect(button).not.toHaveAttribute('aria-describedby');
  });

  it('wraps a disabled child so hover still reaches it', async () => {
    renderTip(
      <Tip label="Needs OPERATOR" disabled>
        <button type="button">P</button>
      </Tip>
    );
    const button = screen.getByRole('button');
    expect(button).toBeDisabled();
    expect(button.parentElement?.tagName).toBe('SPAN');
    await expectTooltip(button.parentElement as HTMLElement, 'Needs OPERATOR');
  });

  it('shows a shortcut and a description without leaking them to the child', async () => {
    renderTip(
      <Tip label="Run" shortcut={['Shift', 'R']} description="Starts a new run">
        <button type="button">P</button>
      </Tip>
    );
    const button = screen.getByRole('button');
    await expectTooltip(button, 'Run');
    expect(
      [...(tooltip()?.querySelectorAll('kbd') ?? [])].map((k) => k.textContent)
    ).toEqual(['Shift', 'R']);
    expect(screen.getByText('Starts a new run')).toBeInTheDocument();
    expect(button).not.toHaveAttribute('shortcut');
    expect(button).not.toHaveAttribute('description');
  });

  it('accepts a single-key shortcut', async () => {
    renderTip(
      <Tip label="Run" shortcut="R">
        <button type="button">P</button>
      </Tip>
    );
    await expectTooltip(screen.getByRole('button'), 'Run');
    expect(tooltip()?.querySelector('kbd')).toHaveTextContent('R');
  });
});
