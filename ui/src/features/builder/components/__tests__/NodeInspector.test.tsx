// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { useState } from 'react';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import type { DraftNode } from '../../types';
import { NodeInspector } from '../NodeInspector';

const ok = (data: unknown) =>
  HttpResponse.json({ timestamp: 't', status: 200, message: 'ok', data, path: '/' });

const details = (type: string, extra: object = {}) => ({
  type,
  category: 'PROCESSOR',
  description: null,
  usagePattern: null,
  uiDesign: null,
  outputPorts: ['default'],
  ...extra,
});

const server = setupServer(
  http.get('/api/plugins/:type', ({ params }) => {
    if (params.type === 'WITH_SCHEMA') {
      return ok(
        details('WITH_SCHEMA', {
          configSchema: [
            { name: 'url', type: 'STRING', required: true, defaultValue: null, options: [], description: '' },
          ],
        })
      );
    }
    return ok(details(String(params.type), { usagePattern: 'Configuration Parameters: x' }));
  })
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function Harness({ initial, onRemove = vi.fn() }: { initial: DraftNode; onRemove?: () => void }) {
  const [node, setNode] = useState(initial);
  return (
    <>
      <NodeInspector
        node={node}
        onRemove={onRemove}
        onChange={(config) => setNode({ ...node, config })}
      />
      <output data-testid="config">{JSON.stringify(node.config)}</output>
    </>
  );
}

const config = () => screen.getByTestId('config').textContent;

describe('NodeInspector', () => {
  it('renders the plugin form when the plugin declares a schema', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness initial={{ nodeId: 'n1', type: 'WITH_SCHEMA', config: { url: 'a' } }} />
    );
    const url = await screen.findByLabelText(/url/);
    await user.type(url, 'b');
    expect(config()).toBe('{"url":"ab"}');
  });

  it('falls back to a JSON editor with the usage pattern when there is no schema', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={{ nodeId: 'n1', type: 'PLAIN', config: {} }} />);
    const box = await screen.findByLabelText('Configuration (JSON)');
    expect(screen.getByText('Configuration Parameters: x')).toBeInTheDocument();

    await user.click(box);
    await user.paste('{"k":1}');
    expect(config()).toBe('{"k":1}');

    await user.clear(box);
    expect(config()).toBe('{}');
  });

  it('omits the usage help when the plugin has none', async () => {
    server.use(http.get('/api/plugins/:type', () => ok(details('BARE'))));
    renderWithProviders(<Harness initial={{ nodeId: 'n1', type: 'BARE', config: {} }} />);
    await screen.findByLabelText('Configuration (JSON)');
    expect(screen.queryByText('Usage')).not.toBeInTheDocument();
  });

  it('keeps the engine policy apart from the plugin config and drops it when emptied', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={{
          nodeId: 'n1',
          type: 'WITH_SCHEMA',
          config: { url: 'a', engine: { onError: 'ROUTE' } },
        }}
      />
    );
    await screen.findByLabelText(/url/);
    await user.click(screen.getByText(/Error handling/));

    await user.selectOptions(screen.getByLabelText('trigger'), 'ALL_DONE');
    expect(config()).toBe('{"url":"a","engine":{"onError":"ROUTE","trigger":"ALL_DONE"}}');

    await user.selectOptions(screen.getByLabelText('onError'), 'Default (FAIL)');
    await user.selectOptions(screen.getByLabelText('trigger'), 'Default (ALL_SUCCESS)');
    expect(config()).toBe('{"url":"a"}');
  });

  it('keeps the engine policy when the JSON editor changes', async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Harness
        initial={{ nodeId: 'n1', type: 'PLAIN', config: { engine: { trigger: 'ALL_DONE' } } }}
      />
    );
    const box = await screen.findByLabelText('Configuration (JSON)');
    await user.click(box);
    await user.paste('{"a":1}');
    expect(config()).toBe('{"a":1,"engine":{"trigger":"ALL_DONE"}}');
  });

  it('removes the node', async () => {
    const user = userEvent.setup();
    const onRemove = vi.fn();
    renderWithProviders(
      <Harness initial={{ nodeId: 'n1', type: 'PLAIN', config: {} }} onRemove={onRemove} />
    );
    await user.click(await screen.findByRole('button', { name: 'Remove node' }));
    expect(onRemove).toHaveBeenCalled();
  });

  it('shows a loading state while the plugin loads', () => {
    renderWithProviders(<Harness initial={{ nodeId: 'n1', type: 'PLAIN', config: {} }} />);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
  });
});
