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
vi.mock('@/features/control/components/CapacityControls', () => ({
  CapacityControls: () => <p>capacity-controls</p>,
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

/** Opens a themed select and chooses an option by its label. */
async function pick(name: string, option: string) {
  await userEvent.click(screen.getByRole('combobox', { name }));
  await userEvent.click(await screen.findByRole('option', { name: option }));
}

describe('ConfigPage', () => {
  beforeEach(() => live({ status: 'denied' }));

  it('lists every option, with the how-it-works note behind the info button', async () => {
    setup();
    expect(
      screen.getByRole('heading', { name: 'Configuration' })
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: 'How configuration works' })
    );
    expect(await screen.findByText(/read once at start/)).toBeInTheDocument();
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
    await pick('Area', 'MCP');
    expect(rowCount()).toBe(CATALOG.filter((o) => o.area === 'MCP').length);
  });

  it('filters by when a change applies', async () => {
    setup();
    await pick('Applies', 'Changes live');
    expect(rowCount()).toBe(
      CATALOG.filter((o) => o.appliesWhen === 'runtime').length
    );
    expect(screen.getAllByText('Live').length).toBeGreaterThan(0);
  });

  it('filters to options that differ in the native image', async () => {
    setup();
    await pick('Mode', 'Differs in native and Docker');
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

  it('switches between the tabs and keeps the choice in the URL', async () => {
    setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Run modes' }));
    expect(screen.getByTestId('search')).toHaveTextContent('?tab=modes');

    await userEvent.click(screen.getByRole('tab', { name: 'Options' }));
    expect(screen.getByTestId('search')).toHaveTextContent(/^$/);
    expect(screen.getByLabelText('Search options')).toBeInTheDocument();
  });

  it('opens an option when anywhere on its row is clicked', async () => {
    setup();
    const button = screen.getByRole('button', { name: 'server.port' });
    expect(button).toHaveAttribute('aria-expanded', 'false');

    // Once open, the purpose also appears in the details panel, so keep the row's node.
    const row = screen.getByText(/HTTP port for the API/);
    await userEvent.click(row);
    expect(button).toHaveAttribute('aria-expanded', 'true');

    await userEvent.click(row);
    expect(button).toHaveAttribute('aria-expanded', 'false');
  });

  it('opens the Security tab from the URL', () => {
    setup('/config?tab=security');
    expect(screen.getByRole('tab', { name: 'Security' })).toHaveAttribute(
      'aria-selected',
      'true'
    );
    expect(
      screen.getByRole('heading', { name: 'Set up API keys' })
    ).toBeInTheDocument();
  });

  it('opens the Capacity tab, where the server limits and drain are changed', async () => {
    setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Capacity' }));
    expect(screen.getByTestId('search')).toHaveTextContent('?tab=capacity');
    expect(screen.getByText('capacity-controls')).toBeInTheDocument();
  });

  it('opens the Capacity tab from a link to it', () => {
    setup('/config?tab=capacity');
    expect(screen.getByText('capacity-controls')).toBeInTheDocument();
  });

  it('opens the Run modes tab', async () => {
    setup();
    await userEvent.click(screen.getByRole('tab', { name: 'Run modes' }));
    expect(screen.getByTestId('search')).toHaveTextContent('?tab=modes');
    expect(
      screen.getByRole('heading', {
        name: 'Example: start on another port',
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
      ).toHaveTextContent('Not setDefault');
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
        screen.getByRole('row', { name: /yukta\.restart\.auto-resume/ })
      ).toHaveTextContent('—');
    });

    it('filters to the options set on this server, and spans the detail row', async () => {
      ready();
      setup();
      await pick('Value', 'Set on this server');
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
