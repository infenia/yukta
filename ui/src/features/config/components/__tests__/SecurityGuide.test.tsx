// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { SecurityGuide } from '../SecurityGuide';
import { KEY_STEPS, LIMIT_STEPS, ROLES } from '../../lib/security';

describe('SecurityGuide', () => {
  it('warns that a fresh server is open, then lists the roles', () => {
    render(<SecurityGuide />);
    expect(
      screen.getByText('Open by default, closed to the network')
    ).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Roles' })).toBeInTheDocument();
    expect(screen.getAllByRole('row')).toHaveLength(ROLES.rows.length + 1);
  });

  it('walks through setting a key, with a copyable example each', () => {
    render(<SecurityGuide />);
    expect(
      screen.getByRole('heading', { name: 'Set up API keys' })
    ).toBeInTheDocument();
    for (const step of KEY_STEPS) {
      expect(
        screen.getByRole('heading', { name: step.title })
      ).toBeInTheDocument();
      expect(screen.getByLabelText(`Copy ${step.label}`)).toBeInTheDocument();
    }
  });

  it('covers commands, hosts and secrets', () => {
    render(<SecurityGuide />);
    for (const step of LIMIT_STEPS) {
      expect(
        screen.getByRole('heading', { name: step.title })
      ).toBeInTheDocument();
    }
  });

  it('renders backticked text as code', () => {
    render(<SecurityGuide />);
    expect(screen.getAllByText('yukta.api-keys')[0].tagName).toBe('CODE');
  });

  it('has complete rows in the roles table', () => {
    for (const row of ROLES.rows) {
      expect(row).toHaveLength(ROLES.columns.length);
    }
  });
});
