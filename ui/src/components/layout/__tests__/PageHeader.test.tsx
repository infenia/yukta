// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from '../PageHeader';

describe('PageHeader', () => {
  it('renders the page title as the only h1, with description and actions', () => {
    render(
      <PageHeader
        title="Sessions"
        description="All sessions"
        actions={<button type="button">New</button>}
      />
    );
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      'Sessions'
    );
    expect(screen.getByText('All sessions')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'New' })).toBeInTheDocument();
    expect(screen.queryByTestId('page-eyebrow')).not.toBeInTheDocument();
  });

  it('shows an eyebrow pill above the title', () => {
    render(<PageHeader title="Plugins" eyebrow="Catalog" />);
    expect(screen.getByTestId('page-eyebrow')).toHaveTextContent('Catalog');
    expect(screen.getByTestId('page-eyebrow')).toHaveClass('rounded-full');
  });
});
