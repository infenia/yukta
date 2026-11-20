// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { PackagingGuide } from '../PackagingGuide';
import { BUILD_FLAGS, CLI, MATRIX, TUNING } from '../../lib/packaging';

describe('PackagingGuide', () => {
  it('has a section and a full table for each topic', () => {
    render(<PackagingGuide />);
    for (const table of [MATRIX, BUILD_FLAGS, TUNING, CLI]) {
      expect(
        screen.getByRole('heading', { name: table.title })
      ).toBeInTheDocument();
    }
    expect(screen.getAllByRole('table')).toHaveLength(4);
    expect(
      screen.getByRole('heading', { name: 'Running the Docker image' })
    ).toBeInTheDocument();
  });

  it('puts the JVM, native and Docker columns side by side', () => {
    render(<PackagingGuide />);
    const [matrix] = screen.getAllByRole('table');
    for (const name of ['JVM jar', 'Native binary', 'Docker image']) {
      expect(
        within(matrix).getByRole('columnheader', { name })
      ).toBeInTheDocument();
    }
    const row = within(matrix).getByRole('row', { name: /JAVA_TOOL_OPTIONS/ });
    expect(within(row).getByText('Ignored.')).toBeInTheDocument();
  });

  it('renders backticked text as code', () => {
    render(<PackagingGuide />);
    expect(screen.getAllByText('prod,postgres')[0].tagName).toBe('CODE');
    expect(screen.getAllByText('nonroot')[0].tagName).toBe('CODE');
  });

  it('shows a copyable docker run example', () => {
    render(<PackagingGuide />);
    expect(
      screen.getByLabelText('Copy docker run example')
    ).toBeInTheDocument();
    expect(screen.getByText(/ghcr.io\/infenia\/yukta -D/)).toBeInTheDocument();
  });

  it('has unique first cells and complete rows in every table', () => {
    for (const table of [MATRIX, BUILD_FLAGS, TUNING, CLI]) {
      const firsts = table.rows.map((r) => r[0]);
      expect(new Set(firsts).size).toBe(firsts.length);
      for (const row of table.rows) {
        expect(row).toHaveLength(table.columns.length);
      }
    }
  });
});
