// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '@/test/utils/testUtils';
import EditionSourceLink from '../EditionSourceLink';
import { getEdition } from '../../api/edition';

vi.mock('../../api/edition', () => ({ getEdition: vi.fn() }));

describe('EditionSourceLink', () => {
  beforeEach(() => {
    vi.mocked(getEdition).mockReset();
  });

  it('links to the source when the edition offers it', async () => {
    vi.mocked(getEdition).mockResolvedValue({
      edition: 'PLUS',
      license: 'AGPL-3.0-only',
      features: ['file-stores'],
      source: 'https://github.com/infenia/yukta',
    });

    renderWithProviders(<EditionSourceLink />);

    const link = await screen.findByRole('link', { name: 'Source code' });
    expect(link).toHaveAttribute('href', 'https://github.com/infenia/yukta');
    expect(screen.getByText(/AGPL-3.0-only/)).toBeInTheDocument();
  });

  it('shows nothing for a build without a source offer', async () => {
    vi.mocked(getEdition).mockResolvedValue({
      edition: 'COMMUNITY',
      license: 'Apache-2.0',
      features: [],
      source: null,
    });

    const { container } = renderWithProviders(<EditionSourceLink />);

    await waitFor(() => expect(getEdition).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('shows nothing while loading or when the request fails', async () => {
    vi.mocked(getEdition).mockRejectedValue(new Error('down'));

    const { container } = renderWithProviders(<EditionSourceLink />);

    await waitFor(() => expect(getEdition).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
