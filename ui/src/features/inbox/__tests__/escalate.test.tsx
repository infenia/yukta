// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, expect, it, vi } from 'vitest';
import * as inputsApi from '@/features/workflow/api/inputs';
import { InputRow } from '@/features/workflow/components/InputRow';
import { input } from '../testFixtures';

vi.mock('@/features/workflow/api/inputs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/workflow/api/inputs')>()),
  escalateInput: vi.fn(),
}));

describe('InputRow', () => {
  it('shows why an escalation failed', async () => {
    vi.mocked(inputsApi.escalateInput).mockRejectedValue(
      new Error('no higher tier')
    );
    render(
      <QueryClientProvider client={new QueryClient()}>
        <InputRow request={input()} />
      </QueryClientProvider>
    );

    await userEvent.click(screen.getByRole('button', { name: 'Escalate' }));
    await userEvent.type(screen.getByLabelText('Reason'), 'needs the owner');
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByText(/no higher tier/)).toBeInTheDocument();
  });
});
