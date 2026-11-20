// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, useLocation } from 'react-router';
import { ConfigPage } from '../ConfigPage';
import { CATALOG } from '../../lib/catalog';
import { useConfigValues, type LiveValues } from '../../hooks/useConfigValues';

vi.mock('../../hooks/useConfigValues', () => ({
  useConfigValues: vi.fn(),
}));

vi.mock('@/features/control/dashboard/CapacityPanel', () => ({
  CapacityPanel: ({ live }: { live: boolean }) => (
    <div>capacity panel live={String(live)}</div>
  ),
}));

function Search() {
  return <p data-testid="search">{useLocation().search}</p>;
}

const setup = (path = '/config') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <ConfigPage />
      <Search />
    </MemoryRouter>
  );

const rowCount = () => screen.getAllByRole('row').length - 1;

const live = (state: LiveValues) =>
  vi.mocked(useConfigValues).mockReturnValue(state);

describe('ConfigPage', () => {
  beforeEach(() => live({ status: 'denied' }));

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

  it('opens the Runtime tab from the URL and says the change is not saved', () => {
    setup('/config?tab=runtime');
    expect(screen.getByRole('tab', { name: 'Runtime' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(screen.getByText('capacity panel live=true')).toBeInTheDocument();
    expect(
      screen.getByText('Changes here are live, but not saved')
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Search options')).toBeNull();
  });

  it('switches between the tabs and keeps the choice in the URL', async () => {
    setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Runtime' }));
    expect(screen.getByTestId('search')).toHaveTextContent('?tab=runtime');
    expect(screen.getByText('capacity panel live=true')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('tab', { name: 'Options' }));
    expect(screen.getByTestId('search')).toHaveTextContent(/^$/);
    expect(screen.getByLabelText('Search options')).toBeInTheDocument();
  });

  it('opens the Packaging tab', async () => {
    setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Packaging' }));
    expect(screen.getByTestId('search')).toHaveTextContent('?tab=packaging');
    expect(
      screen.getByRole('heading', {
        name: 'JVM jar, native binary and Docker image',
      })
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Search options')).toBeNull();
  });

  describe('current values', () => {
    const ready = () =>
      live({
        status: 'ready',
        values: new Map([
          [
            'server.port',
            { key: 'server.port', value: '7000', source: 'argument' },
          ],
          [
            'yukta.execution.timeout',
            { key: 'yukta.execution.timeout', value: null, source: 'default' },
          ],
          [
            'yukta.api-keys[0].key',
            {
              key: 'yukta.api-keys[0].key',
              value: '******',
              source: 'environment variable',
            },
          ],
        ]),
      });

    it('adds the value and source columns', () => {
      ready();
      setup();
      const port = screen.getByRole('row', { name: /server\.port/ });
      expect(port).toHaveTextContent('7000');
      expect(port).toHaveTextContent('argument');
      expect(
        screen.getByRole('row', { name: /yukta\.execution\.timeout/ })
      ).toHaveTextContent('Not set');
      expect(
        screen.getByRole('row', { name: /yukta\.api-keys\[0\]\.key/ })
      ).toHaveTextContent('******');
      expect(
        screen.getByRole('columnheader', { name: 'Current value' })
      ).toBeInTheDocument();
    });

    it('shows a dash where the server reports nothing for an option', () => {
      ready();
      setup();
      expect(
        screen.getByRole('row', { name: /yukta\.session\.base-dir/ })
      ).toHaveTextContent('—');
    });

    it('filters to the options set on this server, and spans the detail row', async () => {
      ready();
      setup();
      await userEvent.selectOptions(screen.getByLabelText('Value'), 'set');
      expect(rowCount()).toBe(2);

      await userEvent.click(
        screen.getByRole('button', { name: 'server.port' })
      );
      expect(
        screen.getByText('Environment variable').closest('td')
      ).toHaveAttribute('colspan', '6');
    });

    it('keeps four columns and no value filter without values', () => {
      setup();
      expect(screen.queryByLabelText('Value')).toBeNull();
      expect(
        screen.queryByRole('columnheader', { name: 'Current value' })
      ).toBeNull();
    });

    it('says current values need ADMIN', () => {
      setup();
      expect(
        screen.getByText('Current values need an ADMIN key.')
      ).toBeInTheDocument();
    });

    it('says the values are loading', () => {
      live({ status: 'loading' });
      setup();
      expect(screen.getByText('Loading current values…')).toBeInTheDocument();
    });

    it('warns when the values cannot be loaded, and still lists the options', () => {
      live({ status: 'error' });
      setup();
      expect(
        screen.getByText('Could not load the current values.')
      ).toBeInTheDocument();
      expect(rowCount()).toBe(CATALOG.length);
    });
  });
});
