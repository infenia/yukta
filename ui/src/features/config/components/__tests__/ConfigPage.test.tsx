// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { ConfigPage } from '../ConfigPage';
import { CATALOG } from '../../lib/catalog';

const setup = () =>
  render(
    <MemoryRouter>
      <ConfigPage />
    </MemoryRouter>
  );

const rowCount = () => screen.getAllByRole('row').length - 1;

describe('ConfigPage', () => {
  it('lists every option with the how-it-works note', () => {
    setup();
    expect(
      screen.getByRole('heading', { name: 'Configuration' })
    ).toBeInTheDocument();
    expect(screen.getByText('How configuration works')).toBeInTheDocument();
    expect(rowCount()).toBe(CATALOG.length);
  });

  it('searches by key, variable and purpose', async () => {
    setup();
    await userEvent.type(
      screen.getByLabelText('Search options'),
      'SERVER_PORT'
    );
    expect(rowCount()).toBe(1);
    expect(screen.getByText('server.port')).toBeInTheDocument();
  });

  it('filters by area', async () => {
    setup();
    await userEvent.selectOptions(screen.getByLabelText('Area'), 'MCP');
    expect(rowCount()).toBe(CATALOG.filter((o) => o.area === 'MCP').length);
  });

  it('filters by when a change applies', async () => {
    setup();
    await userEvent.selectOptions(screen.getByLabelText('Applies'), 'runtime');
    expect(rowCount()).toBe(
      CATALOG.filter((o) => o.appliesWhen === 'runtime').length
    );
    expect(screen.getAllByText('Live').length).toBeGreaterThan(0);
  });

  it('filters to options that differ in the native image', async () => {
    setup();
    await userEvent.selectOptions(screen.getByLabelText('Mode'), 'native');
    expect(rowCount()).toBe(CATALOG.filter((o) => o.native).length);
  });

  it('shows an empty state when nothing matches', async () => {
    setup();
    await userEvent.type(screen.getByLabelText('Search options'), 'zzzzzz');
    expect(screen.getByText('No options match.')).toBeInTheDocument();
  });

  it('expands a row for how to set it and shows the native caveat, one at a time', async () => {
    setup();
    await userEvent.click(
      screen.getByRole('button', { name: 'yukta.session.store-type' })
    );
    expect(screen.getByText('Environment variable')).toBeInTheDocument();
    expect(screen.getByText('Native image and Docker')).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'yukta.rate-limit-per-minute' })
    );
    expect(screen.queryByText('Native image and Docker')).toBeNull();
    expect(screen.getByText('JVM jar')).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'yukta.rate-limit-per-minute' })
    );
    expect(screen.queryByText('JVM jar')).toBeNull();
  });
});
