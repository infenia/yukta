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

const server = setupServer(
  http.get('/api/templates', () =>
    ok([
      {
        id: 'hello',
        title: 'Hello world',
        summary: 'Smallest workflow',
        category: '1-standalone',
        pluginTypes: ['CONSOLE_TERMINAL'],
      },
      {
        id: 'loose',
        title: 'No folder',
        summary: 'Has no category',
        pluginTypes: [],
      },
      {
        id: 'files',
        title: 'Write files',
        summary: 'Writes a report',
        category: '10-free-deps',
        pluginTypes: ['CONSOLE_TERMINAL', 'FILE_WRITE', 'FILE_READ'],
      },
    ])
  ),
  http.get('/api/plugins', () =>
    ok([
      { type: 'CONSOLE_TERMINAL', category: 'TERMINAL', enabled: true },
      {
        type: 'FILE_WRITE',
        category: 'PROCESSOR',
        enabled: false,
        enableWith: 'set yukta.plugins.enabled to include FILE_WRITE',
      },
      { type: 'FILE_READ', category: 'PROCESSOR', enabled: false },
    ])
  )
);

beforeAll(() => server.listen());
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('TemplateGallery', () => {
  it('says on the card which plugins are off and what switches them on', async () => {
    renderWithProviders(<TemplateGallery onPick={() => undefined} />);

    const files = (await screen.findByText('Write files')).closest('li');
    const notice = await within(files as HTMLElement).findByRole('status');
    expect(notice).toHaveTextContent('Needs FILE_WRITE, FILE_READ enabled');
    expect(notice).toHaveTextContent(
      'YUKTA_PLUGINS_ENABLED=FILE_WRITE,FILE_READ'
    );
    // the struck-through chip explains itself in the app's tooltip, not the browser's
    const user = userEvent.setup();
    await user.hover(
      within(files as HTMLElement).getByText('FILE_WRITE', { selector: 'li' })
    );
    expect(
      await screen.findByText('Not enabled on this server')
    ).toBeInTheDocument();

    // the folders are headed in their number order (10 after 1), a template with none is Other
    expect(
      screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    ).toEqual(['Standalone', 'Free deps', 'Other']);

    // a template of enabled plugins carries no notice
    const hello = screen.getByText('Hello world').closest('li');
    expect(within(hello as HTMLElement).queryByRole('status')).toBeNull();
  });
});
