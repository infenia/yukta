// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Card, CardContent, CardHeader, CardTitle, cardVariants } from '../card';

describe('Card', () => {
  it('renders a raised surface with its slots', () => {
    render(
      <Card data-testid="card">
        <CardHeader>
          <CardTitle>Summary</CardTitle>
        </CardHeader>
        <CardContent>Body</CardContent>
      </Card>
    );
    const card = screen.getByTestId('card');
    expect(card).toHaveAttribute('data-slot', 'card');
    expect(card).toHaveClass('rounded-xl', 'border', 'bg-card', 'shadow-card');
    expect(screen.getByRole('heading', { name: 'Summary' })).toHaveClass(
      'text-overline'
    );
    expect(screen.getByText('Body')).toHaveAttribute('data-slot', 'card-content');
  });

  it('is static unless interactive', () => {
    expect(cardVariants()).not.toContain('hover:shadow-raised');
    expect(cardVariants({ interactive: true })).toContain('hover:shadow-raised');
    expect(cardVariants({ interactive: true })).toContain('hover:-translate-y-px');
  });

  it('merges caller classes', () => {
    render(<Card data-testid="card" className="p-6" />);
    expect(screen.getByTestId('card')).toHaveClass('p-6', 'rounded-xl');
  });
});
