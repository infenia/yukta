// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { fireEvent, render, screen } from '@testing-library/react';
import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { getApiKey, requireApiKey, setApiKey } from '@/lib/apiKey';
import ApiKeyPrompt from '../ApiKeyPrompt';

afterEach(() => setApiKey(null));

describe('ApiKeyPrompt', () => {
  it('stays hidden until the server asks for a key, then stores it and reloads', () => {
    const reload = vi.fn();
    vi.stubGlobal('location', { ...window.location, reload });
    render(<ApiKeyPrompt />);
    expect(screen.queryByRole('alertdialog')).toBeNull();

    act(() => requireApiKey());
    const submit = screen.getByRole('button', { name: 'Use key' });
    expect(submit).toBeDisabled();

    fireEvent.change(screen.getByLabelText('API key'), { target: { value: ' secret ' } });
    fireEvent.click(submit);

    expect(getApiKey()).toBe('secret');
    expect(reload).toHaveBeenCalledOnce();
    vi.unstubAllGlobals();
  });
});
