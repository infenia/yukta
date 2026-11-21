// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TooltipProvider } from '@/components/ui/tooltip';
import { resetTools } from '../lib/mcpClient';
import { AssistantLauncher } from '../components/AssistantLauncher';
import { openAssistant } from '../lib/open';
import { DEFAULT_SETTINGS, writeKey, writeSettings } from '../lib/settings';
import { nanoSession, sseResponse } from '../testFixtures';

const answer = (words: string) =>
  sseResponse([
    {
      type: 'content_block_delta',
      index: 0,
      delta: { type: 'text_delta', text: words },
    },
  ]);

/** Yukta's MCP endpoint lists one tool; the relay answers with the given words. */
const serve = (words = 'Hello there') =>
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) =>
      Promise.resolve(
        url === '/mcp'
          ? new Response(
              JSON.stringify({
                result: {
                  tools: [
                    { name: 'list_sessions', description: '', inputSchema: {} },
                  ],
                },
              }),
              { headers: { 'content-type': 'application/json' } }
            )
          : answer(words)
      )
    )
  );

const show = (path = '/sessions') =>
  render(
    <MemoryRouter initialEntries={[path]}>
      <TooltipProvider>
        <AssistantLauncher />
      </TooltipProvider>
    </MemoryRouter>
  );

const opener = () => screen.getByRole('button', { name: 'Yukta assistant' });

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  resetTools();
});
afterEach(() => vi.unstubAllGlobals());

describe('AssistantLauncher', () => {
  it('shows only the round button until it is opened', () => {
    show();

    expect(opener()).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.queryByRole('dialog', { hidden: true })
    ).not.toBeInTheDocument();
  });

  it('opens the card on a click, asks for a model first, and closes on the next click', async () => {
    show();

    await userEvent.click(opener());

    const card = await screen.findByRole('dialog', { name: 'Yukta assistant' });
    expect(card).toBeVisible();
    expect(screen.getByText('Experimental')).toBeInTheDocument();
    expect(screen.getByText('No model connected')).toBeInTheDocument();
    expect(opener()).toHaveAttribute('aria-expanded', 'true');

    await userEvent.click(opener());
    expect(card).not.toBeVisible();
    expect(opener()).toHaveFocus();
  });

  it('toggles with Ctrl+J or ⌘J and closes on Escape, returning focus to the button', async () => {
    show();

    fireEvent.keyDown(document, { key: 'j', ctrlKey: true });
    const card = await screen.findByRole('dialog');
    fireEvent.keyDown(document, { key: 'J', metaKey: true });
    expect(card).not.toBeVisible();
    fireEvent.keyDown(document, { key: 'j' });
    expect(card).not.toBeVisible();

    fireEvent.keyDown(document, { key: 'j', ctrlKey: true });
    expect(card).toBeVisible();
    fireEvent.keyDown(card, { key: 'a' });
    expect(card).toBeVisible();
    fireEvent.keyDown(card, { key: 'Escape' });
    expect(card).not.toBeVisible();
    expect(opener()).toHaveFocus();
  });

  it('opens from the palette event even when the button is hidden, and brings the button back', async () => {
    writeSettings({ ...DEFAULT_SETTINGS, hidden: true });
    show();
    expect(
      screen.queryByRole('button', { name: 'Yukta assistant' })
    ).not.toBeInTheDocument();

    act(() => openAssistant());

    expect(await screen.findByRole('dialog')).toBeVisible();
    expect(opener()).toBeInTheDocument();
  });

  it('hides the button from the setup, and keeps it hidden', async () => {
    show();
    await userEvent.click(opener());

    await userEvent.click(
      await screen.findByRole('button', { name: 'Hide the assistant button' })
    );

    expect(
      screen.queryByRole('button', { name: 'Yukta assistant' })
    ).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { hidden: true })).not.toBeVisible();
    expect(
      JSON.parse(localStorage.getItem('yukta.assistant') as string).hidden
    ).toBe(true);
  });

  it('connects a model through the setup, then answers questions', async () => {
    serve('Connected');
    show();
    await userEvent.click(opener());

    await userEvent.type(await screen.findByLabelText('API key'), 'sk-1');
    await userEvent.click(
      screen.getByRole('button', { name: 'Save and test' })
    );

    expect(
      await screen.findByText('claude-sonnet-5-5 · your key')
    ).toBeInTheDocument();
    expect(sessionStorage.getItem('yukta.assistant.key')).toBe('sk-1');
    await userEvent.type(screen.getByLabelText('Message'), 'hello{Enter}');
    expect(await screen.findByText('Connected')).toBeInTheDocument();
  });

  it('starts a new chat and moves between the chat and the settings without losing either', async () => {
    serve('Hello there');
    writeKey('sk-1', false);
    show();
    await userEvent.click(opener());
    await userEvent.type(await screen.findByLabelText('Message'), 'hi{Enter}');
    expect(await screen.findByText('Hello there')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Settings' }));
    expect(screen.getByLabelText('API key')).toHaveValue('sk-1');
    await userEvent.click(screen.getByRole('button', { name: 'Settings' }));
    expect(screen.queryByLabelText('API key')).not.toBeInTheDocument();
    expect(screen.getByText('Hello there')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'New chat' }));
    await waitFor(() =>
      expect(screen.queryByText('Hello there')).not.toBeInTheDocument()
    );
    expect(screen.getByText(/Ask about your sessions/)).toBeInTheDocument();
  });

  it('starts on the built-in model, with no key, in a browser that has it', async () => {
    serve();
    const session = nanoSession(['{"tool":"none"}'], ['Hi from Nano']);
    vi.stubGlobal('LanguageModel', {
      availability: vi.fn().mockResolvedValue('available'),
      create: vi.fn().mockResolvedValue(session),
    });
    show();

    await userEvent.click(opener());

    expect(
      await screen.findByText('Gemini Nano · on this device')
    ).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Message'), 'hello{Enter}');
    expect(await screen.findByText('Hi from Nano')).toBeInTheDocument();
  });

  it('sits higher on the run page, above the docked logs', async () => {
    writeKey('sk-1', false);
    show('/sessions/s1/workflow/w1');

    expect(opener().className).toContain('sm:bottom-68');
    await userEvent.click(opener());
    expect((await screen.findByRole('dialog')).className).toContain(
      'sm:bottom-84'
    );
    expect(screen.getByText('Looking at Workflow w1')).toBeInTheDocument();
  });
  it('expands the card and shrinks it again, and opens the settings from the model name', async () => {
    serve();
    writeKey('sk-1', false);
    show();
    await userEvent.click(opener());
    const card = await screen.findByRole('dialog', { name: 'Yukta assistant' });
    expect(card).not.toHaveClass('sm:w-xl');

    await userEvent.click(screen.getByRole('button', { name: 'Expand' }));
    expect(card).toHaveClass('sm:w-xl');
    await userEvent.click(screen.getByRole('button', { name: 'Shrink' }));
    expect(card).not.toHaveClass('sm:w-xl');

    await userEvent.click(screen.getByRole('button', { name: 'Change model' }));
    expect(screen.getByLabelText('API key')).toBeInTheDocument();
  });

  it('keeps the conversation after the page reloads', async () => {
    serve('Remembered');
    writeKey('sk-1', false);
    const first = show();
    await userEvent.click(opener());
    await userEvent.type(await screen.findByLabelText('Message'), 'hi{Enter}');
    expect(await screen.findByText('Remembered')).toBeInTheDocument();
    first.unmount();

    show();
    await userEvent.click(opener());

    expect(await screen.findByText('Remembered')).toBeInTheDocument();
  });
});
