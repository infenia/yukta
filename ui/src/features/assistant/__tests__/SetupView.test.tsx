// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SetupView } from '../components/SetupView';
import { DEFAULT_SETTINGS } from '../lib/settings';
import { sseResponse } from '../testFixtures';

afterEach(() => vi.unstubAllGlobals());

const noNano = {
  state: 'unsupported' as const,
  progress: 0,
  download: vi.fn(),
};

const show = (
  apiKey: string | null = null,
  nano: Parameters<typeof SetupView>[0]['nano'] = noNano,
  settings = DEFAULT_SETTINGS
) => {
  const onSave = vi.fn();
  const onHide = vi.fn();
  render(
    <SetupView
      settings={settings}
      apiKey={apiKey}
      nano={nano}
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

  describe('the built-in model', () => {
    const nano = (
      state: Parameters<typeof SetupView>[0]['nano']['state'],
      progress = 0,
      download = vi.fn()
    ) => ({ state, progress, download });

    it('is not offered in a browser that does not have it', () => {
      show();

      expect(
        screen.queryByRole('button', { name: 'On this device' })
      ).not.toBeInTheDocument();
    });

    it.each([
      ['checking', /Checking this browser/],
      ['unavailable', /cannot run the built-in model/],
      ['downloadable', /downloaded once/],
      ['downloading', /Downloading the model/],
      ['available', /is ready/],
    ] as const)('says what is going on while %s', async (state, text) => {
      show(null, nano(state));

      await userEvent.click(
        screen.getByRole('button', { name: 'On this device' })
      );

      expect(screen.getByText(text)).toBeInTheDocument();
      expect(screen.queryByLabelText('API key')).not.toBeInTheDocument();
    });

    it('starts the download from its button', async () => {
      const download = vi.fn();
      show(null, nano('downloadable', 0, download));
      await userEvent.click(
        screen.getByRole('button', { name: 'On this device' })
      );

      await userEvent.click(
        screen.getByRole('button', { name: 'Download model' })
      );

      expect(download).toHaveBeenCalledTimes(1);
    });

    it('shows the progress of the download', async () => {
      show(null, nano('downloading', 0.426));
      await userEvent.click(
        screen.getByRole('button', { name: 'On this device' })
      );

      expect(screen.getByRole('progressbar')).toHaveAttribute(
        'aria-valuenow',
        '43'
      );
      expect(
        screen.getByRole('button', { name: 'Downloading…' })
      ).toBeDisabled();
    });

    it('is used as soon as it is ready, with the key that was there kept', async () => {
      const { onSave } = show('kept', nano('available'));
      await userEvent.click(
        screen.getByRole('button', { name: 'On this device' })
      );

      await userEvent.click(
        screen.getByRole('button', { name: 'Use this model' })
      );

      expect(onSave).toHaveBeenCalledWith(
        { ...DEFAULT_SETTINGS, provider: 'nano' },
        'kept'
      );
    });

    it('cannot be used before it is ready', async () => {
      const { onSave } = show(null, nano('unavailable'));
      await userEvent.click(
        screen.getByRole('button', { name: 'On this device' })
      );
      const button = screen.getByRole('button', { name: 'Use this model' });

      expect(button).toBeDisabled();
      fireEvent.submit(button.closest('form') as HTMLFormElement);
      expect(onSave).not.toHaveBeenCalled();
    });

    it('stays chosen, with the reason, in a browser that no longer has it', () => {
      show(null, noNano, { ...DEFAULT_SETTINGS, provider: 'nano' });

      expect(
        screen.getByRole('button', { name: 'On this device' })
      ).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByText(/no built-in model/)).toBeInTheDocument();
    });

    it('goes back to a provider with the model it had', async () => {
      show('k', nano('available'), { ...DEFAULT_SETTINGS, provider: 'nano' });

      await userEvent.click(
        screen.getByRole('button', { name: 'Your own provider' })
      );

      expect(screen.getByLabelText('Model')).toHaveValue('claude-sonnet-5-5');
    });
  });

  it('a provider with the built-in fallback uses a ready built-in model without a key', async () => {
    const { onSave } = show(null, {
      state: 'available',
      progress: 1,
      download: vi.fn(),
    });

    await userEvent.click(
      screen.getByLabelText('Use the built-in model when it is ready')
    );
    await userEvent.click(
      screen.getByRole('button', { name: 'Use this model' })
    );

    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({ provider: 'anthropic', device: true }),
      ''
    );
  });
});

describe('older settings', () => {
  it('shows an auto setting as a provider with the built-in fallback on', () => {
    show(
      null,
      { state: 'available', progress: 1, download: vi.fn() },
      {
        ...DEFAULT_SETTINGS,
        provider: 'auto',
        baseUrl: 'http://x/v1',
      }
    );

    expect(
      screen.getByRole('button', { name: 'OpenAI-compatible' })
    ).toHaveAttribute('aria-pressed', 'true');
    expect(
      screen.getByLabelText('Use the built-in model when it is ready')
    ).toBeChecked();
  });

  it('shows an auto setting without a base URL as Anthropic', () => {
    show(
      null,
      { state: 'available', progress: 1, download: vi.fn() },
      {
        ...DEFAULT_SETTINGS,
        provider: 'auto',
        baseUrl: '',
      }
    );

    expect(screen.getByRole('button', { name: 'Anthropic' })).toHaveAttribute(
      'aria-pressed',
      'true'
    );
  });
});
