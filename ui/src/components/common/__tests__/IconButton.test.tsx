// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { IconButton, IconLink } from '../IconButton';

describe('IconButton and IconLink', () => {
  it('an icon button is named by its label and tells what it does on hover', async () => {
    render(
      <IconButton icon="restart_alt" label="Rotate" tip="Rotate: a new one" />
    );
    const button = screen.getByRole('button', { name: 'Rotate' });
    expect(button).toHaveTextContent('restart_alt');
    await userEvent.hover(button);
    expect(await screen.findByText('Rotate: a new one')).toHaveAttribute(
      'data-slot',
      'tooltip-content'
    );
  });

  it('an icon link goes to its route, and its tooltip defaults to the label', async () => {
    render(
      <MemoryRouter>
        <IconLink
          to="/supervisors/x/edit"
          icon="edit"
          label="Edit supervisor"
        />
      </MemoryRouter>
    );
    const link = screen.getByRole('link', { name: 'Edit supervisor' });
    expect(link).toHaveAttribute('href', '/supervisors/x/edit');
    await userEvent.hover(link);
    expect(
      (await screen.findAllByText('Edit supervisor')).some(
        (el) => el.getAttribute('data-slot') === 'tooltip-content'
      )
    ).toBe(true);
  });

  it('an icon link takes a tooltip that reads differently from its name', async () => {
    render(
      <MemoryRouter>
        <IconLink
          to="/a"
          icon="open_in_new"
          label="Open"
          tip="Open it in a run"
        />
      </MemoryRouter>
    );
    await userEvent.hover(screen.getByRole('link', { name: 'Open' }));
    expect(await screen.findByText('Open it in a run')).toBeInTheDocument();
  });
});
