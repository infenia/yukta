// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import { renderWithProviders } from '@/test/utils/testUtils';
import { TemplateGallery } from '../TemplateGallery';

const ok = (data: unknown) =>
  HttpResponse.json({
    timestamp: 't',
    status: 200,
    message: 'ok',
    data,
    path: '/',
  });

const summaries = [
  {
    id: 'hello',
    title: 'Hello world',
    summary: 'Smallest',
    category: '1-standalone',
    pluginTypes: ['API_TRIGGER'],
  },
  {
    id: 'files',
    title: 'Write files',
    summary: 'Writes',
    category: '10-more-things',
    pluginTypes: ['FILE_WRITE', 'FILE_READ'],
  },
  {
    id: 'two',
    title: 'Second',
    summary: 'Free',
    category: '2-free-deps',
    pluginTypes: ['API_TRIGGER'],
  },
  {
    id: 'loose',
    title: 'No folder',
    summary: 'None',
    pluginTypes: ['API_TRIGGER'],
  },
];

let plugins: unknown[] = [];
let templates: unknown[] = summaries;

const detail = (id: string, types: string[]) => ({
  ...summaries.find((t) => t.id === id),
  pluginTypes: types,
  readme: '',
  session: {
    workflows: {
      main: {
        workflowId: 'main',
        description: '',
        nodes: types.map((type, i) => ({ nodeId: `n${i}`, type, config: {} })),
        edges: [],
      },
    },
  },
});

const server = setupServer(
  http.get('/api/templates', () => ok(templates)),
  http.get('/api/templates/files', () =>
    ok(detail('files', ['FILE_WRITE', 'FILE_READ']))
  ),
  http.get('/api/templates/:id', ({ params }) =>
    ok(detail(String(params['id']), ['API_TRIGGER']))
  ),
  http.get('/api/plugins', () => ok(plugins))
);

beforeAll(() => server.listen());
afterEach(() => {
  server.resetHandlers();
  plugins = [];
  templates = summaries;
});
afterAll(() => server.close());

const off = (type: string) => ({
  type,
  category: 'TERMINAL',
  enabled: false,
  enableWith: `YUKTA_PLUGINS_ENABLED=${type}`,
});

describe('TemplateGallery', () => {
  it('groups the templates under their examples folder, in number order, with the rest last', async () => {
    renderWithProviders(<TemplateGallery onPick={() => undefined} />);
    await screen.findByText('Hello world');
    expect(
      screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    ).toEqual(['Standalone', 'Free deps', 'More things', 'Other']);
  });

  it('lists templates without folders in one grid, with no headings', async () => {
    templates = summaries.map((t) => ({
      id: t.id,
      title: t.title,
      summary: t.summary,
      pluginTypes: t.pluginTypes,
    }));
    renderWithProviders(<TemplateGallery onPick={() => undefined} />);
    expect(await screen.findByText('Hello world')).toBeVisible();
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull();
  });

  it('says on top, on the card and in the preview which switched-off plugins a template needs', async () => {
    plugins = [off('FILE_WRITE'), off('FILE_READ')];
    const user = userEvent.setup();
    renderWithProviders(<TemplateGallery onPick={() => undefined} />);

    // one notice for the whole page, naming every off plugin the templates use
    expect(
      await screen.findByText('Some plugins the templates use are switched off')
    ).toBeVisible();

    // and one on the card that needs them, none on the others
    const files = screen.getByText('Write files').closest('li') as HTMLElement;
    expect(within(files).getByRole('status')).toHaveTextContent(
      'Needs FILE_WRITE, FILE_READ enabled'
    );
    const hello = screen.getByText('Hello world').closest('li') as HTMLElement;
    expect(within(hello).queryByRole('status')).toBeNull();

    // the preview repeats it, and still offers the template
    await user.click(within(files).getByRole('button', { name: 'Preview' }));
    const dialog = await screen.findByRole('dialog', { name: 'Write files' });
    expect(within(dialog).getByRole('status')).toHaveTextContent(
      'YUKTA_PLUGINS_ENABLED=FILE_WRITE,FILE_READ'
    );
    expect(
      within(dialog).getByRole('button', { name: 'Use this template' })
    ).toBeEnabled();
  });

  it('uses the singular when one plugin is off, and shows no notice when none is', async () => {
    plugins = [off('FILE_WRITE')];
    const { unmount } = renderWithProviders(
      <TemplateGallery onPick={() => undefined} />
    );
    expect(
      await screen.findByText('A plugin the templates use is switched off')
    ).toBeVisible();
    unmount();

    plugins = [];
    renderWithProviders(<TemplateGallery onPick={() => undefined} />);
    await screen.findByText('Hello world');
    expect(screen.queryByRole('status')).toBeNull();
  });
});
