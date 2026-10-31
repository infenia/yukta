// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SessionsHeader } from '../SessionsHeader';

describe('SessionsHeader', () => {
  it('shows the title and the description, without a session count', () => {
    const { container } = render(<SessionsHeader />);
    const p = container.querySelector('p');

    expect(
      screen.getByRole('heading', { name: /Sessions/ })
    ).toBeInTheDocument();
    expect(p?.textContent).toBe(
      "A session holds a project's workflows and their runs."
    );
    expect(p?.textContent).not.toMatch(/\d+ sessions?/);
  });

  it('shows the actions it is given', () => {
    render(<SessionsHeader actions={<button type="button">New</button>} />);
    expect(screen.getByRole('button', { name: 'New' })).toBeInTheDocument();
  });
});
