// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SetupView } from '../components/SetupView';
import { DEFAULT_SETTINGS } from '../lib/settings';
import { sseResponse } from '../testFixtures';

afterEach(() => vi.unstubAllGlobals());

const show = (apiKey: string | null = null) => {
  const onSave = vi.fn();
  const onHide = vi.fn();
  render(
    <SetupView
      settings={DEFAULT_SETTINGS}
      apiKey={apiKey}
      onSave={onSave}
      onHide={onHide}
    />
  );
  return { onSave, onHide };
};

const reply = [
  {
    type: 'content_block_delta',
    index: 0,
    delta: { type: 'text_delta', text: 'OK' },
  },
];

describe('SetupView', () => {
  it('cannot be saved without a key', () => {
    show();

    expect(
      screen.getByRole('button', { name: 'Save and test' })
    ).toBeDisabled();
  });

  it('tests the setup with a call and saves it when the provider answers', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse(reply));
    vi.stubGlobal('fetch', fetchMock);
    const { onSave } = show();

    await userEvent.type(screen.getByLabelText('API key'), ' sk-1 ');
    await userEvent.click(screen.getByLabelText('Keep the key on this device'));
    await userEvent.click(
      screen.getByRole('button', { name: 'Save and test' })
    );

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave).toHaveBeenCalledWith(
      { ...DEFAULT_SETTINGS, remember: true },
      'sk-1'
    );
    expect(fetchMock.mock.calls[0][1].headers['X-Assistant-Key']).toBe('sk-1');
  });

  it('shows why the provider refused and saves nothing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response('{"error":{"message":"invalid x-api-key"}}', {
          status: 401,
        })
      )
    );
    const { onSave } = show('old');

    expect(screen.getByLabelText('API key')).toHaveValue('old');
    await userEvent.click(
      screen.getByRole('button', { name: 'Save and test' })
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'invalid x-api-key'
    );
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Save and test' })).toBeEnabled();
  });

  it('switches to an OpenAI-compatible endpoint with a base URL and its own model', async () => {
    const fetchMock = vi.fn().mockResolvedValue(sseResponse([]));
    vi.stubGlobal('fetch', fetchMock);
    const { onSave } = show('k');
    expect(screen.queryByLabelText(/Base URL/)).not.toBeInTheDocument();

    await userEvent.click(
      screen.getByRole('button', { name: 'OpenAI-compatible' })
    );
    expect(screen.getByLabelText('Model')).toHaveValue('');
    expect(
      screen.getByRole('button', { name: 'Save and test' })
    ).toBeDisabled();
    await userEvent.type(screen.getByLabelText('Model'), 'gpt-x');
    await userEvent.type(
      screen.getByLabelText(/Base URL/),
      'http://localhost:11434/v1'
    );
    // choosing the same provider again keeps what was typed
    await userEvent.click(
      screen.getByRole('button', { name: 'OpenAI-compatible' })
    );
    expect(screen.getByLabelText('Model')).toHaveValue('gpt-x');
    await userEvent.click(
      screen.getByRole('button', { name: 'Save and test' })
    );

    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0][0]).toMatchObject({
      provider: 'openai',
      model: 'gpt-x',
      baseUrl: 'http://localhost:11434/v1',
    });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/assistant/proxy/openai');
  });

  it('does not test while a test is running or when the form is not ready', async () => {
    let release: (value: Response) => void = () => {};
    const fetchMock = vi.fn().mockReturnValue(
      new Promise<Response>((resolve) => {
        release = resolve;
      })
    );
    vi.stubGlobal('fetch', fetchMock);
    show('k');
    const form = screen
      .getByRole('button', { name: 'Save and test' })
      .closest('form') as HTMLFormElement;

    await userEvent.click(
      screen.getByRole('button', { name: 'Save and test' })
    );
    expect(screen.getByRole('button', { name: 'Checking…' })).toBeDisabled();
    fireEvent.submit(form);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    release(sseResponse(reply));
    await screen.findByRole('button', { name: 'Save and test' });

    await userEvent.clear(screen.getByLabelText('API key'));
    fireEvent.submit(form);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('hides the assistant button', async () => {
    const { onHide } = show();

    await userEvent.click(
      screen.getByRole('button', { name: 'Hide the assistant button' })
    );

    expect(onHide).toHaveBeenCalled();
  });
});
