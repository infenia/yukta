// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as inputsApi from '@/features/workflow/api/inputs';
import { ApiError } from '@/lib/apiClient';
import { InputAnswerForm } from '@/features/workflow/components/InputAnswerForm';
import { input } from '../testFixtures';

vi.mock('@/features/workflow/api/inputs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/features/workflow/api/inputs')>()),
  answerInput: vi.fn(),
}));

const show = (request = input(), onAnswered?: () => void) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <InputAnswerForm request={request} onAnswered={onAnswered} />
    </QueryClientProvider>
  );

describe('InputAnswerForm', () => {
  beforeEach(() => {
    vi.mocked(inputsApi.answerInput).mockReset();
    vi.mocked(inputsApi.answerInput).mockResolvedValue(input());
  });

  it('answers with the version the caller saw and reports success', async () => {
    const onAnswered = vi.fn();
    show(input({ version: 7 }), onAnswered);

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));

    await waitFor(() => expect(onAnswered).toHaveBeenCalled());
    expect(inputsApi.answerInput).toHaveBeenCalledWith('e1', 'r1', {
      decision: 'approve',
      expectedVersion: 7,
    });
  });

  it('asks to confirm a HIGH risk answer first, and sends nothing when cancelled', async () => {
    show(input({ risk: 'HIGH' }));

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(screen.getByText(/rated HIGH risk/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(inputsApi.answerInput).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(inputsApi.answerInput).toHaveBeenCalled());
  });

  it('names the rule of a refusal and tells a conflict from other errors', async () => {
    vi.mocked(inputsApi.answerInput).mockRejectedValue(
      new ApiError(403, 'no', [{ field: 'rule', message: 'risk' }])
    );
    show();
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }));
    expect(await screen.findByText('Not allowed: risk')).toBeInTheDocument();
  });
});
